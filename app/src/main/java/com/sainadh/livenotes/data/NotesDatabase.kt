package com.sainadh.livenotes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_notes")
data class DailyNoteEntity(
    @PrimaryKey val dateKey: String,
    val summary: String,
    val runningContext: String,
    val actionItemsJson: String,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "transcript_chunks")
data class TranscriptChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val text: String,
    val isFinal: Boolean,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val summaryProcessed: Boolean = false
)

/** New recordings use stable segment identities. The v1 table remains untouched. */
@Entity(tableName = "transcript_segments", primaryKeys = ["recordingId", "segmentId"], indices = [Index("dateKey")])
data class TranscriptSegmentEntity(
    val recordingId: String,
    val segmentId: Long,
    val dateKey: String,
    val text: String,
    val status: String,
    val appendToPrevious: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val revision: Long,
    val summarizedRevision: Long
)

@Dao
interface TranscriptSegmentDao {
    @Query("SELECT * FROM transcript_segments ORDER BY createdAtEpochMs ASC, recordingId ASC, segmentId ASC")
    fun observeAll(): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId AND segmentId = :segmentId")
    suspend fun get(recordingId: String, segmentId: Long): TranscriptSegmentEntity?

    @Insert
    suspend fun insert(segment: TranscriptSegmentEntity)

    @Update
    suspend fun update(segment: TranscriptSegmentEntity)

    @Transaction
    suspend fun save(recordingId: String, update: TranscriptUpdate, dateKey: String, timestampMs: Long): String {
        val existing = get(recordingId, update.segmentId)
        if (existing == null) {
            if (update.text.isNotEmpty()) insert(TranscriptSegmentEntity(
                recordingId, update.segmentId, dateKey, update.text, update.status.name,
                update.appendToPrevious, timestampMs, timestampMs, 1L, 0L
            ))
        } else if (existing.status == TranscriptStatus.PARTIAL.name &&
            (existing.text != update.text || existing.status != update.status.name)) {
            // Final and interrupted segments cannot be rewritten by stale partials.
            this.update(existing.copy(
                text = update.text, status = update.status.name,
                updatedAtEpochMs = timestampMs, revision = existing.revision + 1L
            ))
        }
        // A segment revised after midnight still belongs to the day it started.
        return existing?.dateKey ?: dateKey
    }

    @Query("""
        SELECT * FROM transcript_segments
        WHERE dateKey = :dateKey AND revision > summarizedRevision
        ORDER BY createdAtEpochMs ASC, recordingId ASC, segmentId ASC
    """)
    suspend fun pendingSummary(dateKey: String): List<TranscriptSegmentEntity>

    @Query("""
        UPDATE transcript_segments SET summarizedRevision = :revision
        WHERE recordingId = :recordingId AND segmentId = :segmentId AND revision = :revision
    """)
    suspend fun markSummarized(recordingId: String, segmentId: Long, revision: Long)
}

@Dao
interface DailyNoteDao {
    @Query("SELECT * FROM daily_notes ORDER BY dateKey DESC")
    fun observeAll(): Flow<List<DailyNoteEntity>>

    @Query("SELECT * FROM daily_notes WHERE dateKey = :dateKey")
    fun observeOne(dateKey: String): Flow<DailyNoteEntity?>

    @Query("SELECT * FROM daily_notes WHERE dateKey = :dateKey")
    suspend fun getOne(dateKey: String): DailyNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: DailyNoteEntity)
}

@Dao
interface TranscriptChunkDao {
    @Query("SELECT * FROM transcript_chunks ORDER BY createdAtEpochMs ASC, id ASC")
    fun observeAll(): Flow<List<TranscriptChunkEntity>>

    @Insert
    suspend fun insert(chunk: TranscriptChunkEntity)

    @Query("SELECT * FROM transcript_chunks WHERE dateKey = :dateKey ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit")
    suspend fun recent(dateKey: String, limit: Int): List<TranscriptChunkEntity>

    @Query("SELECT * FROM transcript_chunks WHERE dateKey = :dateKey ORDER BY createdAtEpochMs DESC, id DESC LIMIT 1")
    suspend fun latest(dateKey: String): TranscriptChunkEntity?

    // Include the timestamp boundary because callbacks can share a millisecond.
    // Only the newest row may contribute a partial; all unsummarized finals survive.
    @Query("""
        SELECT * FROM transcript_chunks
        WHERE dateKey = :dateKey AND summaryProcessed = 0 AND createdAtEpochMs >= :sinceEpochMs
        AND (isFinal = 1 OR id = (
            SELECT id FROM transcript_chunks WHERE dateKey = :dateKey
            ORDER BY createdAtEpochMs DESC, id DESC LIMIT 1
        ))
        ORDER BY createdAtEpochMs ASC, id ASC
    """)
    suspend fun forSummary(dateKey: String, sinceEpochMs: Long): List<TranscriptChunkEntity>

    @Query("UPDATE transcript_chunks SET summaryProcessed = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)
}

@Database(
    entities = [DailyNoteEntity::class, TranscriptChunkEntity::class, TranscriptSegmentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun dailyNoteDao(): DailyNoteDao
    abstract fun transcriptChunkDao(): TranscriptChunkDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Never infer recording boundaries or discard historical partials.
                db.execSQL("ALTER TABLE transcript_chunks ADD COLUMN summaryProcessed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS transcript_segments (
                        recordingId TEXT NOT NULL, segmentId INTEGER NOT NULL,
                        dateKey TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
                        appendToPrevious INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL,
                        revision INTEGER NOT NULL, summarizedRevision INTEGER NOT NULL,
                        PRIMARY KEY(recordingId, segmentId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_segments_dateKey ON transcript_segments (dateKey)")
            }
        }

        fun build(context: Context): NotesDatabase {
            return Room.databaseBuilder(
                context,
                NotesDatabase::class.java,
                "live-notes.db"
            ).addMigrations(MIGRATION_1_2).build()
        }
    }
}
