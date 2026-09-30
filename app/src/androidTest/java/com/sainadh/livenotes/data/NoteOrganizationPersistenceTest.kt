package com.sainadh.livenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteOrganizationPersistenceTest {
    private fun withRepository(test: suspend (NotesRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        try { runBlocking { test(NotesRepository(database)) } } finally { database.close() }
    }

    @Test fun aiSummaryUpdatesNeverOverwriteManualDetails() = withRepository { repository ->
        val date = "2026-09-30"
        val key = dailyNoteKey(date)
        val manualSummary = "  My summary from another app.\n\n• Keep this formatting.  "
        repository.upsertNote(DailyNote(date, "Initial AI text", "Initial context", emptyList(), 1))
        repository.saveNoteDetails(key, "My meeting", null, manualSummary)
        repository.setNoteBookmarked(key, true)
        val annotations = repository.observeNoteOrganizations().first().single()

        repository.saveSummary(DailyNote(date, "Updated AI text", "New context", listOf("AI task"), 2),
            emptyList(), emptyList())

        assertEquals(annotations, repository.observeNoteOrganizations().first().single())
        assertEquals(manualSummary, annotations.userSummary)
        assertEquals("Updated AI text", repository.getNote(date)!!.summary)
    }

    @Test fun categoryRenameRetainsAssignmentsAndDeleteRetainsNotes() = withRepository { repository ->
        val category = repository.createNoteCategory("  Projects  ")
        val recordingKey = recordingNoteKey("legacy:2026-09-01")
        val dailyKey = dailyNoteKey("2026-09-01")
        repository.saveNoteDetails(recordingKey, "Legacy recording", category.id, "Recording summary")
        repository.saveNoteDetails(dailyKey, "Daily summary", category.id, "Daily note")
        repository.setNoteBookmarked(recordingKey, true)

        repository.renameNoteCategory(category.id, "  RESEARCH  ")
        assertEquals(listOf(NoteCategory(category.id, "RESEARCH")), repository.observeNoteCategories().first())
        val beforeDelete = repository.observeNoteOrganizations().first()
        assertEquals(setOf(recordingKey, dailyKey), beforeDelete.map { it.noteKey }.toSet())
        assertTrue(beforeDelete.all { it.categoryId == category.id })

        repository.deleteNoteCategory(category.id)
        assertTrue(repository.observeNoteCategories().first().isEmpty())
        assertEquals(beforeDelete.map { it.copy(categoryId = null) }, repository.observeNoteOrganizations().first())
    }

    @Test fun rejectsBlankDuplicateAndMissingCategoriesWithoutChangingSavedData() = withRepository { repository ->
        val work = repository.createNoteCategory("Work")
        val personal = repository.createNoteCategory("Personal")
        expectRejected { repository.createNoteCategory("   ") }
        expectRejected { repository.createNoteCategory("  wOrK ") }
        expectRejected { repository.createNoteCategory("ＷＯＲＫ") }
        expectRejected { repository.renameNoteCategory(personal.id, "work") }
        expectRejected { repository.renameNoteCategory(work.id, "") }
        expectRejected { repository.renameNoteCategory("missing", "Unavailable") }
        // A casing-only rename is valid because it retains the category's identity.
        repository.renameNoteCategory(work.id, "WORK")
        assertEquals(setOf(NoteCategory(work.id, "WORK"), personal), repository.observeNoteCategories().first().toSet())

        val key = recordingNoteKey("meeting")
        repository.saveNoteDetails(key, "Saved title", personal.id, "Saved summary")
        val saved = repository.observeNoteOrganizations().first()
        expectRejected { repository.saveNoteDetails(key, "New title", "missing", "New summary") }
        expectRejected { repository.saveNoteDetails(key, "x".repeat(121), null, "New summary") }
        expectRejected { repository.saveNoteDetails(key, "New title", null, "x".repeat(100_001)) }
        assertEquals(saved, repository.observeNoteOrganizations().first())
    }

    @Test fun independentBookmarkAndDetailsWritesDoNotEraseEachOther() = withRepository { repository ->
        val key = recordingNoteKey("recording-one")
        repository.saveNoteDetails(key, "Original", null, "First summary")
        coroutineScope {
            listOf(
                async { repository.saveNoteDetails(key, "Updated", null, "Updated summary") },
                async { repository.setNoteBookmarked(key, true) }
            ).awaitAll()
        }
        val result = repository.observeNoteOrganizations().first().single()
        assertEquals("Updated", result.title)
        assertEquals("Updated summary", result.userSummary)
        assertTrue(result.isBookmarked)

        repository.setNoteBookmarked(key, false)
        repository.saveNoteDetails(key, "  ", null, "")
        val cleared = repository.observeNoteOrganizations().first().single()
        assertFalse(cleared.isBookmarked)
        assertEquals("", cleared.title)
        assertEquals("", cleared.userSummary)
    }

    @Test fun bookmarkedLegacyTextNeedsNoSyntheticRecordingRow() = withRepository { repository ->
        val key = recordingNoteKey("legacy:2026-09-01")
        repository.setNoteBookmarked(key, true)
        repository.saveNoteDetails(key, "Older notes", null, "User-written summary")
        val saved = repository.observeNoteOrganizations().first().single()
        assertEquals(key, saved.noteKey)
        assertTrue(saved.isBookmarked)
        assertTrue(repository.observeSavedRecordings().first().isEmpty())
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected invalid input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure; database remains unchanged.
        }
    }
}
