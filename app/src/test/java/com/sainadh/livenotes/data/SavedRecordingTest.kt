package com.sainadh.livenotes.data

import org.junit.Assert.*
import org.junit.Test

class SavedRecordingTest {
    private fun segment(id: Long, text: String, status: String = "FINAL", append: Boolean = true,
        recording: String = "one", date: String = "2026-09-06", updated: Long = id) =
        TranscriptSegmentEntity(recording, id, date, text, status, append, id, updated, 1, 0)

    @Test fun archiveKeepsWordBoundariesAndRecordingIdentityAcrossMidnight() {
        val rows = listOf(segment(2, "world", "INTERRUPTED", date = "2026-09-07"),
            segment(1, "Hello "), segment(0, "Another", recording = "two", updated = 100))
        val recordings = savedRecordings(rows, emptyList())
        assertEquals(listOf("two", "one"), recordings.map { it.recordingId })
        assertEquals("Hello world", recordings[1].text)
        assertEquals("2026-09-06", recordings[1].dateKey)
        assertTrue(recordings[1].hasUnconfirmedWords)
        assertFalse(recordings[0].hasUnconfirmedWords)
    }

    @Test fun independentUtterancesHaveNewlinesAndEmptyWithdrawalsStayHidden() {
        val rows = listOf(segment(0, "First", append = false), segment(1, "Second", append = false),
            segment(2, "", "INTERRUPTED"), segment(0, "", recording = "empty"))
        val recordings = savedRecordings(rows, emptyList())
        assertEquals(1, recordings.size)
        assertEquals("First\nSecond", recordings.single().text)
        assertFalse(recordings.single().hasUnconfirmedWords)
    }

    @Test fun legacyArchivePreservesUncertainRowsRatherThanInventingRevisions() {
        val rows = listOf(TranscriptChunkEntity(2, "2026-09-06", "hello world", true, 2),
            TranscriptChunkEntity(1, "2026-09-06", "hello", false, 1))
        val note = savedRecordings(emptyList(), rows).single()
        assertEquals("hello\nhello world", note.text)
        assertTrue(note.hasUnconfirmedWords)
        assertEquals("legacy:2026-09-06", note.recordingId)
        assertNull(note.audioFileName)
        assertEquals(RecordingAudioStatus.UNAVAILABLE, note.audioStatus)
        assertTrue(note.wordCues.isEmpty())
    }

    @Test fun audioOnlyRecordingIsVisibleWithoutRecognizedSpeech() {
        val info = RecordingEntity("silent", "2026-09-06", "Quiet meeting", 100, 2_000,
            "silent.wav", RecordingAudioStatus.READY, 2_100)
        val saved = savedRecordings(emptyList(), emptyList(), listOf(info)).single()
        assertEquals("silent", saved.recordingId)
        assertEquals("Quiet meeting", saved.title)
        assertEquals("", saved.text)
        assertEquals("silent.wav", saved.audioFileName)
        assertEquals(2_000L, saved.durationMs)
        assertEquals(100L, saved.startedAtEpochMs)
        assertFalse(saved.hasUnconfirmedWords)
        assertTrue(saved.wordCues.isEmpty())
    }

    @Test fun metadataAndSegmentsMergeIntoOneSessionAndKeepTheirTiming() {
        val info = RecordingEntity("one", "2026-09-06", "Planning", 100, 3_000,
            "one.wav", RecordingAudioStatus.READY, 3_100)
        val rows = listOf(segment(1, "Hello world", date = "2026-09-07").copy(startMs = 500, endMs = 2_000))
        val saved = savedRecordings(rows, emptyList(), listOf(info)).single()
        assertEquals("Planning", saved.title)
        assertEquals("2026-09-06", saved.dateKey)
        assertEquals(3_100L, saved.updatedAtEpochMs)
        assertEquals("Hello world", saved.text)
        assertEquals(listOf("Hello", "world"), saved.wordCues.map { it.text })
        assertEquals(500L, saved.wordCues.first().startMs)
        assertEquals(2_000L, saved.wordCues.last().endMs)
    }
}
