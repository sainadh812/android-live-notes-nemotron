package com.sainadh.livenotes.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechTranscriber(
    private val context: Context,
    private val listener: Listener
) : RecognitionListener {
    interface Listener {
        fun onTranscript(text: String, isFinal: Boolean)
        fun onStateChanged(state: String)
        fun onError(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var consecutiveRecoverableErrors = 0

    fun start() {
        if (running) return
        running = true
        consecutiveRecoverableErrors = 0
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                listener.onError("Speech recognition is not available on this device")
                running = false
                return@post
            }
            val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
            listener.onStateChanged("listening")
            speechRecognizer.startListening(buildIntent())
        }
    }

    fun stop() {
        running = false
        consecutiveRecoverableErrors = 0
        mainHandler.post {
            recognizer?.stopListening()
            listener.onStateChanged("stopped")
        }
    }

    fun destroy() {
        running = false
        consecutiveRecoverableErrors = 0
        mainHandler.post {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        consecutiveRecoverableErrors = 0
        listener.onStateChanged("ready")
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        listener.onStateChanged("processing")
    }

    override fun onError(error: Int) {
        val message = describeError(error)
        listener.onError(message)

        when (error) {
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                running = false
                listener.onStateChanged("stopped")
            }

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveRecoverableErrors = 0
                if (running) restartSoon(1200L)
            }

            else -> {
                consecutiveRecoverableErrors += 1
                if (!running) return
                if (consecutiveRecoverableErrors >= 3) {
                    running = false
                    listener.onError("Stopped retrying after repeated recognizer failures. ${message.removePrefix("SpeechRecognizer error: ")}")
                    listener.onStateChanged("stopped")
                    return
                }
                val delayMs = (1500L * consecutiveRecoverableErrors).coerceAtMost(5000L)
                restartSoon(delayMs)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        consecutiveRecoverableErrors = 0
        emitMatches(results, isFinal = true)
        if (running) restartSoon(700L)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        emitMatches(partialResults, isFinal = false)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun emitMatches(bundle: Bundle?, isFinal: Boolean) {
        val values = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val text = values.firstOrNull().orEmpty().trim()
        if (text.isNotBlank()) {
            listener.onTranscript(text, isFinal)
        }
    }

    private fun restartSoon(delayMs: Long) {
        mainHandler.postDelayed({
            if (running) {
                recognizer?.cancel()
                recognizer?.destroy()
                val freshRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(this)
                }
                recognizer = freshRecognizer
                freshRecognizer.startListening(buildIntent())
            }
        }, delayMs)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.ENGLISH.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "SpeechRecognizer error: network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "SpeechRecognizer error: network unavailable"
        SpeechRecognizer.ERROR_AUDIO -> "SpeechRecognizer error: audio recording failed"
        SpeechRecognizer.ERROR_SERVER -> "SpeechRecognizer error: server problem"
        SpeechRecognizer.ERROR_CLIENT -> "SpeechRecognizer error: client problem"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SpeechRecognizer error: no speech detected"
        SpeechRecognizer.ERROR_NO_MATCH -> "SpeechRecognizer error: no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "SpeechRecognizer error: recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "SpeechRecognizer error: microphone permission missing"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "SpeechRecognizer error: too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SpeechRecognizer error: server disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "SpeechRecognizer error: English is not supported by this recognizer"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "SpeechRecognizer error: English speech pack is unavailable right now"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "SpeechRecognizer error: cannot check language support"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "SpeechRecognizer error: cannot monitor language pack downloads"
        else -> "SpeechRecognizer error: $error"
    }
}
