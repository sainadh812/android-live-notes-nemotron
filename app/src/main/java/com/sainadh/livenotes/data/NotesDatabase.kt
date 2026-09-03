package com.sainadh.livenotes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val createdAtEpochMs: Long
)

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
    @Insert
    suspend fun insert(chunk: TranscriptChunkEntity)

    @Query("SELECT * FROM transcript_chunks WHERE dateKey = :dateKey ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recent(dateKey: String, limit: Int): List<TranscriptChunkEntity>

    @Query("SELECT * FROM transcript_chunks WHERE dateKey = :dateKey ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latest(dateKey: String): TranscriptChunkEntity?
}

@Database(
    entities = [DailyNoteEntity::class, TranscriptChunkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun dailyNoteDao(): DailyNoteDao
    abstract fun transcriptChunkDao(): TranscriptChunkDao

    companion object {
        fun build(context: Context): NotesDatabase {
            return Room.databaseBuilder(
                context,
                NotesDatabase::class.java,
                "live-notes.db"
            ).fallbackToDestructiveMigration().build()
        }
    }
}
