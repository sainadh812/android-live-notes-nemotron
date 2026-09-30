package com.sainadh.livenotes.data

import org.junit.Assert.*
import org.junit.Test

class NoteOrganizationTest {
    @Test fun distinctStableKeysIncludeLegacyRecordingIdentity() {
        val recording = recordingNoteKey("legacy:2026-09-30")
        val daily = dailyNoteKey("2026-09-30")
        assertEquals("recording:legacy:2026-09-30", recording)
        assertEquals("daily:2026-09-30", daily)
        assertNotEquals(recording, daily)
        validateNoteKey(recording)
        validateNoteKey(daily)
        assertThrows(IllegalArgumentException::class.java) { validateNoteKey("recording:") }
        assertThrows(IllegalArgumentException::class.java) { validateNoteKey("other:key") }
    }

    @Test fun titlesCanResetToDefaultAndRejectOversizeInsteadOfTruncating() {
        assertEquals("A meeting", cleanNoteTitle("  A meeting  "))
        assertEquals("", cleanNoteTitle("   "))
        assertEquals(120, cleanNoteTitle("x".repeat(120)).length)
        assertThrows(IllegalArgumentException::class.java) { cleanNoteTitle("x".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { cleanNoteTitle("Line one\nLine two") }
    }

    @Test fun categoryNamesAreTrimmedAndNormalizedForCaseInsensitiveIdentity() {
        val cleaned = cleanCategoryName("  Research  ")
        assertEquals("Research", cleaned)
        assertEquals(normalizedCategoryName(cleaned), normalizedCategoryName("RESEARCH"))
        assertEquals(normalizedCategoryName(cleaned), normalizedCategoryName("ＲＥＳＥＡＲＣＨ"))
        assertEquals(normalizedCategoryName("Café"), normalizedCategoryName("Cafe\u0301"))
        assertEquals(60, cleanCategoryName("x".repeat(60)).length)
        assertThrows(IllegalArgumentException::class.java) { cleanCategoryName(" ") }
        assertThrows(IllegalArgumentException::class.java) { cleanCategoryName("x".repeat(61)) }
        assertThrows(IllegalArgumentException::class.java) { cleanCategoryName("A\nB") }
    }

    @Test fun pastedSummaryAcceptsFormattingAndEnforcesAnExplicitLimit() {
        validateUserSummary("")
        validateUserSummary("\n  Pasted summary 📝\n\n• Retain Unicode and formatting.\t")
        validateUserSummary("x".repeat(100_000))
        assertThrows(IllegalArgumentException::class.java) { validateUserSummary("x".repeat(100_001)) }
    }
}
