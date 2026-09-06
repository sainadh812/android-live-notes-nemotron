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
) {
    interface Listener {
        fun onTranscript(text: String, isFinal: Boolean)
        fun onStateChanged(state: String)
        fun onError(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var stopping = false
    private var destroyed = false
    private var sessionActive = false
    private var generation = 0
    private var consecutiveRecoverableErrors = 0
    private var lastHypothesis = ""
    private var restartTask: Runnable? = null
    private var stopTimeout: Runnable? = null

    fun start() = onMain {
        if (running || stopping || destroyed) return@onMain
        running = true
        consecutiveRecoverableErrors = 0
        startSession()
    }

    /** Wait for the recognizer's final result before announcing that capture stopped. */
    fun stop() = onMain {
        if (destroyed || stopping) return@onMain
        running = false
        stopping = true
        cancelRestart()
        if (!sessionActive) {
            emitLastHypothesis()
            finishStop()
            return@onMain
        }
        // Some recognition providers never deliver a result after stopListening().
        // Preserve their latest hypothesis and bound the wait in that case.
        stopTimeout = Runnable {
            emitLastHypothesis()
            finishStop()
        }.also { mainHandler.postDelayed(it, 5_000L) }
        try {
            recognizer?.stopListening()
        } catch (_: RuntimeException) {
            emitLastHypothesis()
            finishStop()
        }
    }

    fun destroy() = onMain {
        destroyed = true
        running = false
        stopping = false
        cancelRestart()
        cancelStopTimeout()
        disposeRecognizer()
    }

    private fun startSession() {
        if (!running || destroyed) return
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                fail("Speech recognition is not available on this device")
                return
            }
            disposeRecognizer()
            lastHypothesis = ""
            val sessionGeneration = generation
            val currentRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = currentRecognizer
            currentRecognizer.setRecognitionListener(callbacks(sessionGeneration))
            sessionActive = true
            listener.onStateChanged("listening")
            currentRecognizer.startListening(buildIntent())
        } catch (error: RuntimeException) {
            fail(error.message ?: "Could not start speech recognition")
        }
    }

    private fun callbacks(sessionGeneration: Int) = object : RecognitionListener {
        private fun isCurrent() = !destroyed && generation == sessionGeneration

        override fun onReadyForSpeech(params: Bundle?) {
            if (isCurrent()) listener.onStateChanged("ready")
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (isCurrent()) listener.onStateChanged("processing")
        }
        override fun onError(error: Int) {
            if (!isCurrent()) return
            sessionActive = false
            if (stopping) {
                emitLastHypothesis()
                finishStop()
                return
            }
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(describeError(error))

                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveRecoverableErrors = 0
                    listener.onStateChanged("Waiting for speech")
                    restartSoon(1_200L)
                }

                else -> {
                    consecutiveRecoverableErrors += 1
                    if (consecutiveRecoverableErrors >= 3) {
                        fail("Stopped retrying after repeated recognizer failures. ${describeError(error)}")
                    } else {
                        listener.onError(describeError(error))
                        restartSoon((1_500L * consecutiveRecoverableErrors).coerceAtMost(5_000L))
                    }
                }
            }
        }
        override fun onResults(results: Bundle?) {
            if (!isCurrent()) return
            sessionActive = false
            consecutiveRecoverableErrors = 0
            val text = match(results).ifBlank { lastHypothesis }
            if (text.isNotBlank()) listener.onTranscript(text, isFinal = true)
            lastHypothesis = ""
            if (stopping) finishStop() else if (running) restartSoon(700L)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent() || (!running && !stopping)) return
            val text = match(partialResults)
            if (text.isNotBlank()) {
                lastHypothesis = text
                listener.onTranscript(text, isFinal = false)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun fail(reason: String) {
        listener.onError(reason)
        emitLastHypothesis()
        finishStop()
    }

    private fun finishStop() {
        running = false
        stopping = false
        cancelRestart()
        cancelStopTimeout()
        disposeRecognizer()
        listener.onStateChanged("stopped")
    }

    private fun emitLastHypothesis() {
        if (lastHypothesis.isNotBlank()) listener.onTranscript(lastHypothesis, isFinal = true)
        lastHypothesis = ""
    }

    private fun disposeRecognizer() {
        // Invalidate callbacks before cancel/destroy, which may trigger provider callbacks.
        generation += 1
        sessionActive = false
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }

    private fun match(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()

    private fun restartSoon(delayMs: Long) {
        cancelRestart()
        restartTask = Runnable {
            restartTask = null
            startSession()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelRestart() {
        restartTask?.let(mainHandler::removeCallbacks)
        restartTask = null
    }

    private fun cancelStopTimeout() {
        stopTimeout?.let(mainHandler::removeCallbacks)
        stopTimeout = null
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
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
