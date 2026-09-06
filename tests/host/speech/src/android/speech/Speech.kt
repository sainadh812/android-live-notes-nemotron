package android.speech
import android.content.Context
import android.content.Intent
import android.os.Bundle
interface RecognitionListener {
    fun onReadyForSpeech(params: Bundle?)
    fun onBeginningOfSpeech()
    fun onRmsChanged(rmsdB: Float)
    fun onBufferReceived(buffer: ByteArray?)
    fun onEndOfSpeech()
    fun onError(error: Int)
    fun onResults(results: Bundle?)
    fun onPartialResults(partialResults: Bundle?)
    fun onEvent(eventType: Int, params: Bundle?)
}
class SpeechRecognizer {
    lateinit var callback: RecognitionListener
    var stopped = false
    var destroyed = false
    fun setRecognitionListener(listener: RecognitionListener) { callback = listener }
    fun startListening(intent: Intent) {
        if (failStart) throw IllegalStateException("Provider rejected start")
    }
    fun stopListening() {
        stopped = true
        if (failStop) throw IllegalStateException("Provider rejected stop")
    }
    fun cancel() { if (callbackOnCancel) callback.onError(ERROR_CLIENT) }
    fun destroy() { destroyed = true }
    companion object {
        var available = true
        var failStart = false
        var failStop = false
        var callbackOnCancel = false
        val instances = mutableListOf<SpeechRecognizer>()
        fun isRecognitionAvailable(context: Context) = available
        fun createSpeechRecognizer(context: Context) = SpeechRecognizer().also { instances += it }
        const val RESULTS_RECOGNITION = "results"
        const val ERROR_NETWORK_TIMEOUT = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO = 3
        const val ERROR_SERVER = 4
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_RECOGNIZER_BUSY = 8
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_TOO_MANY_REQUESTS = 10
        const val ERROR_SERVER_DISCONNECTED = 11
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
        const val ERROR_CANNOT_CHECK_SUPPORT = 14
        const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15
    }
}
object RecognizerIntent {
    const val ACTION_RECOGNIZE_SPEECH = "recognize"
    const val EXTRA_LANGUAGE_MODEL = "model"
    const val LANGUAGE_MODEL_FREE_FORM = "free"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_LANGUAGE_PREFERENCE = "preference"
    const val EXTRA_PARTIAL_RESULTS = "partial"
    const val EXTRA_MAX_RESULTS = "max"
    const val EXTRA_CALLING_PACKAGE = "package"
}
