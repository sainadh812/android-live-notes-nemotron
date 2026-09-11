package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus.FINAL
import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    private fun segment(id: Long, text: String, start: Long?, end: Long?, append: Boolean = false) =
        RecordingSegment(id, text, FINAL, append, start, end)

    @Test fun wordsPreserveOffsetsWhitespaceAndPunctuation() {
        val segments = listOf(segment(0, "  Hello,  world!\n", 1_000, 2_200))
        val cues = recordingWordCues(segments)
        assertEquals(listOf("Hello,", "world!"), cues.map { it.text })
        assertEquals(listOf(1_000L, 1_600L), cues.map { it.startMs })
        assertEquals(listOf(1_600L, 2_200L), cues.map { it.endMs })
        cues.forEach { cue ->
            assertEquals(cue.text, transcriptText(segments).substring(cue.startChar, cue.endChar))
            assertTrue(cue.isEstimated)
        }
    }

    @Test fun silenceBetweenSegmentsDoesNotBecomeAWord() {
        val segments = listOf(segment(1, "Second", 5_000, 6_000), segment(0, "First", 0, 1_000))
        val cues = recordingWordCues(segments)
        assertEquals("First\nSecond", transcriptText(segments))
        assertEquals(1_000L, cues[0].endMs)
        assertEquals(5_000L, cues[1].startMs)
        assertFalse(cues.any { 3_000L in it.startMs until it.endMs })
    }

    @Test fun nativePiecesThatSplitAWordProduceOneCompleteCue() {
        val segments = listOf(segment(0, "Hel", 0, 300), segment(1, "lo world", 300, 1_000, append = true))
        val cues = recordingWordCues(segments)
        assertEquals("Hello world", transcriptText(segments))
        assertEquals(listOf("Hello", "world"), cues.map { it.text })
        assertEquals(0L, cues[0].startMs)
        assertEquals(500L, cues[0].endMs)
        assertEquals(500L, cues[1].startMs)
        assertEquals(1_000L, cues[1].endMs)
    }

    @Test fun missingInvalidOrZeroLengthTimingNeverGetsFabricated() {
        val segments = listOf(segment(0, "legacy", null, null), segment(1, "backward", 50, 10),
            segment(2, "negative", -5, 50), segment(3, "zero", 20, 20), segment(4, "valid", 100, 200))
        assertEquals(listOf("valid"), recordingWordCues(segments).map { it.text })
        assertEquals(emptyList<WordCue>(), recordingWordCues(emptyList()))
    }

    @Test fun partiallyUntimedSplitWordHasNoMisleadingCue() {
        val segments = listOf(segment(0, "Hel", null, null), segment(1, "lo world", 300, 1_000, append = true))
        assertEquals(listOf("world"), recordingWordCues(segments).map { it.text })
    }

    @Test fun unicodeWordsAndSpacesKeepTextAndOffsets() {
        val segments = listOf(segment(0, "నమస్కారం\u2003世界", 0, 1_000))
        val cues = recordingWordCues(segments)
        assertEquals(listOf("నమస్కారం", "世界"), cues.map { it.text })
        cues.forEach { assertEquals(it.text, transcriptText(segments).substring(it.startChar, it.endChar)) }
        assertEquals(1_000L, cues.last().endMs)
    }

    @Test fun hourLongTranscriptKeepsEveryWordAndTimingOffset() {
        val segments = (0 until 7_200).map { index ->
            segment(index.toLong(), "word$index ", index * 500L, (index + 1) * 500L, append = true)
        }.reversed()
        val text = transcriptText(segments)
        val cues = recordingWordCues(segments)
        assertEquals((0 until 7_200).joinToString(" ") { "word$it" } + " ", text)
        assertEquals(7_200, cues.size)
        assertEquals(0L, cues.first().startMs)
        assertEquals(3_600_000L, cues.last().endMs)
        cues.forEach { cue -> assertEquals(cue.text, text.substring(cue.startChar, cue.endChar)) }
    }
}
