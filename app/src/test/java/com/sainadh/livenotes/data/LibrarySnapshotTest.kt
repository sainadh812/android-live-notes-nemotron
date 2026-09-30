package com.sainadh.livenotes.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LibrarySnapshotTest {
    @Test fun serializationAndFreshImportPreserveEveryStoredField() {
        val snapshot = fixture()
        assertEquals(snapshot, Json.decodeFromString<LibrarySnapshot>(Json.encodeToString(snapshot)))
        val plan = planLibraryImport(LibrarySnapshot(), snapshot)
        assertEquals(snapshot, plan.additions)
        assertEquals(setOf("r1"), plan.result.importedRecordingIds)
        assertEquals(3, plan.result.organizationsAdded)
        assertEquals(LibrarySnapshot(), planLibraryImport(snapshot, snapshot).additions)
    }

    @Test fun existingRecordingIsKeptAsAWholeEvenWhenBackupHasAdditionalSegmentsOrAnnotations() {
        val incoming = fixture()
        val current = incoming.copy(
            recordings = incoming.recordings.map { it.copy(title = "Local title", durationMs = 9000) },
            segments = incoming.segments.map { it.copy(text = "Current local words") },
            organizations = emptyList()
        )
        val extra = incoming.segments.single().copy(segmentId = 2, text = "Backup-only continuation")
        val plan = planLibraryImport(current, incoming.copy(segments = incoming.segments + extra))
        assertTrue(plan.additions.recordings.isEmpty())
        assertTrue(plan.additions.segments.isEmpty())
        assertTrue(plan.additions.organizations.isEmpty())
        assertEquals(1, plan.result.recordingsSkipped)
        assertEquals(2, plan.result.segmentsSkipped)
    }

    @Test fun transcriptOnlyRecordingIdentityAlsoPreventsAConflictingMerge() {
        val incoming = fixture()
        val current = LibrarySnapshot(segments = listOf(incoming.segments.single().copy(text = "Keep this")))
        val plan = planLibraryImport(current, incoming)
        assertTrue(plan.additions.recordings.isEmpty())
        assertTrue(plan.additions.segments.isEmpty())
        assertFalse(plan.additions.organizations.any { it.noteKey == recordingNoteKey("r1") })
    }

    @Test fun categoryNamesReuseExistingIdsAndConflictingIdsGetNewStableIds() {
        val incoming = fixture()
        val existingByName = LibrarySnapshot(categories = listOf(NoteCategoryEntity("local", "WORK", "work")))
        val reused = planLibraryImport(existingByName, incoming)
        assertTrue(reused.additions.categories.isEmpty())
        assertTrue(reused.additions.organizations.all { it.categoryId == "local" })

        val existingById = LibrarySnapshot(categories = listOf(NoteCategoryEntity("c1", "Personal", "personal")))
        val remapped = planLibraryImport(existingById, incoming)
        val newCategory = remapped.additions.categories.single()
        assertNotEquals("c1", newCategory.id)
        assertEquals("Work", newCategory.name)
        assertTrue(remapped.additions.organizations.all { it.categoryId == newCategory.id })
        assertEquals(remapped, planLibraryImport(existingById, incoming))
    }

    @Test fun legacyIdCollisionPreservesDifferentTextAndRepeatedEqualRowsWithoutDuplication() {
        val incoming = fixture().copy(chunks = listOf(
            TranscriptChunkEntity(1, "2026-09-29", "Same words", true, 30),
            TranscriptChunkEntity(2, "2026-09-29", "Same words", true, 30)
        ))
        val current = LibrarySnapshot(chunks = listOf(
            TranscriptChunkEntity(1, "2026-09-29", "Different local words", true, 10),
            TranscriptChunkEntity(4, "2026-09-29", "Same words", true, 30, summaryProcessed = true)
        ))
        val first = planLibraryImport(current, incoming)
        assertEquals(1, first.additions.chunks.size)
        assertEquals("Same words", first.additions.chunks.single().text)
        assertTrue(first.additions.organizations.none { it.noteKey == recordingNoteKey("legacy:2026-09-29") })
        val combined = first.additions.copy(chunks = current.chunks + first.additions.chunks)
        assertTrue(planLibraryImport(combined, incoming).additions.chunks.isEmpty())
    }

    @Test fun collidingLegacyIdsAreRemappedAndSkippedOnRepeat() {
        val incoming = LibrarySnapshot(chunks = listOf(TranscriptChunkEntity(1, "2026-09-29", "Imported", true, 10)))
        val local = TranscriptChunkEntity(1, "2026-09-30", "Local", true, 10)
        val first = planLibraryImport(LibrarySnapshot(chunks = listOf(local)), incoming)
        assertEquals(2L, first.additions.chunks.single().id)
        val merged = LibrarySnapshot(chunks = listOf(local) + first.additions.chunks)
        assertTrue(planLibraryImport(merged, incoming).additions.chunks.isEmpty())
    }

    @Test fun invalidVersionsDuplicatesReferencesAndTraversalAreRejected() {
        val source = fixture()
        listOf(
            source.copy(schemaVersion = 99),
            source.copy(recordings = source.recordings + source.recordings),
            source.copy(chunks = source.chunks + source.chunks),
            source.copy(segments = source.segments + source.segments),
            source.copy(categories = emptyList()),
            source.copy(recordings = source.recordings.map { it.copy(audioFileName = "../secret.wav") }),
            source.copy(recordings = source.recordings.map { it.copy(recordingId = "../invalid") }),
            source.copy(recordings = source.recordings.map { it.copy(audioStatus = RecordingAudioStatus.RECORDING) }),
            source.copy(organizations = source.organizations + NoteOrganizationEntity("recording:missing")),
            source.copy(segments = source.segments.map { it.copy(startMs = 5000, endMs = 1000) })
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { planLibraryImport(LibrarySnapshot(), invalid) }
        }
        listOf("/absolute.wav", "..", "a\\b.wav", "C:audio.wav", "audio\u0000.wav").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { validateBackupAudioFileName(bad) }
        }
    }

    @Test fun sharedAudioReferencesRemainValidForVerifiedArchiveFiles() {
        val source = fixture()
        val second = source.recordings.single().copy(recordingId = "r2")
        validateLibrarySnapshot(source.copy(recordings = source.recordings + second))
    }

    private fun fixture() = LibrarySnapshot(
        dailyNotes = listOf(DailyNoteEntity("2026-09-30", "Generated summary", "Context", "[\"Follow up\"]", 80)),
        chunks = listOf(TranscriptChunkEntity(17, "2026-09-29", "Legacy text", false, 20, true)),
        segments = listOf(TranscriptSegmentEntity("r1", 0, "2026-09-30", "Recorded words", "FINAL", false, 30, 40, 3, 2, 100, 2000)),
        recordings = listOf(RecordingEntity("r1", "2026-09-30", "Original title", 30, 2000, "r1.wav", RecordingAudioStatus.READY, 40)),
        categories = listOf(NoteCategoryEntity("c1", "Work", "work")),
        organizations = listOf(
            NoteOrganizationEntity("daily:2026-09-30", "Daily title", true, "c1", "Pasted summary\n\nKeep whitespace.", 90),
            NoteOrganizationEntity("recording:r1", "Meeting title", true, "c1", "Recording summary", 91),
            NoteOrganizationEntity("recording:legacy:2026-09-29", "Old title", true, "c1", "Old summary", 92)
        )
    )
}
