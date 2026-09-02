package com.sainadh.livenotes.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread

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
        init {
            // Load order matters: dependents before the library that needs
            // them. ggml-base has no ggml deps; ggml and ggml-cpu depend on
            // it; libtranscribe depends on all three; nemotron_jni depends
            // on libtranscribe. jniLibs packaging preserves these names.
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("transcribe")
            System.loadLibrary("nemotron_jni")
        }
    }

    private external fun nativeInit(modelPath: String, language: String, attContextRight: Int): Long
    private external fun nativeFeedPcm(handle: Long, pcm: FloatArray): String
    private external fun nativeFinalizeStream(handle: Long): String
    private external fun nativeRestartStream(handle: Long, language: String, attContextRight: Int): Boolean
    private external fun nativeDestroy(handle: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var handle: Long = 0L
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    // 16kHz mono float32 PCM, matching transcribe.cpp's required input
    // format exactly (see transcribe.h: "16 kHz mono float32").
    private val sampleRateHz = 16000
    private val chunkFrames = (sampleRateHz * 0.5).toInt() // ~500ms chunks

    /** Starts model load (once) + mic capture + streaming feed loop. */
    fun start() {
        if (running) return

        if (!File(modelPath).exists()) {
            listener.onError("Model file not found at $modelPath")
            return
        }

        mainHandler.post { listener.onStateChanged("loading model") }

        thread(name = "nemotron-init") {
            handle = nativeInit(modelPath, language, attContextRight)
            if (handle == 0L) {
                mainHandler.post {
                    listener.onError("Failed to load Nemotron model / open stream (see logcat NemotronJNI)")
                    listener.onStateChanged("stopped")
                }
                return@thread
            }
            startCapture()
        }
    }

    private fun startCapture() {
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            mainHandler.post { listener.onError("AudioRecord.getMinBufferSize failed") }
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 4
            )
        } catch (e: SecurityException) {
            mainHandler.post { listener.onError("Microphone permission missing") }
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { listener.onError("AudioRecord failed to initialize") }
            return
        }

        audioRecord = record
        running = true
        record.startRecording()
        mainHandler.post { listener.onStateChanged("listening") }

        captureThread = thread(name = "nemotron-capture") {
            val pcm16 = ShortArray(chunkFrames)
            val pcmF32 = FloatArray(chunkFrames)
            var lastCommitted = ""

            while (running) {
                val n = record.read(pcm16, 0, chunkFrames)
                if (n <= 0) continue

                // AudioRecord gives 16-bit PCM; transcribe.cpp wants
                // float32 in [-1, 1] - standard PCM16->F32 normalization.
                for (i in 0 until n) {
                    pcmF32[i] = pcm16[i] / 32768.0f
                }
                val chunk = if (n == pcmF32.size) pcmF32 else pcmF32.copyOf(n)

                val result = nativeFeedPcm(handle, chunk)
                val parts = result.split('\u0001', limit = 2)
                val committed = parts.getOrElse(0) { "" }
                val tentative = parts.getOrElse(1) { "" }

                if (committed.isNotEmpty() && committed != lastCommitted) {
                    lastCommitted = committed
                    mainHandler.post { listener.onTranscript(committed, isFinal = false) }
                }
                if (tentative.isNotEmpty()) {
                    mainHandler.post { listener.onTranscript(tentative, isFinal = false) }
                }
            }
        }
    }

    /** Stops capture and finalizes the stream, emitting one final transcript. */
    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(2000)
        captureThread = null

        audioRecord?.let {
            it.stop()
            it.release()
        }
        audioRecord = null

        if (handle != 0L) {
            val finalText = nativeFinalizeStream(handle)
            if (finalText.isNotBlank()) {
                mainHandler.post { listener.onTranscript(finalText, isFinal = true) }
            }
        }
        mainHandler.post { listener.onStateChanged("stopped") }
    }

    /** Releases the native model/session. Call once when done with this instance. */
    fun destroy() {
        stop()
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }
}
