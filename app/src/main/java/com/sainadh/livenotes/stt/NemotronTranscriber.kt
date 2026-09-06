package com.sainadh.livenotes.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    private val captureWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "nemotron-microphone")
    }

    // Only worker accesses the native handle; only captureWorker owns AudioRecord.
    // Inference cannot delay microphone reads or microphone shutdown.
    private var handle = 0L
    private val sampleRateHz = 16000
    private val chunkFrames = sampleRateHz / 2

    private class Capture {
        // Ten seconds of 16 kHz float PCM (640 KB), regardless of recording length.
        val chunks = ArrayBlockingQueue<FloatArray>(20)
        val finished = CountDownLatch(1)
        @Volatile var failure: String? = null

        fun enqueue(pcm: FloatArray) {
            check(chunks.offer(pcm)) {
                "Transcription could not keep up with the microphone (10 seconds of queued audio). " +
                    "Recording stopped; some recent audio could not be saved. Try a smaller model."
            }
        }
    }

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
     * Stops capture without waiting on model loading or inference. Queued audio
     * and the microphone tail are drained before the stream is finalized.
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
                    worker.execute { complete(session, null, null) }
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
            captureWorker.shutdown()
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
        val capture = Capture()
        var captureStarted = false
        var streamOpened = false
        var failure: String? = null
        var finalUpdate: TranscriptUpdate? = null
        val segments = NativeTranscriptSegments()
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
            synchronized(stateLock) {
                if (!wantsCapture(session)) return
                captureWorker.execute { runCapture(session, capture) }
                captureStarted = true
            }

            while (capture.finished.count != 0L || capture.chunks.isNotEmpty()) {
                if (isDestroyed()) break
                val pcm = capture.chunks.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val result = nativeFeedPcm(handle, pcm)
                segments.update(result).forEach { update -> postTranscript(session, update) }
            }
        } catch (error: Throwable) {
            failure = describeFailure(error)
        } finally {
            synchronized(stateLock) {
                if (state != State.DESTROYED) state = State.STOPPING
            }
            // The mic owner stops independently even when nativeFeedPcm is slow.
            // Never release a native session until its actual call has returned.
            if (captureStarted) capture.finished.await()
            if (streamOpened && failure == null && !isDestroyed()) {
                try {
                    // Capture errors still preserve all audio accepted before the error.
                    finalUpdate = segments.finish(nativeFinalizeStream(handle))
                } catch (error: Throwable) {
                    failure = describeFailure(error)
                }
            }
            failure = failure ?: capture.failure
            if (streamOpened && failure != null && finalUpdate == null) {
                finalUpdate = segments.interrupted()
            }
            if (failure != null || isDestroyed()) {
                val cleanupFailure = releaseHandle()
                failure = failure ?: cleanupFailure
            }
            complete(session, failure, finalUpdate)
        }
    }

    private fun runCapture(session: Long, capture: Capture) {
        var record: AudioRecord? = null
        val pcm16 = ShortArray(chunkFrames)
        var bufferedFrames = 0
        fun read(mic: AudioRecord, maxFrames: Int = chunkFrames): Int {
            val n = mic.read(
                pcm16, bufferedFrames, minOf(chunkFrames - bufferedFrames, maxFrames),
                AudioRecord.READ_NON_BLOCKING
            )
            check(n >= 0) { "Microphone read failed ($n)" }
            bufferedFrames += n
            if (bufferedFrames == chunkFrames) {
                capture.enqueue(toFloatPcm(pcm16, bufferedFrames))
                bufferedFrames = 0
            }
            return n
        }
        try {
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
            synchronized(stateLock) {
                if (!wantsCapture(session)) return
                mic.startRecording()
                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "AudioRecord failed to start recording"
                }
                state = State.LISTENING
            }
            postActive(session) { listener.onStateChanged("listening") }
            while (wantsCapture(session)) {
                if (read(mic) == 0) Thread.sleep(10)
            }
            if (!isDestroyed()) {
                // AudioRecord.stop() can discard unread samples. Drain available
                // nonblocking reads first, including the last incomplete chunk.
                // At most two seconds of PCM is read to bound a misbehaving driver.
                var tailFrames = 0
                val tailLimit = sampleRateHz * 2
                while (tailFrames < tailLimit && !isDestroyed()) {
                    val n = read(mic, tailLimit - tailFrames)
                    if (n == 0) break
                    tailFrames += n
                }
                check(tailFrames < tailLimit) {
                    "Microphone did not finish draining; recording stopped before all recent audio was saved"
                }
                if (bufferedFrames > 0 && !isDestroyed()) {
                    capture.enqueue(toFloatPcm(pcm16, bufferedFrames))
                    bufferedFrames = 0
                }
            }
        } catch (error: Throwable) {
            capture.failure = describeFailure(error)
        } finally {
            record?.let { mic ->
                try {
                    if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop()
                } catch (error: Throwable) {
                    capture.failure = capture.failure ?: describeFailure(error)
                } finally {
                    try {
                        mic.release()
                    } catch (error: Throwable) {
                        capture.failure = capture.failure ?: describeFailure(error)
                    }
                }
            }
            // Even a read/driver failure must retain samples collected before it.
            // Queue overflow stays explicit if there is still no room for this tail.
            if (bufferedFrames > 0 && !isDestroyed()) {
                try {
                    capture.enqueue(toFloatPcm(pcm16, bufferedFrames))
                } catch (error: Throwable) {
                    capture.failure = capture.failure ?: describeFailure(error)
                }
            }
            synchronized(stateLock) {
                if (state != State.DESTROYED && generation == session) state = State.STOPPING
            }
            postSession(session) { listener.onStateChanged("finishing") }
            capture.finished.countDown()
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

    private fun postTranscript(session: Long, update: TranscriptUpdate) =
        postSession(session) { listener.onTranscriptUpdate(update) }

    private fun postSession(session: Long, callback: () -> Unit) {
        mainHandler.post {
            synchronized(stateLock) {
                // Draining after stop may still commit text. Only destroy cancels delivery.
                if (state != State.DESTROYED && generation == session) callback()
            }
        }
    }

    private fun complete(session: Long, failure: String?, finalUpdate: TranscriptUpdate?) {
        mainHandler.post {
            synchronized(stateLock) {
                if (state == State.DESTROYED || generation != session) return@post
                if (failure != null) listener.onError(failure)
                if (state == State.DESTROYED) return@post
                if (finalUpdate != null) listener.onTranscriptUpdate(finalUpdate)
                if (state == State.DESTROYED) return@post
                state = State.IDLE
                listener.onStateChanged("stopped")
            }
        }
    }
}
