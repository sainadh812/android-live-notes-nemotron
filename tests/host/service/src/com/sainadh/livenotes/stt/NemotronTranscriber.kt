package com.sainadh.livenotes.stt
import android.content.Context
class NemotronTranscriber(context: Context, modelPath: String, language: String, val listener: SpeechTranscriber.Listener) {
    var stopRequested = false
    var destroyed = false
    init { last = this }
    fun start() { listener.onStateChanged("listening") }
    fun stop() { stopRequested = true }
    fun destroy() { destroyed = true }
    companion object {
        lateinit var last: NemotronTranscriber
        fun isAvailable() = true
        fun loadError(): String? = null
    }
}
