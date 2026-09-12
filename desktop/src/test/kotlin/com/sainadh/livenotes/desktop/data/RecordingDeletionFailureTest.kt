package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.desktop.SpeakerName
import com.sainadh.livenotes.desktop.TranscriptTurn
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID

class RecordingDeletionFailureTest {
    @Test fun databaseFailureRestoresAudioTimingAndAllMetadata() = withMeeting { paths, store, id, audio, timing ->
        DriverManager.getConnection("jdbc:sqlite:${File(paths.root, "notes.db").absolutePath}").use { db ->
            db.createStatement().use { it.execute("""
                CREATE TRIGGER prevent_meeting_delete BEFORE DELETE ON recordings
                BEGIN SELECT RAISE(ABORT, 'injected write failure'); END
            """.trimIndent()) }
        }
        assertTrue(runCatching { store.delete(id) }.isFailure)
        assertArrayEquals(audio, paths.audio(id).readBytes())
        assertArrayEquals(timing, File(paths.recordings, "$id.wav.words").readBytes())
        val document = checkNotNull(store.document(id))
        assertEquals("Hello world", document.text)
        assertEquals(listOf(SpeakerName("speaker_1", "Ravi")), document.speakers)
        assertEquals("Saved summary", document.summary)
        assertEquals(1, document.turns.size)
        assertEquals(1, store.list().size)
    }

    @Test fun secondFileMoveFailureRestoresFirstFileAndKeepsMeeting() = withMeeting { paths, store, id, audio, timing ->
        val trash = File(paths.work, "trash/$id").apply { mkdirs() }
        val conflictingBackup = File(trash, "$id.wav.words").apply { writeText("existing backup") }
        assertTrue(runCatching { store.delete(id) }.isFailure)
        assertArrayEquals(audio, paths.audio(id).readBytes())
        assertArrayEquals(timing, File(paths.recordings, "$id.wav.words").readBytes())
        assertFalse(File(trash, "$id.wav").exists())
        assertEquals("existing backup", conflictingBackup.readText())
        assertEquals("Hello world", checkNotNull(store.document(id)).text)
        assertEquals(1, store.list().size)
    }

    private fun withMeeting(test: (AppPaths, RecordingStore, String, ByteArray, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("meeting-delete-failure-").toFile()
        try {
            val paths = AppPaths(root)
            RecordingStore(paths).use { store ->
                val id = UUID.randomUUID().toString()
                store.begin(id, 1_000)
                store.saveSegment(id, TranscriptUpdate(0, "Hello world", TranscriptStatus.FINAL, startMs = 0, endMs = 100))
                val writer = WavFileWriter(paths.audio(id), 16_000)
                writer.write(ShortArray(1600) { 42 }, 0, 1600)
                writer.finish()
                val timing = "0\t50\t48656c6c6f\n50\t100\t776f726c64\n".toByteArray()
                File(paths.recordings, "$id.wav.words").writeBytes(timing)
                store.finish(id, true, 100, "Hello world", false)
                store.saveSpeakers(id, listOf(SpeakerName("speaker_1", "Ravi")),
                    listOf(TranscriptTurn(0, "speaker_1", 0, 100, 0, 11)))
                store.saveSummary(id, "Saved summary", "Context", listOf("Next action"))
                test(paths, store, id, paths.audio(id).readBytes(), timing)
            }
        } finally { root.deleteRecursively() }
    }
}
