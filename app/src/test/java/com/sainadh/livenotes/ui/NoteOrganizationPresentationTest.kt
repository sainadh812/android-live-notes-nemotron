package com.sainadh.livenotes.ui

import com.sainadh.livenotes.data.NoteOrganization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteOrganizationPresentationTest {
    private val organization = NoteOrganization("recording:meeting", title = "Design review", isBookmarked = true,
        categoryId = "work", userSummary = "Gemini notes: send the prototype to Maya.")

    @Test fun searchCombinesWordsAcrossNameTranscriptCategoryAndManualSummary() {
        assertTrue(matchesNoteFilters("Design review", "We discussed accessibility", organization, "Product work",
            "DESIGN accessibility Maya work", true, "work"))
        assertFalse(matchesNoteFilters("Design review", "We discussed accessibility", organization, "Product work",
            "unknown", false, null))
    }

    @Test fun categoryAndBookmarkFiltersIncludeUnorganizedOlderNotesCorrectly() {
        assertTrue(matchesNoteFilters("Old recording", "Transcript", null, null, "", false, ""))
        assertFalse(matchesNoteFilters("Old recording", "Transcript", null, null, "", true, null))
        assertFalse(matchesNoteFilters("Design review", "Transcript", organization, "Product work", "", false, ""))
        assertFalse(matchesNoteFilters("Design review", "Transcript", organization, "Product work", "", false, "personal"))
    }

    @Test fun exportingANoteKeepsManualSummarySeparateAndDoesNotAlterTranscript() {
        assertEquals("Design review\n\nCategory: Work\n\nMy summary\n  Keep these notes.\n\n\nTranscript\nOriginal words.\n",
            organizedNoteText("Design review", "Work", "  Keep these notes.\n", "Transcript", "Original words.\n"))
    }

    @Test fun unnamedUncategorizedEmptyNotesCanStillExportTheirTitle() {
        assertEquals("September 30", organizedNoteText("September 30", null, "", "Automatic summary", ""))
    }
}
