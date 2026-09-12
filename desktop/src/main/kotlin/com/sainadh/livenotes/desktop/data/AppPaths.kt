package com.sainadh.livenotes.desktop.data

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

class AppPaths(val root: File = defaultRoot()) {
    val recordings = File(root, "recordings")
    val models = File(root, "models")
    val speakerModels = File(root, "speaker-models")
    val work = File(root, "work")
    init { listOf(root, recordings, models, speakerModels, work).forEach { check(it.isDirectory || it.mkdirs()) } }
    fun audio(id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid recording ID" }
        return File(recordings, "$id.wav")
    }
    companion object {
        fun defaultRoot(): File = System.getProperty("livenotes.dataDir")?.let(::File)
            ?: if (System.getProperty("os.name").startsWith("Windows")) {
                val parent = System.getenv("LOCALAPPDATA") ?: File(System.getProperty("user.home"), "AppData/Local").path
                File(parent, "LiveMeetingNotes")
            } else File(System.getProperty("user.home"), ".local/share/LiveMeetingNotes")
        fun resources(): File = System.getProperty("compose.application.resources.dir")?.let(::File)
            ?: System.getProperty("livenotes.resourcesDir")?.let(::File)
            ?: File("resources/windows-x64")
    }
}

/** A second window must never recover files still owned by an active recorder. */
class SingleInstance(root: File) : AutoCloseable {
    private val channel = FileChannel.open(File(root, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private val lock: FileLock = try {
        checkNotNull(channel.tryLock()) { "LiveMeetingNotes is already running. Open its existing window." }
    } catch (error: Throwable) { channel.close(); throw error }
    override fun close() { lock.release(); channel.close() }
}
