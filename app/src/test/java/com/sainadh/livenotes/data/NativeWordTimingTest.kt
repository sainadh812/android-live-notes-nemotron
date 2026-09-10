package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.NativeWordTimingFile
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NativeWordTimingTest {
    @Test fun decoderPointTimestampsHighlightUntilNextWord() {
        val words = listOf(NativeWordTimingFile.Word("one", 100, 100), NativeWordTimingFile.Word("two", 400, 400))
        val cues = alignedWordCues("one two", words, 800)
        assertEquals(listOf(100L, 400L), cues.map { it.startMs })
        assertEquals(listOf(400L, 800L), cues.map { it.endMs })
        assertTrue(cues.none { it.isEstimated })
    }

    @Test fun nativeTimingsPreserveUnicodeAndRequireMatchingTranscript() {
        val words = NativeWordTimingFile.parse("250\t500\t48656c6c6f\n600\t850\tf09f8c8d\n")
        val cues = alignedWordCues("Hello 🌍", words, 1000)
        assertEquals(listOf("Hello", "🌍"), cues.map { it.text })
        assertEquals(listOf(250L, 600L), cues.map { it.startMs })
        assertTrue(cues.none { it.isEstimated })
        assertEquals(6, cues[1].startChar)
        assertEquals(8, cues[1].endChar)
        assertTrue(alignedWordCues("Hello brave 🌍", words, 1000).isEmpty())
        assertTrue(alignedWordCues("Hello 🌍 extra", words, 1000).isEmpty())
        assertTrue(alignedWordCues("Hello 🌍", words, 300).isEmpty())
    }

    @Test fun atomicSidecarRoundTripAndMalformedDataFallback() {
        val directory = Files.createTempDirectory("word-timing-test").toFile()
        try {
            val audio = java.io.File(directory, "recording.wav")
            assertTrue(NativeWordTimingFile.read(audio).isEmpty())
            NativeWordTimingFile.write(audio, "100\t500\t6869\n")
            assertEquals(listOf(NativeWordTimingFile.Word("hi", 100, 500)), NativeWordTimingFile.read(audio))
            java.io.File(directory, "recording.wav.words").writeText("not a word record")
            assertTrue(NativeWordTimingFile.read(audio).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidIntervalsAndInvalidUtf8AreRejected() {
        listOf("500\t100\t6869", "-1\t20\t6869", "1\t20\tff", "1\t20\txx", "1\t20\t6").forEach { encoded ->
            try { NativeWordTimingFile.parse(encoded); fail("Accepted $encoded") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.nio.charset.CharacterCodingException) { }
        }
    }
}
