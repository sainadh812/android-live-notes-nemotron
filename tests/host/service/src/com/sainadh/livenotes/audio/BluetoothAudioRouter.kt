package com.sainadh.livenotes.audio
import android.content.Context
class BluetoothAudioRouter(context: Context) {
    fun activate(mode: String): String { active = true; return "Phone microphone" }
    fun release() { active = false }
    companion object { var active = false }
}
