package com.sainadh.livenotes.desktop.stt

/** Internal seam for deterministic capture/lifecycle tests without model or microphone hardware. */
internal interface SpeechNativeApi {
    fun ensureLoaded()
    fun nativeInit(modelPath: String, language: String, attContextRight: Int): Long
    fun nativeFeedPcm(handle: Long, pcm: FloatArray): String?
    fun nativeFinalizeStream(handle: Long): String
    fun nativeWordTimings(handle: Long): String
    fun nativeWasTruncated(handle: Long): Boolean
    fun nativeDestroy(handle: Long)
}
