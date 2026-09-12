package com.sainadh.livenotes.desktop.stt

import java.io.File

/** One worker owns each native handle. The shared bridge selects four CPU threads. */
internal object NativeSpeech : SpeechNativeApi {
    @Volatile private var loaded = false

    @Synchronized
    override fun ensureLoaded() {
        if (loaded) return
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val candidates = buildList {
            System.getProperty("livenotes.native.dir")?.let { add(File(it)) }
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, "native")) }
            add(File("resources/windows-x64/native"))
            add(File("desktop/resources/windows-x64/native"))
            add(File("native/build/bridge/Release"))
            add(File("desktop/native/build/bridge/Release"))
        }
        val bridge = if (windows) "livenotes_jni.dll" else System.mapLibraryName("livenotes_jni")
        val directory = candidates.firstOrNull { File(it, bridge).isFile }
            ?: error("Speech engine is missing. Reinstall the app or build desktop/native/build-windows.ps1.")
        // Windows dependency resolution must not depend on the process PATH.
        for (name in listOf("ggml-base", "ggml-cpu", "ggml", "transcribe", "livenotes_jni")) {
            val library = File(directory, if (windows) "$name.dll" else System.mapLibraryName(name))
            if (library.isFile) System.load(library.canonicalPath)
            else if (windows || name == "livenotes_jni") error("Speech engine library missing: ${library.name}")
        }
        loaded = true
    }

    override external fun nativeInit(modelPath: String, language: String, attContextRight: Int): Long
    override external fun nativeFeedPcm(handle: Long, pcm: FloatArray): String?
    override external fun nativeFinalizeStream(handle: Long): String
    override external fun nativeWordTimings(handle: Long): String
    override external fun nativeWasTruncated(handle: Long): Boolean
    override external fun nativeDestroy(handle: Long)
}
