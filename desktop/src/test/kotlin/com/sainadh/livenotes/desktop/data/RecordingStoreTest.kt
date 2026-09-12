package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.desktop.SpeakerName
import com.sainadh.livenotes.desktop.TranscriptTurn
import com.sainadh.livenotes.stt.LiveTranscriptBuffer
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class RecordingStoreTest {
    @Test fun hourOfRevisionsPreservesFullTranscriptAcrossReopen() = withPaths { paths ->
        val id = UUID.randomUUID().toString()
        val expected = (0 until 7_200).joinToString("") { "word$it " }
        val live = LiveTranscriptBuffer()
        RecordingStore(paths).use { store ->
            store.begin(id, 1_000)
            repeat(7_200) { index ->
                val partial = TranscriptUpdate(index.toLong(), "draft ", TranscriptStatus.PARTIAL,
                    appendToPrevious = true, startMs = index * 500L, endMs = (index + 1) * 500L)
                val final = partial.copy(text = "word$index ", status = TranscriptStatus.FINAL)
                store.saveSegment(id, partial); store.saveSegment(id, final)
                val preview = checkNotNull(live.update(final))
                assertTrue(preview.text.length <= 4_000)
                assertTrue(preview.segments.size <= 120)
            }
            store.saveSegment(id, TranscriptUpdate(0, "stale", TranscriptStatus.PARTIAL))
            store.finish(id, false, 3_600_000, "word0", false)
            assertEquals(expected, live.fullText())
        }
        RecordingStore(paths).use { store ->
            val document = checkNotNull(store.document(id))
            assertEquals(expected, document.text)
            assertEquals(7_200, document.words.size)
            assertEquals(1, store.list().size)
        }
    }
    @Test fun interruptedWavAndPartialTextRecoverWithoutInventingFinalWords() = withPaths { paths ->
        val id = UUID.randomUUID().toString()
        RecordingStore(paths).use { store ->
            store.begin(id, 1_000)
            store.saveSegment(id, TranscriptUpdate(0, "unfinished words", TranscriptStatus.PARTIAL, startMs = 0, endMs = 100))
            val writer = WavFileWriter(paths.audio(id), 16_000)
            writer.write(ShortArray(1600) { 100 }, 0, 1600)
            writer.finish()
        }
        RecordingStore(paths).use { store ->
            assertEquals(1, store.recoverInterrupted())
            val document = checkNotNull(store.document(id))
            assertTrue(document.entry.hasAudio)
            assertTrue(document.entry.interrupted)
            assertEquals(100L, document.entry.durationMs)
            assertEquals("unfinished words", document.text)
            assertEquals(TranscriptStatus.INTERRUPTED, store.segments(id).single().status)
        }
    }
    @Test fun speakerRenameMergeCorrectionAndExportSurviveReopen() = withPaths { paths ->
        val id = UUID.randomUUID().toString()
        RecordingStore(paths).use { store ->
            store.begin(id, 1_000)
            store.saveSegment(id, TranscriptUpdate(0, "Hello world. Next person.", TranscriptStatus.FINAL, startMs = 0, endMs = 4_000))
            store.finish(id, false, 4_000, "Hello", false)
            store.saveSpeakers(id, listOf(SpeakerName("one", "Speaker 1"), SpeakerName("two", "Speaker 2")), listOf(
                TranscriptTurn(0, "one", 0, 2_000, 0, 12), TranscriptTurn(1, "two", 2_000, 4_000, 13, 25)))
            store.renameSpeaker(id, "one", "Ravi")
            store.mergeSpeakers(id, "two", "one")
            store.assignSpeaker(id, 1, null)
        }
        RecordingStore(paths).use { store ->
            val document = checkNotNull(store.document(id))
            assertEquals(listOf(SpeakerName("one", "Ravi")), document.speakers)
            assertEquals(1, document.entry.speakerCount)
            val text = exportTranscript(document)
            assertTrue(text.contains("Ravi: Hello world."))
            assertTrue(text.contains("Unknown speaker: Next person."))
        }
    }
    @Test fun uncommittedDeletionRestoresAudioOnRestart() = withPaths { paths ->
        val id = UUID.randomUUID().toString()
        RecordingStore(paths).use { store ->
            store.begin(id, 1_000)
            val writer = WavFileWriter(paths.audio(id), 16_000)
            writer.write(ShortArray(1600), 0, 1600); writer.finish()
            store.finish(id, true, 100, "", false)
        }
        val trash = java.io.File(paths.work, "trash/$id").apply { mkdirs() }
        Files.move(paths.audio(id).toPath(), java.io.File(trash, "$id.wav").toPath())
        RecordingStore(paths).use { store ->
            store.recoverInterrupted()
            assertTrue(paths.audio(id).isFile)
            assertEquals(1, store.list().size)
            store.delete(id)
            assertTrue(store.list().isEmpty())
            assertFalse(paths.audio(id).exists())
        }
    }
    private fun withPaths(test: (AppPaths) -> Unit) {
        val root = Files.createTempDirectory("desktop-library-").toFile()
        try { test(AppPaths(root)) } finally { root.deleteRecursively() }
    }
}
