package com.sainadh.livenotes.stt
import android.content.Context
class NemotronTranscriber(context: Context, modelPath: String, language: String, val listener: SpeechTranscriber.Listener) {
    var stopRequested = false
    var destroyed = false
    init { last = this }
    fun start() { listener.onStateChanged("loading model") }
    fun stop() { stopRequested = true }
    fun destroy(onDestroyed: () -> Unit = {}) { destroyed = true; onDestroyed() }
    companion object {
        lateinit var last: NemotronTranscriber
        fun isAvailable() = true
        fun loadError(): String? = null
    }
}
