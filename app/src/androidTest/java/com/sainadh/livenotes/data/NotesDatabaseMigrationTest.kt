package com.sainadh.livenotes.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opening with Room after upgrade validates the complete generated v4 schema as well as data. */
@RunWith(AndroidJUnit4::class)
class NotesDatabaseMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withMigratedDatabase(
        fromVersion: Int,
        afterReopen: ((NotesDatabase) -> Unit)? = null,
        test: (NotesDatabase) -> Unit
    ) {
        val name = "recording-migration-v$fromVersion-${System.nanoTime()}.db"
        try {
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(fromVersion) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("""
                                CREATE TABLE daily_notes (dateKey TEXT NOT NULL PRIMARY KEY,
                                    summary TEXT NOT NULL, runningContext TEXT NOT NULL,
                                    actionItemsJson TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL)
                            """.trimIndent())
                            db.execSQL("""
                                CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    dateKey TEXT NOT NULL, text TEXT NOT NULL, isFinal INTEGER NOT NULL,
                                    createdAtEpochMs INTEGER NOT NULL${if (fromVersion >= 2) ", summaryProcessed INTEGER NOT NULL DEFAULT 0" else ""})
                            """.trimIndent())
                            db.execSQL("INSERT INTO daily_notes VALUES ('2026-09-01', 'Old summary', 'Context', '[]', 100)")
                            db.execSQL("INSERT INTO transcript_chunks (id,dateKey,text,isFinal,createdAtEpochMs) VALUES (1,'2026-09-01','Old partial',0,10)")
                            db.execSQL("INSERT INTO transcript_chunks (id,dateKey,text,isFinal,createdAtEpochMs) VALUES (2,'2026-09-01','Old final words',1,20)")
                            if (fromVersion >= 2) {
                                db.execSQL("""
                                    CREATE TABLE transcript_segments (
                                        recordingId TEXT NOT NULL, segmentId INTEGER NOT NULL,
                                        dateKey TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
                                        appendToPrevious INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL,
                                        updatedAtEpochMs INTEGER NOT NULL, revision INTEGER NOT NULL,
                                        summarizedRevision INTEGER NOT NULL
                                        ${if (fromVersion >= 3) ", startMs INTEGER, endMs INTEGER" else ""},
                                        PRIMARY KEY(recordingId,segmentId))
                                """.trimIndent())
                                db.execSQL("CREATE INDEX index_transcript_segments_dateKey ON transcript_segments (dateKey)")
                                db.execSQL("""
                                    INSERT INTO transcript_segments
                                        (recordingId,segmentId,dateKey,text,status,appendToPrevious,
                                            createdAtEpochMs,updatedAtEpochMs,revision,summarizedRevision)
                                    VALUES ('old-session',0,'2026-09-01','Preserved segment','FINAL',0,30,40,2,1)
                                """.trimIndent())
                            }
                            if (fromVersion >= 3) {
                                db.execSQL("""
                                    CREATE TABLE recordings (
                                        recordingId TEXT NOT NULL PRIMARY KEY, dateKey TEXT NOT NULL,
                                        title TEXT NOT NULL, startedAtEpochMs INTEGER NOT NULL,
                                        durationMs INTEGER NOT NULL, audioFileName TEXT,
                                        audioStatus TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL)
                                """.trimIndent())
                                db.execSQL("CREATE INDEX index_recordings_dateKey ON recordings (dateKey)")
                                db.execSQL("INSERT INTO recordings VALUES ('old-session','2026-09-01','Existing meeting title',30,54000,'meeting.wav','READY',60)")
                                db.execSQL("UPDATE transcript_segments SET startMs = 123, endMs = 4567")
                            }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }).build()
            ).use { it.writableDatabase }

            val database = Room.databaseBuilder(context, NotesDatabase::class.java, name)
                .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
            try {
                    // This opens the database and exercises Room's generated schema validation.
                    assertEquals(4, database.openHelper.writableDatabase.version)
                    test(database)
            } finally { database.close() }
            if (afterReopen != null) {
                val reopened = Room.databaseBuilder(context, NotesDatabase::class.java, name)
                    .addMigrations(NotesDatabase.MIGRATION_1_2, NotesDatabase.MIGRATION_2_3, NotesDatabase.MIGRATION_3_4)
                    .allowMainThreadQueries().build()
                try {
                    assertEquals(4, reopened.openHelper.writableDatabase.version)
                    afterReopen(reopened)
                } finally { reopened.close() }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun v1UpgradePreservesDailyNotesAndEveryHistoricalPartial() = withMigratedDatabase(1) { database ->
        runBlocking {
            assertEquals("Old summary", database.dailyNoteDao().getOne("2026-09-01")!!.summary)
            val legacy = database.transcriptChunkDao().observeAll().first()
            assertEquals(listOf("Old partial", "Old final words"), legacy.map { it.text })
            assertTrue(legacy.none { it.summaryProcessed })
            val saved = NotesRepository(database).observeSavedRecordings().first().single()
            assertEquals("Old partial\nOld final words", saved.text)
            assertNull(saved.audioFileName)
            assertTrue(saved.wordCues.isEmpty())
            val repository = NotesRepository(database)
            repository.saveNoteDetails(recordingNoteKey(saved.recordingId), "Archived meeting", null, "My old meeting summary")
            assertEquals("Archived meeting", repository.observeNoteOrganizations().first().single().title)
        }
    }

    @Test fun v2UpgradePreservesSegmentsAndSupportsAudioOnlySessions() = withMigratedDatabase(2) { database ->
        runBlocking {
            val previous = database.transcriptSegmentDao().get("old-session", 0)!!
            assertEquals("Preserved segment", previous.text)
            assertEquals(2L, previous.revision)
            assertEquals(1L, previous.summarizedRevision)
            assertNull(previous.startMs)
            assertNull(previous.endMs)
            assertEquals(2, database.transcriptChunkDao().observeAll().first().size)

            val repository = NotesRepository(database)
            repository.beginRecording("audio-only", 1_000)
            repository.finishRecording("audio-only", "audio-only.wav", 5_000)
            val recordings = repository.observeSavedRecordings().first()
            assertEquals(3, recordings.size)
            val audio = recordings.single { it.recordingId == "audio-only" }
            assertEquals("", audio.text)
            assertEquals("audio-only.wav", audio.audioFileName)
            assertEquals(5_000L, audio.durationMs)
            assertEquals(RecordingAudioStatus.READY, audio.audioStatus)

            // Re-delivery of a start request cannot erase an already finalized recording.
            repository.beginRecording("audio-only", 9_000)
            assertEquals(RecordingAudioStatus.READY, database.recordingDao().get("audio-only")!!.audioStatus)
        }
    }

    @Test fun v3UpgradeAndReopenPreserveRecordingsAndUserAnnotations() = withMigratedDatabase(
        3,
        afterReopen = { database ->
            runBlocking {
                assertPreviousV3Data(database)
                val repository = NotesRepository(database)
                val category = repository.observeNoteCategories().first().single()
                assertEquals("Research", category.name)
                val organization = repository.observeNoteOrganizations().first().single()
                assertEquals(recordingNoteKey("old-session"), organization.noteKey)
                assertEquals("Review meeting", organization.title)
                assertTrue(organization.isBookmarked)
                assertEquals(category.id, organization.categoryId)
                assertEquals("Summary pasted by the user.\n\nNext steps stay here.", organization.userSummary)
            }
        }
    ) { database ->
        runBlocking {
            assertPreviousV3Data(database)
            val repository = NotesRepository(database)
            assertTrue(repository.observeNoteOrganizations().first().isEmpty())
            assertTrue(repository.observeNoteCategories().first().isEmpty())
            val category = repository.createNoteCategory("Research")
            repository.setNoteBookmarked(recordingNoteKey("old-session"), true)
            repository.saveNoteDetails(recordingNoteKey("old-session"), "Review meeting", category.id,
                "Summary pasted by the user.\n\nNext steps stay here.")
        }
    }

    private suspend fun assertPreviousV3Data(database: NotesDatabase) {
        assertEquals(DailyNoteEntity("2026-09-01", "Old summary", "Context", "[]", 100),
            database.dailyNoteDao().getOne("2026-09-01"))
        assertEquals(listOf("Old partial", "Old final words"),
            database.transcriptChunkDao().observeAll().first().map { it.text })
        assertEquals(RecordingEntity("old-session", "2026-09-01", "Existing meeting title", 30,
            54_000, "meeting.wav", RecordingAudioStatus.READY, 60), database.recordingDao().get("old-session"))
        assertEquals(TranscriptSegmentEntity("old-session", 0, "2026-09-01", "Preserved segment",
            "FINAL", false, 30, 40, 2, 1, 123, 4567), database.transcriptSegmentDao().get("old-session", 0))
        val recording = NotesRepository(database).observeSavedRecordings().first().single { it.recordingId == "old-session" }
        assertEquals("Existing meeting title", recording.title)
        assertEquals("meeting.wav", recording.audioFileName)
        assertEquals("Preserved segment", recording.text)
        assertFalse(recording.wordCues.isEmpty())
    }
}
