package com.sainadh.livenotes.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPersistenceTest {
    private fun withDatabase(test: suspend (NotesDatabase, NotesRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        try { runBlocking { test(database, NotesRepository(database)) } } finally { database.close() }
    }

    @Test fun fullSnapshotRoundTripAndRepeatedRestoreKeepEveryField() = withDatabase { _, repository ->
        val snapshot = fixture()
        val first = repository.importSnapshot(snapshot)
        assertEquals(1, first.recordingsAdded)
        assertEquals(3, first.organizationsAdded)
        assertSnapshotEquals(snapshot, repository.exportSnapshot())
        val repeated = repository.importSnapshot(snapshot)
        assertEquals(0, repeated.recordingsAdded)
        assertEquals(0, repeated.legacyChunksAdded)
        assertEquals(0, repeated.organizationsAdded)
        assertSnapshotEquals(snapshot, repository.exportSnapshot())
    }

    @Test fun mergeKeepsExistingRecordingsDailyNotesAndAnnotations() = withDatabase { _, repository ->
        val local = fixture()
        repository.importSnapshot(local)
        val incoming = local.copy(
            dailyNotes = local.dailyNotes.map { it.copy(summary = "Incoming replacement must not win") },
            recordings = local.recordings.map { it.copy(title = "Incoming replacement", audioFileName = "replacement.wav") },
            segments = local.segments.map { it.copy(text = "Incoming replacement") } +
                local.segments.single().copy(segmentId = 1, text = "Extra incoming words"),
            organizations = local.organizations.map { it.copy(title = "Incoming title", userSummary = "Incoming summary") }
        )
        val plan = repository.planSnapshotImport(incoming)
        assertEquals(1, plan.result.recordingsSkipped)
        assertEquals(2, plan.result.segmentsSkipped)
        repository.importSnapshot(incoming)
        assertSnapshotEquals(local, repository.exportSnapshot())
    }

    @Test fun categoryCollisionMapsAnnotationsWithoutOverwritingLocalCategory() = withDatabase { _, repository ->
        val existing = repository.createNoteCategory("WORK")
        repository.importSnapshot(fixture())
        val restored = repository.exportSnapshot()
        assertEquals(listOf(NoteCategoryEntity(existing.id, "WORK", "work")), restored.categories)
        assertTrue(restored.organizations.all { it.categoryId == existing.id })
    }

    @Test fun collidingLegacyChunkIdsNeverDropTextAndRemainIdempotent() = withDatabase { _, repository ->
        val local = TranscriptChunkEntity(1, "2026-09-28", "Current transcript", true, 10)
        repository.importSnapshot(LibrarySnapshot(chunks = listOf(local)))
        val incoming = LibrarySnapshot(chunks = listOf(TranscriptChunkEntity(1, "2026-09-29", "Backup transcript", true, 20)))
        repository.importSnapshot(incoming)
        val restored = repository.exportSnapshot()
        assertEquals(listOf("Current transcript", "Backup transcript"), restored.chunks.map { it.text })
        assertEquals(listOf(1L, 2L), restored.chunks.map { it.id })
        repository.importSnapshot(incoming)
        assertEquals(restored, repository.exportSnapshot())
    }

    @Test fun invalidInputDoesNotPartiallyImportAndSqlFailureRollsBackEveryTable() = withDatabase { database, repository ->
        val invalid = fixture().copy(organizations = listOf(NoteOrganizationEntity("recording:r1", categoryId = "missing")))
        expectFailure { repository.importSnapshot(invalid) }
        assertEquals(LibrarySnapshot(), repository.exportSnapshot())

        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_restore BEFORE INSERT ON note_organization
            BEGIN SELECT RAISE(ABORT, 'Simulated restore write failure'); END
        """.trimIndent())
        expectFailure { repository.importSnapshot(fixture()) }
        assertEquals(LibrarySnapshot(), repository.exportSnapshot())
    }

    @Test fun deletingRecordingKeepsIndependentDailyNoteCategoriesAndLegacyText() = withDatabase { _, repository ->
        val original = fixture()
        repository.importSnapshot(original)
        val result = repository.deleteRecording("r1")
        assertTrue(result.deleted)
        assertEquals("r1.wav", result.audioFileName)
        assertEquals(1, result.segmentsDeleted)
        assertFalse(repository.recordingExists("r1"))
        assertEquals(0, repository.recordingAudioReferenceCount("r1.wav"))
        val after = repository.exportSnapshot()
        assertTrue(after.recordings.isEmpty())
        assertTrue(after.segments.isEmpty())
        assertEquals(original.dailyNotes, after.dailyNotes)
        assertEquals(original.categories, after.categories)
        assertEquals(original.chunks, after.chunks)
        assertEquals(setOf("daily:2026-09-30", "recording:legacy:2026-09-29"), after.organizations.map { it.noteKey }.toSet())
        assertFalse(repository.deleteRecording("r1").deleted)
    }

    @Test fun deletingLegacyGroupKeepsOtherDatesAndNativeRecordings() = withDatabase { _, repository ->
        val original = fixture().let { it.copy(chunks = it.chunks + TranscriptChunkEntity(22, "2026-09-28", "Other day", true, 10)) }
        repository.importSnapshot(original)
        assertTrue(repository.recordingExists("legacy:2026-09-29"))
        val result = repository.deleteRecording("legacy:2026-09-29")
        assertEquals(1, result.legacyChunksDeleted)
        assertNull(result.audioFileName)
        assertFalse(repository.recordingExists("legacy:2026-09-29"))
        assertTrue(repository.recordingExists("legacy:2026-09-28"))
        val after = repository.exportSnapshot()
        assertEquals(listOf("Other day"), after.chunks.map { it.text })
        assertEquals(original.recordings, after.recordings)
        assertEquals(original.segments, after.segments)
        assertEquals(original.dailyNotes, after.dailyNotes)
        assertEquals(original.categories, after.categories)
        assertFalse(after.organizations.any { it.noteKey == "recording:legacy:2026-09-29" })
    }

    @Test fun activeRecordingCannotBeDeletedOrExportedAndSharedAudioReferenceSurvives() = withDatabase { database, repository ->
        repository.importSnapshot(fixture())
        repository.beginRecording("active", 1000)
        expectFailure { repository.deleteRecording("active") }
        assertEquals(RecordingAudioStatus.RECORDING, database.recordingDao().get("active")!!.audioStatus)
        expectFailure { repository.exportSnapshot() }

        repository.finishRecording("active", "r1.wav", 1000)
        assertEquals(2, repository.recordingAudioReferenceCount("r1.wav"))
        assertEquals("r1.wav", repository.deleteRecording("active").audioFileName)
        assertEquals(1, repository.recordingAudioReferenceCount("r1.wav"))
        assertTrue(repository.recordingExists("r1"))
    }

    @Test fun transcriptOnlyRecordingDeletionRemovesItsWordsAndAnnotation() = withDatabase { _, repository ->
        val source = fixture().copy(recordings = emptyList())
        repository.importSnapshot(source)
        assertTrue(repository.recordingExists("r1"))
        val result = repository.deleteRecording("r1")
        assertTrue(result.deleted)
        assertNull(result.audioFileName)
        assertFalse(repository.recordingExists("r1"))
        assertTrue(repository.exportSnapshot().organizations.none { it.noteKey == "recording:r1" })
    }

    private fun assertSnapshotEquals(expected: LibrarySnapshot, actual: LibrarySnapshot) {
        assertEquals(expected.copy(organizations = expected.organizations.sortedBy { it.noteKey }),
            actual.copy(organizations = actual.organizations.sortedBy { it.noteKey }))
    }

    private suspend fun expectFailure(action: suspend () -> Any?) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }

    private fun fixture() = LibrarySnapshot(
        dailyNotes = listOf(DailyNoteEntity("2026-09-30", "AI summary", "Prior context", "[\"Task\"]", 90)),
        chunks = listOf(TranscriptChunkEntity(17, "2026-09-29", "Old transcript", false, 20, true)),
        segments = listOf(TranscriptSegmentEntity("r1", 0, "2026-09-30", "Recorded words", "FINAL", false, 30, 40, 3, 2, 100, 2000)),
        recordings = listOf(RecordingEntity("r1", "2026-09-30", "Original name", 30, 2000, "r1.wav", RecordingAudioStatus.READY, 40)),
        categories = listOf(NoteCategoryEntity("work", "Work", "work")),
        organizations = listOf(
            NoteOrganizationEntity("daily:2026-09-30", "Daily title", true, "work", "Daily user summary", 50),
            NoteOrganizationEntity("recording:r1", "My recording", true, "work", "Pasted summary\n\nKeep every word.", 60),
            NoteOrganizationEntity("recording:legacy:2026-09-29", "Old recording", true, "work", "Legacy summary", 70)
        )
    )
}
