package com.sainadh.livenotes.desktop.ui

import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.desktop.TranscriptTurn
import org.junit.Assert.*
import org.junit.Test

class TranscriptPresentationTest {
    @Test fun hourLongDocumentPreservesEveryCharacterWordAndSpeakerRange() {
        val text = StringBuilder()
        val words = (0 until 7_200).map { index ->
            val word = "word$index"
            val start = text.length
            text.append(word).append(if (index % 30 == 29) '\n' else ' ')
            WordCue(word, index * 500L, (index + 1) * 500L, index.toLong(), start, start + word.length, false)
        }
        val turns = words.chunked(30).mapIndexed { index, group ->
            TranscriptTurn(index, "speaker${index % 20}", group.first().startMs, group.last().endMs,
                group.first().startChar, group.last().endChar, overlapping = index == 3)
        }
        val blocks = transcriptBlocks(text.toString(), words, turns)
        assertEquals(text.toString(), blocks.joinToString("") { it.text })
        assertEquals(words, blocks.flatMap { it.words })
        blocks.forEach { block ->
            assertEquals(block.text, text.substring(block.start, block.end))
            block.turn?.let { turn -> assertTrue(block.start >= turn.startChar && block.end <= turn.endChar) }
            assertTrue(block.words.size <= 45)
        }
        assertTrue(blocks.any { it.turn?.overlapping == true })
        assertEquals(20, blocks.mapNotNull { it.turn?.speakerId }.distinct().size)
    }

    @Test fun untimedUnicodeAndUnassignedTextArePreserved() {
        val text = "  నమస్కారం\u2003世界\n".repeat(90)
        val blocks = transcriptBlocks(text)
        assertEquals(text, blocks.joinToString("") { it.text })
        assertTrue(blocks.all { it.turn == null && it.words.isEmpty() })
        assertTrue(transcriptBlocks(" \n ").isEmpty())
    }

    @Test fun playbackLookupLeavesSilenceUnhighlightedAndUsesHalfOpenIntervals() {
        val words = listOf(WordCue("one", 500, 900, 0, 0, 3), WordCue("two", 1_400, 1_800, 1, 4, 7))
        assertEquals(-1, activeWordIndex(words, 0))
        assertEquals(0, activeWordIndex(words, 500))
        assertEquals(0, activeWordIndex(words, 899))
        assertEquals(-1, activeWordIndex(words, 900))
        assertEquals(-1, activeWordIndex(words, 1_300))
        assertEquals(1, activeWordIndex(words, 1_400))
        assertEquals(-1, activeWordIndex(words, 1_800))
        assertEquals(-1, activeWordIndex(emptyList(), 0))
    }

    @Test fun playbackDoesNotScanAllWordsInALongMeeting() {
        val words = CountingList((0 until 7_200).map { index -> WordCue("word", index * 500L, (index + 1) * 500L, index.toLong(), index * 5, index * 5 + 4) })
        assertEquals(7_199, activeWordIndex(words, 3_599_800))
        assertTrue("A tick must use logarithmic lookup, not scan7,200 words: ${words.reads}", words.reads <= 15)
        val text = "word ".repeat(7_200)
        val blocks = transcriptBlocks(text, words)
        assertEquals(blocks.lastIndex, activeBlockIndex(blocks, words.last()))
        assertEquals(-1, activeBlockIndex(blocks, null))
    }

    @Test fun durationFormattingHandlesHourLongAndNegativeValues() {
        assertEquals("00:00", durationLabel(-1))
        assertEquals("59:59", durationLabel(3_599_000))
        assertEquals("1:00:00", durationLabel(3_600_000))
        assertEquals("12:03:04", durationLabel(43_384_000))
    }

    private class CountingList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }
}
