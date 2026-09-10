package com.sainadh.livenotes.stt
import android.content.Context
import java.io.File
class NemotronTranscriber(context: Context, modelPath: String, language: String, val listener: SpeechTranscriber.Listener, val audioFile: File? = null) {
    var stopRequested = false
    var destroyed = false
    init { last = this; instances += this }
    fun start() { listener.onStateChanged("loading model") }
    fun stop() { stopRequested = true }
    fun destroy(onDestroyed: () -> Unit = {}) { destroyed = true; onDestroyed() }
    companion object {
        lateinit var last: NemotronTranscriber
        val instances = mutableListOf<NemotronTranscriber>()
        fun isAvailable() = true
        fun loadError(): String? = null
    }
}
