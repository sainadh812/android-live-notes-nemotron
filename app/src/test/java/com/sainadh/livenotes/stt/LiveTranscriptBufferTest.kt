package com.sainadh.livenotes.stt

import org.junit.Assert.*
import org.junit.Test

class LiveTranscriptBufferTest {
    @Test fun hourOfUpdatesKeepsDisplayBoundedAndFullTranscriptExact() {
        val buffer = LiveTranscriptBuffer()
        val expected = StringBuilder()
        var last: LiveTranscriptBuffer.Preview? = null
        // One hour at the native half-second feed cadence, including tentative revisions.
        repeat(7_200) { index ->
            val text = "Speaker word $index café 世界. "
            val start = index * 500L
            buffer.update(TranscriptUpdate(index.toLong(), "Speaker wo", TranscriptStatus.PARTIAL,
                appendToPrevious = true, startMs = start, endMs = start + 250))
            val preview = requireNotNull(buffer.update(TranscriptUpdate(index.toLong(), text, TranscriptStatus.FINAL,
                appendToPrevious = true, startMs = start, endMs = start + 500)))
            assertTrue(preview.text.length <= 4_000)
            assertTrue(preview.segments.size <= 120)
            assertTrue(preview.text.endsWith(text))
            expected.append(text)
            last = preview
        }
        assertTrue(requireNotNull(last).hasEarlierText)
        assertFalse(last!!.text.contains("word 0 "))
        assertEquals(expected.toString(), buffer.fullText())
    }

    @Test fun revisionsEmptyTailsStaleFinalsAndOutOfOrderSegmentsPreserveText() {
        val buffer = LiveTranscriptBuffer(40, 3)
        buffer.update(TranscriptUpdate(0, "tentative", TranscriptStatus.PARTIAL))
        buffer.update(TranscriptUpdate(0, "", TranscriptStatus.PARTIAL))
        assertEquals("", buffer.fullText())
        buffer.update(TranscriptUpdate(0, "hello ", TranscriptStatus.FINAL))
        assertNull(buffer.update(TranscriptUpdate(0, "stale", TranscriptStatus.PARTIAL)))
        buffer.update(TranscriptUpdate(2, "second person", TranscriptStatus.INTERRUPTED))
        buffer.update(TranscriptUpdate(1, "world", TranscriptStatus.FINAL, appendToPrevious = true))
        assertEquals("hello world\nsecond person", buffer.fullText())
        assertNull(buffer.update(TranscriptUpdate(1, "world", TranscriptStatus.FINAL, appendToPrevious = true)))
    }

    @Test fun veryLongRevisableSegmentIsBoundedOnlyInDisplay() {
        val buffer = LiveTranscriptBuffer(100, 5)
        val longText = "original ".repeat(10_000)
        val partial = TranscriptUpdate(0, longText, TranscriptStatus.PARTIAL, startMs = 0, endMs = 900_000)
        val preview = requireNotNull(buffer.update(partial))
        assertTrue(preview.text.length <= 100 && preview.hasEarlierText)
        assertNull(preview.segments.single().startMs) // No fabricated times after cropping a segment.
        val corrected = "Corrected beginning. " + longText
        buffer.update(partial.copy(text = corrected, status = TranscriptStatus.FINAL))
        assertEquals(corrected, buffer.fullText())
    }

    @Test fun previouslyPublishedPreviewCannotChangeAfterMoreAudio() {
        val buffer = LiveTranscriptBuffer(100, 2)
        val first = requireNotNull(buffer.update(TranscriptUpdate(0, "first", TranscriptStatus.FINAL)))
        repeat(20) { buffer.update(TranscriptUpdate(it + 1L, "next $it", TranscriptStatus.FINAL)) }
        assertEquals("first", first.text)
        assertEquals(listOf("first"), first.segments.map { it.text })
        assertFalse(first.hasEarlierText)
    }

    @Test fun croppingDoesNotSplitUnicodeSurrogatePair() {
        val buffer = LiveTranscriptBuffer(5, 2)
        val preview = requireNotNull(buffer.update(TranscriptUpdate(0, "prefix 😀end", TranscriptStatus.FINAL)))
        assertEquals("end", preview.text)
        assertEquals("prefix 😀end", buffer.fullText())
    }
}
