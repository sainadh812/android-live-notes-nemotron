package com.sainadh.livenotes.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class RecordingFileDeletionTest {
    private class Fixture {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(parent.cacheDir, "deletion-test-${UUID.randomUUID()}").apply { mkdirs() }
        val audioRoot = File(root, "files/recordings").apply { mkdirs() }
        val journalRoot = File(root, "no-backup/recording-deletions").apply { mkdirs() }
        val context = object : ContextWrapper(parent) {
            override fun getFilesDir(): File = File(root, "files")
            override fun getNoBackupFilesDir(): File = File(root, "no-backup")
        }
        @Volatile var onQuery: ((String) -> Unit)? = null
        val database = Room.inMemoryDatabaseBuilder(parent, NotesDatabase::class.java)
            .setQueryCallback({ sql, _ -> onQuery?.invoke(sql) }, Executor { it.run() }).build()
        val repository = NotesRepository(database)
        val deletion = RecordingFileDeletion(context, repository)

        suspend fun seed(id: String, name: String? = "$id.wav") {
            repository.beginRecording(id, 1000)
            repository.finishRecording(id, name, 2000)
            name?.let { File(audioRoot, it).writeText("keep audio bytes") }
        }

        fun card(id: String, fileName: String? = "$id.wav") = SavedRecording(
            recordingId = id, dateKey = "1970-01-01", text = "", updatedAtEpochMs = 1000,
            hasUnconfirmedWords = false, audioFileName = fileName, audioStatus = RecordingAudioStatus.READY
        )

        fun journal(id: String, filename: String?, suffix: String = ".json"): File =
            File(journalRoot, "${UUID.randomUUID()}$suffix").apply {
                writeText("""{"recordingId":"$id","audioFileName":${filename?.let { "\"$it\"" } ?: "null"}}""")
            }

        fun close() { database.close(); root.deleteRecursively() }
    }

    private fun withFixture(test: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { runBlocking { test(fixture) } } finally { fixture.close() }
    }

    @Test fun deletesCurrentDatabaseAudioAndSidecarsInsteadOfStaleCardPath() = withFixture { fixture ->
        fixture.seed("meeting", "current.wav")
        listOf("current.wav.words", "current.wav.part", "current.wav.words.part", "stale.wav").forEach {
            File(fixture.audioRoot, it).writeText("file")
        }
        assertTrue(fixture.deletion.delete(fixture.card("meeting", "stale.wav")))
        assertFalse(fixture.repository.recordingExists("meeting"))
        assertEquals(listOf("stale.wav"), fixture.audioRoot.listFiles()!!.map { it.name })
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun activeDatabaseRecordingWinsOverStaleReadyCard() = withFixture { fixture ->
        fixture.repository.beginRecording("active", 1000)
        val audio = File(fixture.audioRoot, "active.wav").apply { writeText("active") }
        expectFailure { fixture.deletion.delete(fixture.card("active")) }
        assertTrue(audio.exists())
        assertTrue(fixture.repository.recordingExists("active"))
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun databaseFailurePreservesAudioAndRecoveryDiscardsIntentWhileRecordingExists() = withFixture { fixture ->
        fixture.seed("meeting")
        fixture.database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_recording_delete BEFORE DELETE ON recordings
            BEGIN SELECT RAISE(ABORT, 'Simulated database failure'); END
        """.trimIndent())
        expectFailure { fixture.deletion.delete(fixture.card("meeting")) }
        assertTrue(fixture.repository.recordingExists("meeting"))
        assertEquals("keep audio bytes", File(fixture.audioRoot, "meeting.wav").readText())
        assertTrue(fixture.journalRoot.listFiles()!!.isNotEmpty())
        fixture.deletion.recover()
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
        assertTrue(File(fixture.audioRoot, "meeting.wav").exists())
    }

    @Test fun backupOnlyAtomicJournalRecoversCommittedDeletion() = withFixture { fixture ->
        val audio = File(fixture.audioRoot, "removed.wav").apply { writeText("orphan after committed delete") }
        val timing = File(fixture.audioRoot, "removed.wav.words").apply { writeText("timings") }
        val backup = fixture.journal("removed", "removed.wav", ".json.bak")
        assertFalse(File(backup.path.removeSuffix(".bak")).exists())
        fixture.deletion.recover()
        assertFalse(audio.exists())
        assertFalse(timing.exists())
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun recoveryProtectsSharedAudioWhileCleaningOtherJournals() = withFixture { fixture ->
        fixture.seed("owner", "shared.wav")
        fixture.journal("already-deleted", "shared.wav")
        fixture.journal("another-deleted", "orphan.wav")
        File(fixture.audioRoot, "orphan.wav").writeText("orphan")
        fixture.deletion.recover()
        assertTrue(File(fixture.audioRoot, "shared.wav").exists())
        assertFalse(File(fixture.audioRoot, "orphan.wav").exists())
        assertTrue(fixture.repository.recordingExists("owner"))
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun corruptAndUncommittedJournalsNeverAuthorizeFileDeletionOrBlockOthers() = withFixture { fixture ->
        val preserved = File(fixture.audioRoot, "keep.wav").apply { writeText("keep") }
        File(fixture.journalRoot, "malformed.json").writeText("not JSON")
        fixture.journal("../invalid", "keep.wav")
        fixture.journal("unfinished", "keep.wav", ".json.new")
        val deleted = File(fixture.audioRoot, "delete.wav").apply { writeText("delete") }
        fixture.journal("committed", "delete.wav")
        fixture.deletion.recover()
        assertTrue(preserved.exists())
        assertFalse(deleted.exists())
    }

    @Test fun cleanupFailureRetainsJournalAndStartupRetriesAfterObstructionIsRemoved() = withFixture { fixture ->
        fixture.seed("meeting")
        val obstruction = File(fixture.audioRoot, "meeting.wav.words").apply { mkdir() }
        val child = File(obstruction, "not-a-timing-file").apply { writeText("obstruction") }
        assertFalse(fixture.deletion.delete(fixture.card("meeting")))
        assertFalse(fixture.repository.recordingExists("meeting"))
        assertFalse(File(fixture.audioRoot, "meeting.wav").exists())
        assertTrue(fixture.journalRoot.listFiles()!!.isNotEmpty())
        child.delete()
        fixture.deletion.recover()
        assertFalse(obstruction.exists())
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    @Test fun staleMissingRecordingCannotDeleteUnownedCallerFilename() = withFixture { fixture ->
        val unrelated = File(fixture.audioRoot, "unrelated.wav").apply { writeText("not owned by this recording") }
        assertTrue(fixture.deletion.delete(fixture.card("missing", "unrelated.wav")))
        assertTrue(unrelated.exists())
    }

    @Test fun cancellationDuringDatabaseDeleteCompletesCleanupAfterDurableIntent() = withFixture { fixture ->
        fixture.seed("meeting")
        coroutineScope {
            lateinit var deletionJob: Job
            deletionJob = launch(start = CoroutineStart.LAZY) { fixture.deletion.delete(fixture.card("meeting")) }
            fixture.onQuery = { sql ->
                if (sql.startsWith("DELETE FROM recordings", ignoreCase = true)) deletionJob.cancel()
            }
            deletionJob.start()
            deletionJob.join()
            fixture.onQuery = null
            assertTrue(deletionJob.isCancelled)
        }
        assertFalse(fixture.repository.recordingExists("meeting"))
        assertFalse(File(fixture.audioRoot, "meeting.wav").exists())
        assertTrue(fixture.journalRoot.listFiles()!!.isEmpty())
    }

    private suspend fun expectFailure(action: suspend () -> Any?) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Expected operation to fail", failed)
    }
}
