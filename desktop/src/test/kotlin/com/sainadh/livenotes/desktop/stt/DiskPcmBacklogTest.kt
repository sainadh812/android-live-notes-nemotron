package com.sainadh.livenotes.desktop.stt

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DiskPcmBacklogTest {
    @Test fun committedChunksPreserveOrderAndOnlyFinishPublishesPartialTail() {
        val directory = Files.createTempDirectory("pcm-backlog-test").toFile()
        try {
            DiskPcmBacklog(directory).use { backlog ->
                backlog.append(ShortArray(7999) { it.toShort() })
                assertNull(backlog.next(false))
                backlog.append(shortArrayOf(7999, -32768, 32767))
                val first = checkNotNull(backlog.next(false))
                assertEquals(8000, first.size)
                first.forEachIndexed { index, value -> assertEquals(index / 32768f, value, 0f) }
                assertNull(backlog.next(false))
                assertArrayEquals(floatArrayOf(-1f, 32767 / 32768f), backlog.next(true), 0f)
                assertNull(backlog.next(true))
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun startupCleanupRemovesOnlyOwnedRegularFilesAndPreservesAudioAndForeignFiles() {
        val directory = Files.createTempDirectory("pcm-backlog-cleanup").toFile()
        try {
            val owned = File(directory, ".speech-backlog-${UUID.randomUUID()}.pcm").apply { writeText("abandoned") }
            val preserved = listOf("meeting.wav", "meeting.wav.part", "recording.pcm", ".speech-backlog-invalid.pcm")
                .map { name -> File(directory, name).apply { writeText("preserve") } }
            val ownedDirectory = File(directory, ".speech-backlog-${UUID.randomUUID()}.pcm").apply { mkdir() }
            val link = File(directory, ".speech-backlog-${UUID.randomUUID()}.pcm").toPath()
            val linkCreated = runCatching { Files.createSymbolicLink(link, preserved.first().toPath()); true }.getOrDefault(false)
            assertEquals(1, DiskPcmBacklog.cleanupAbandoned(directory))
            assertFalse(owned.exists())
            preserved.forEach { assertEquals("preserve", it.readText()) }
            assertTrue(ownedDirectory.isDirectory)
            if (linkCreated) assertTrue(Files.isSymbolicLink(link))
            assertEquals(0, DiskPcmBacklog.cleanupAbandoned(directory))
        } finally { directory.deleteRecursively() }
    }
}
