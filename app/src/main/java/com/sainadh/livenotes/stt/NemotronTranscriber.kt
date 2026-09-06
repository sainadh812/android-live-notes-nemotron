package com.sainadh.livenotes.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device streaming ASR using transcribe.cpp + NVIDIA's
 * nemotron-3.5-asr-streaming-0.6b (or nemotron-speech-streaming-en-0.6b),
 * as a drop-in replacement for SpeechTranscriber's Android SpeechRecognizer
 * wrapper.
 *
 * Implements the SAME [SpeechTranscriber.Listener] contract as the
 * existing recognizer so ForegroundListeningService can swap between the
 * two without any other code changes.
 *
 * Owns raw microphone capture directly via AudioRecord (16kHz mono PCM),
 * unlike SpeechTranscriber which delegates capture to the OS
 * SpeechRecognizer. This class chunks live audio and feeds it straight
 * into the native streaming session.
 *
 * modelPath must point at a GGUF file already present on device storage
 * (e.g. downloaded to context.filesDir/models/ on first run - this class
 * does not fetch it itself).
 *
 * language must be a locale tag the loaded model supports, e.g. "en-US".
 * nemotron-3.5-asr-streaming-0.6b has no implicit default - passing an
 * unsupported tag fails nativeInit.
 *
 * attContextRight selects the cache-aware streaming latency/accuracy
 * tradeoff (nemotron-3.5 menu: {0,3,6,13} -> {0,240,480,1040} ms
 * lookahead). Pass -1 for the model's trained default (highest accuracy,
 * highest latency) unless you have a specific reason to trade accuracy
 * for lower latency.
 */
class NemotronTranscriber(
    private val context: Context,
    private val modelPath: String,
    private val language: String = "en-US",
    private val attContextRight: Int = -1,
    private val listener: SpeechTranscriber.Listener
) {
    companion object {
        // Tracks whether the native libs loaded successfully. A failure
        // here (e.g. UnsatisfiedLinkError from a 16KB-page-size mismatch
        // on newer devices) used to be uncaught and could crash the whole
        // app process since this ran inside Service.onCreate(). Now it's
        // caught, recorded, and callers check isAvailable() first so a
        // native load failure degrades to "fall back to SpeechTranscriber"
        // instead of "app crashes / silently does nothing."
        @Volatile private var nativeLibsLoaded = false
        @Volatile private var nativeLoadError: String? = null

        init {
            try {
                // Load order matters: dependents before the library that
                // needs them. ggml-base has no ggml deps; ggml and
                // ggml-cpu depend on it; libtranscribe depends on all
                // three; nemotron_jni depends on libtranscribe. jniLibs
                // packaging preserves these names.
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("transcribe")
                System.loadLibrary("nemotron_jni")
                nativeLibsLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                nativeLoadError = e.message ?: "UnsatisfiedLinkError loading native libs"
            } catch (e: Throwable) {
                nativeLoadError = e.message ?: e.javaClass.simpleName
            }
        }

        /** True if all native libs loaded successfully at class-load time. */
        fun isAvailable(): Boolean = nativeLibsLoaded

        /** Non-null failure reason when [isAvailable] is false. */
        fun loadError(): String? = nativeLoadError
    }

    private external fun nativeInit(modelPath: String, language: String, attContextRight: Int): Long
    private external fun nativeFeedPcm(handle: Long, pcm: FloatArray): String
    private external fun nativeFinalizeStream(handle: Long): String
    private external fun nativeRestartStream(handle: Long, language: String, attContextRight: Int): Boolean
    private external fun nativeDestroy(handle: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private enum class State { IDLE, STARTING, LISTENING, STOPPING, DESTROYED }
    private val stateLock = Any()
    private var state = State.IDLE
    private var generation = 0L
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nemotron-worker")
    }

    // Only the worker accesses the native session and AudioRecord. In particular,
    // stopping never frees a session that nativeInit/nativeFeedPcm is still using.
    private var handle = 0L
    private val sampleRateHz = 16000
    private val chunkFrames = sampleRateHz / 2

    /** Starts once; another start is accepted after the stopped callback. */
    fun start() {
        synchronized(stateLock) {
            if (state != State.IDLE) return
            state = State.STARTING
            val session = ++generation
            worker.execute { runSession(session) }
        }
    }

    /**
     * Requests cancellation without waiting on model loading or inference.
     * The final transcript precedes the stopped callback, which signals that
     * capture and finalization have finished. Repeated pending stops coalesce.
     */
    fun stop() {
        synchronized(stateLock) {
            when (state) {
                State.DESTROYED, State.STOPPING -> return
                State.IDLE -> {
                    state = State.STOPPING
                    val session = generation
                    worker.execute { complete(session, null, "") }
                }
                State.STARTING, State.LISTENING -> state = State.STOPPING
            }
        }
    }

    /** Suppresses callbacks immediately; the worker frees resources when safe. */
    fun destroy() {
        synchronized(stateLock) {
            if (state == State.DESTROYED) return
            state = State.DESTROYED
            worker.execute { releaseHandle() }
            worker.shutdown()
        }
    }

    private fun wantsCapture(session: Long): Boolean = synchronized(stateLock) {
        generation == session && (state == State.STARTING || state == State.LISTENING)
    }

    private fun isDestroyed(): Boolean = synchronized(stateLock) {
        state == State.DESTROYED
    }

    private fun postActive(session: Long, callback: () -> Unit) {
        mainHandler.post {
            synchronized(stateLock) {
                if (wantsCapture(session)) callback()
            }
        }
    }

    private fun runSession(session: Long) {
        var record: AudioRecord? = null
        var streamOpened = false
        var failure: String? = null
        var finalText = ""
        val pcm16 = ShortArray(chunkFrames)
        var bufferedFrames = 0
        try {
            if (!wantsCapture(session)) return
            check(nativeLibsLoaded) {
                "Native ASR libraries failed to load: ${nativeLoadError ?: "unknown reason"}"
            }
            if (handle == 0L) {
                check(File(modelPath).exists()) { "Model file not found at $modelPath" }
                postActive(session) { listener.onStateChanged("loading model") }
                handle = nativeInit(modelPath, language, attContextRight)
                check(handle != 0L) { "Failed to load Nemotron model / open stream" }
            } else {
                postActive(session) { listener.onStateChanged("restarting") }
                check(nativeRestartStream(handle, language, attContextRight)) {
                    "Failed to restart Nemotron stream"
                }
            }
            streamOpened = true
            if (!wantsCapture(session)) return

            val minBufBytes = AudioRecord.getMinBufferSize(
                sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBufBytes > 0) { "AudioRecord.getMinBufferSize failed ($minBufBytes)" }
            val mic = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufBytes * 4, chunkFrames * 2)
                )
            } catch (error: SecurityException) {
                throw IllegalStateException("Microphone permission missing: ${error.message.orEmpty()}", error)
            }
            record = mic
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            // Serialize this short transition with stop/destroy so cancellation
            // cannot return and then allow a pending microphone start.
            synchronized(stateLock) {
                if (!wantsCapture(session)) return
                mic.startRecording()
                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord failed to start recording"
                }
                state = State.LISTENING
            }
            postActive(session) { listener.onStateChanged("listening") }

            var lastFullText = ""
            while (wantsCapture(session)) {
                // Polling avoids a blocking read that would need a different
                // thread to stop/release the microphone. Preserve 500 ms chunks.
                val n = mic.read(
                    pcm16, bufferedFrames, chunkFrames - bufferedFrames,
                    AudioRecord.READ_NON_BLOCKING
                )
                check(n >= 0) { "Microphone read failed ($n)" }
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                bufferedFrames += n
                if (bufferedFrames < chunkFrames) continue
                if (!wantsCapture(session)) break
                val result = nativeFeedPcm(handle, toFloatPcm(pcm16, bufferedFrames))
                bufferedFrames = 0
                val parts = result.split('\u0001', limit = 2)
                val currentText = parts.getOrElse(0) { "" } + parts.getOrElse(1) { "" }
                if (currentText.isNotEmpty() && currentText != lastFullText) {
                    lastFullText = currentText
                    postActive(session) { listener.onTranscript(currentText, isFinal = false) }
                }
            }
        } catch (error: Throwable) {
            failure = describeFailure(error)
        } finally {
            synchronized(stateLock) {
                if (state != State.DESTROYED) state = State.STOPPING
            }
            // Native calls and mic cleanup all run after the actual feed call
            // returns. There is no timeout after which a live session is freed.
            record?.let { mic ->
                try {
                    if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop()
                } catch (error: Throwable) {
                    failure = failure ?: describeFailure(error)
                } finally {
                    try {
                        mic.release()
                    } catch (error: Throwable) {
                        failure = failure ?: describeFailure(error)
                    }
                }
            }
            if (streamOpened && failure == null && !isDestroyed()) {
                try {
                    if (bufferedFrames > 0) {
                        nativeFeedPcm(handle, toFloatPcm(pcm16, bufferedFrames))
                    }
                    finalText = nativeFinalizeStream(handle)
                } catch (error: Throwable) {
                    failure = describeFailure(error)
                }
            }
            if (failure != null || isDestroyed()) {
                val cleanupFailure = releaseHandle()
                failure = failure ?: cleanupFailure
            }
            complete(session, failure, finalText)
        }
    }

    private fun toFloatPcm(pcm: ShortArray, count: Int) =
        FloatArray(count) { pcm[it] / 32768.0f }

    private fun describeFailure(error: Throwable): String = when (error) {
        is SecurityException -> "Microphone permission missing: ${error.message.orEmpty()}"
        else -> error.message ?: error.javaClass.simpleName
    }

    /** Called only by the worker; clearing first also makes cleanup idempotent. */
    private fun releaseHandle(): String? {
        val previous = handle
        handle = 0L
        if (previous == 0L) return null
        return try {
            nativeDestroy(previous)
            null
        } catch (error: Throwable) {
            describeFailure(error)
        }
    }

    private fun complete(session: Long, failure: String?, finalText: String) {
        mainHandler.post {
            synchronized(stateLock) {
                if (state == State.DESTROYED || generation != session) return@post
                if (failure != null) listener.onError(failure)
                if (state == State.DESTROYED) return@post
                if (finalText.isNotBlank()) listener.onTranscript(finalText, isFinal = true)
                if (state == State.DESTROYED) return@post
                state = State.IDLE
                listener.onStateChanged("stopped")
            }
        }
    }
}
