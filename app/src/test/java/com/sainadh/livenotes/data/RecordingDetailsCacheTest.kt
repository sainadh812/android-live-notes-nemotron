package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecordingDetailsCacheTest {
    private fun recording(id: String = "meeting") = SavedRecording(
        id, "2026-09-11", "Hello 世界", 10, false,
        durationMs = 1_000, audioFileName = "$id.wav", audioStatus = RecordingAudioStatus.READY,
        segments = listOf(RecordingSegment(0, "Hello 世界", TranscriptStatus.FINAL, startMs = 0, endMs = 1_000))
    )
    private val cues = listOf(
        WordCue("Hello", 0, 600, 0, 0, 5, false),
        WordCue("世界", 600, 1_000, 1, 6, 8, false)
    )

    @Test fun repeatedAndConcurrentDetailLoadsReadTimingOnceAndPreserveTheRecording() = runTest {
        var reads = 0
        val cache = RecordingDetailsCache { reads++; delay(10); cues }
        val source = recording()
        val first = async { cache.load(source) }
        val second = async { cache.load(source.copy()) }
        val details = first.await()
        assertSame(details, second.await())
        assertSame(details, cache.load(source))
        assertEquals(1, reads)
        assertEquals(source, details.copy(nativeWordCues = emptyList()))
        assertEquals(cues, details.wordCues)
        details.wordCues.forEach { cue -> assertEquals(cue.text, details.text.substring(cue.startChar, cue.endChar)) }
    }

    @Test fun newRevisionInvalidatesCacheAndOnlyOneRecordingIsRetained() = runTest {
        var reads = 0
        val cache = RecordingDetailsCache { reads++; cues }
        val first = recording()
        cache.load(first)
        cache.load(first.copy(updatedAtEpochMs = 20, durationMs = 2_000))
        assertEquals(2, reads)
        cache.load(recording("another"))
        cache.load(first)
        assertEquals(4, reads)
    }

    @Test fun unavailableAndInProgressAudioNeverReadsTimingFiles() = runTest {
        val cache = RecordingDetailsCache { error("Should not read timing files") }
        val textOnly = recording().copy(audioFileName = null, audioStatus = RecordingAudioStatus.UNAVAILABLE)
        val inProgress = recording().copy(audioStatus = RecordingAudioStatus.RECORDING)
        assertSame(textOnly, cache.load(textOnly))
        assertSame(inProgress, cache.load(inProgress))
    }
}
