package com.sainadh.livenotes.desktop.speakers

import java.nio.file.Path
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SpeakerTurnsTest {
    @Test fun keepsEveryWordIncludingUnknownAndOverlapping() {
        val words = (0 until 6).map { SpeakerWord("word$it", it * 1000L, (it + 1) * 1000L) } +
            SpeakerWord("untimed", null, null)
        val turns = assignSpeakerTurns(words, listOf(
            SpeakerSpan("speaker_1", 0, 2000), SpeakerSpan("speaker_2", 3000, 5000),
            SpeakerSpan("speaker_3", 4000, 5000), SpeakerSpan("speaker_1", 5000, 6000)))
        assertEquals((0 until 7).toList(), turns.flatMap { (it.startWordIndex until it.endWordIndexExclusive).toList() })
        assertEquals(listOf("speaker_1", null, "speaker_2", null, "speaker_1", null), turns.map { it.speakerId })
        assertTrue(turns[3].overlapping)
        assertFalse(turns[1].overlapping)
    }

    @Test fun speakerBoundaryInsideWordIsUncertainRatherThanOverlap() {
        val turns = assignSpeakerTurns(listOf(SpeakerWord("hello", 0, 1000)), listOf(
            SpeakerSpan("speaker_1", 0, 500), SpeakerSpan("speaker_2", 500, 1000)))
        assertNull(turns.single().speakerId)
        assertFalse(turns.single().overlapping)
    }

    @Test fun duplicatedSpansDoNotInflateCoverage() {
        val span = SpeakerSpan("speaker_1", 0, 200)
        assertNull(assignSpeakerTurns(listOf(SpeakerWord("hello", 0, 1000)), listOf(span, span, span)).single().speakerId)
    }

    @Test fun allowsTwentySpeakersWithoutLosingOrMergingDifferentTurns() {
        val words = (0 until 20).map { SpeakerWord("word$it", it * 1000L, (it + 1) * 1000L) }
        val spans = (0 until 20).map { SpeakerSpan("speaker_${it + 1}", it * 1000L, (it + 1) * 1000L) }
        assertEquals(20, assignSpeakerTurns(words, spans).map { it.speakerId }.toSet().size)
    }

    @Test fun longMeetingKeepsOriginalWordIndices() {
        val words = (0 until 14400).map { SpeakerWord("word$it", it * 250L, (it + 1) * 250L) }
        val spans = (0 until 240).map { SpeakerSpan("speaker_${it % 20 + 1}", it * 15000L, (it + 1) * 15000L) }
        val turns = assignSpeakerTurns(words, spans)
        assertEquals(240, turns.size)
        assertEquals((0 until words.size).toList(), turns.flatMap { (it.startWordIndex until it.endWordIndexExclusive).toList() })
    }

    @Test fun rejectsMalformedWorkerResults() {
        val client = DiarizationClient(Path.of("worker"), Path.of("models"), Path.of("jobs"))
        assertThrows(IllegalArgumentException::class.java) {
            client.validateResult(DiarizationResult(durationMs = 1000, spans = listOf(SpeakerSpan("speaker_1", 0, 2000)), speakerCount = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.validateResult(DiarizationResult(durationMs = 1000, spans = listOf(SpeakerSpan("speaker_1", 0, 500)), speakerCount = 2))
        }
        client.validateResult(DiarizationResult(durationMs = 1000, spans = emptyList(), speakerCount = 0))
    }
}
