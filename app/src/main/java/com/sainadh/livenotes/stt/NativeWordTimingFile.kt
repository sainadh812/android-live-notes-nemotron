package com.sainadh.livenotes.stt

import java.io.File
import java.io.FileOutputStream

/** Optional decoder timing. An absent sidecar always falls back to estimated segment timing. */
internal object NativeWordTimingFile {
    data class Word(val text: String, val startMs: Long, val endMs: Long)

    fun write(audioFile: File, encoded: String) {
        if (encoded.isBlank()) return
        // Reject malformed native data before publishing it.
        require(parse(encoded).isNotEmpty())
        val destination = File(audioFile.parentFile, "${audioFile.name}.words")
        val temporary = File(audioFile.parentFile, "${audioFile.name}.words.part")
        FileOutputStream(temporary).use { it.write(encoded.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(!destination.exists() && temporary.renameTo(destination)) { "Could not save word timing" }
    }

    fun read(audioFile: File): List<Word> {
        val sidecar = File(audioFile.parentFile, "${audioFile.name}.words")
        if (!sidecar.isFile || sidecar.length() > 16 * 1024 * 1024) return emptyList()
        return runCatching { parse(sidecar.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    fun parse(encoded: String): List<Word> {
        var previousStart = -1L
        return encoded.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            require(parts.size == 3)
            val start = parts[0].toLong()
            val end = parts[1].toLong()
            require(start >= 0 && start >= previousStart && end >= start)
            previousStart = start
            val hex = parts[2]
            require(hex.length % 2 == 0)
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim()
            require(text.isNotEmpty())
            Word(text, start, end)
        }.toList()
    }
}
