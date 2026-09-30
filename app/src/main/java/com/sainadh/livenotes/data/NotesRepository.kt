package com.sainadh.livenotes.data

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import androidx.room.withTransaction
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class DailyNote(
    val dateKey: String,
    val summary: String,
    val runningContext: String,
    val actionItems: List<String>,
    val updatedAtEpochMs: Long
)

data class TranscriptChunk(
    val id: Long,
    val dateKey: String,
    val text: String,
    val isFinal: Boolean,
    val createdAtEpochMs: Long
)

class NotesRepository(
    private val database: NotesDatabase,
    private val json: Json = Json
) {
    private val dailyNoteDao = database.dailyNoteDao()
    private val transcriptChunkDao = database.transcriptChunkDao()
    private val segmentDao = database.transcriptSegmentDao()
    private val recordingDao = database.recordingDao()
    private val organizationDao = database.noteOrganizationDao()
    private val categoryDao = database.noteCategoryDao()
    fun observeToday(): Flow<DailyNote?> = dailyNoteDao.observeOne(todayKey()).map { it?.toModel(json) }

    fun observeAll(): Flow<List<DailyNote>> = dailyNoteDao.observeAll().map { list ->
        list.map { it.toModel(json) }
    }

    fun observeNoteOrganizations(): Flow<List<NoteOrganization>> = organizationDao.observeAll().map { rows ->
        rows.map { it.toModel() }
    }

    fun observeNoteCategories(): Flow<List<NoteCategory>> = categoryDao.observeAll().map { rows ->
        rows.map { NoteCategory(it.id, it.name) }
    }

    suspend fun saveNoteDetails(noteKey: String, title: String, categoryId: String?, userSummary: String) {
        validateNoteKey(noteKey)
        val cleanTitle = cleanNoteTitle(title)
        validateUserSummary(userSummary)
        database.withTransaction {
            requireNoteExists(noteKey)
            require(categoryId == null || categoryDao.get(categoryId) != null) {
                "This category no longer exists. Choose another category."
            }
            val existing = organizationDao.get(noteKey) ?: NoteOrganizationEntity(noteKey)
            organizationDao.upsert(existing.copy(
                title = cleanTitle, categoryId = categoryId, userSummary = userSummary,
                updatedAtEpochMs = System.currentTimeMillis()
            ))
        }
    }

    suspend fun setNoteBookmarked(noteKey: String, bookmarked: Boolean) {
        validateNoteKey(noteKey)
        database.withTransaction {
            requireNoteExists(noteKey)
            val existing = organizationDao.get(noteKey) ?: NoteOrganizationEntity(noteKey)
            organizationDao.upsert(existing.copy(
                isBookmarked = bookmarked, updatedAtEpochMs = System.currentTimeMillis()
            ))
        }
    }

    /** Called inside the annotation write transaction so a late UI write cannot undo deletion. */
    private suspend fun requireNoteExists(noteKey: String) {
        val exists = if (noteKey.startsWith("daily:")) {
            dailyNoteDao.getOne(noteKey.removePrefix("daily:")) != null
        } else {
            val recordingId = noteKey.removePrefix("recording:")
            if (recordingId.startsWith("legacy:")) transcriptChunkDao.dateExists(recordingId.removePrefix("legacy:"))
            else recordingDao.get(recordingId) != null || segmentDao.recordingExists(recordingId)
        }
        require(exists) { "This note no longer exists. Reopen your notes and try again." }
    }

    suspend fun createNoteCategory(name: String): NoteCategory {
        val cleanName = cleanCategoryName(name)
        val normalizedName = normalizedCategoryName(cleanName)
        return database.withTransaction {
            require(categoryDao.getByNormalizedName(normalizedName) == null) {
                "A category with this name already exists"
            }
            val category = NoteCategory(UUID.randomUUID().toString(), cleanName)
            categoryDao.insert(NoteCategoryEntity(category.id, category.name, normalizedName))
            category
        }
    }

    suspend fun renameNoteCategory(id: String, name: String) {
        val cleanName = cleanCategoryName(name)
        val normalizedName = normalizedCategoryName(cleanName)
        database.withTransaction {
            require(categoryDao.get(id) != null) { "This category no longer exists" }
            val duplicate = categoryDao.getByNormalizedName(normalizedName)
            require(duplicate == null || duplicate.id == id) { "A category with this name already exists" }
            categoryDao.rename(id, cleanName, normalizedName)
        }
    }

    /** SQLite clears assignments while retaining every note and its user-written text. */
    suspend fun deleteNoteCategory(id: String) = categoryDao.delete(id)

    /** Snapshot all tables at one database revision, rather than independently collected flows. */
    suspend fun exportSnapshot(): LibrarySnapshot = database.withTransaction {
        snapshotInTransaction().also { snapshot ->
            require(snapshot.recordings.none { it.audioStatus == RecordingAudioStatus.RECORDING }) {
                "Stop and save the current recording before creating a backup"
            }
        }
    }

    suspend fun planSnapshotImport(snapshot: LibrarySnapshot): LibraryImportPlan {
        validateLibrarySnapshot(snapshot)
        return database.withTransaction { planLibraryImport(snapshotInTransaction(), snapshot) }
    }

    suspend fun importSnapshot(snapshot: LibrarySnapshot): LibraryImportResult {
        validateLibrarySnapshot(snapshot)
        return database.withTransaction {
            val plan = planLibraryImport(snapshotInTransaction(), snapshot)
            val additions = plan.additions
            // ABORT inserts inside this transaction ensure unexpected conflicts roll back all tables.
            categoryDao.insertRestored(additions.categories)
            dailyNoteDao.insertRestored(additions.dailyNotes)
            recordingDao.insertRestored(additions.recordings)
            transcriptChunkDao.insertRestored(additions.chunks)
            segmentDao.insertRestored(additions.segments)
            organizationDao.insertRestored(additions.organizations)
            plan.result
        }
    }

    private suspend fun snapshotInTransaction() = LibrarySnapshot(
        dailyNotes = dailyNoteDao.getAll(), chunks = transcriptChunkDao.getAll(),
        segments = segmentDao.getAll(), recordings = recordingDao.getAll(),
        categories = categoryDao.getAll(), organizations = organizationDao.getAll()
    )

    suspend fun recordingAudioReferenceCount(fileName: String): Int = recordingDao.audioReferenceCount(fileName)

    suspend fun getRecordingMetadata(recordingId: String): RecordingEntity? = recordingDao.get(recordingId)

    suspend fun recordingExists(recordingId: String): Boolean = database.withTransaction {
        if (recordingId.startsWith("legacy:")) {
            val date = recordingId.removePrefix("legacy:")
            validateBackupDate(date)
            transcriptChunkDao.dateExists(date)
        } else {
            validateRecordingId(recordingId)
            recordingDao.get(recordingId) != null || segmentDao.recordingExists(recordingId)
        }
    }

    suspend fun deleteRecording(recordingId: String): RecordingDeletionResult = database.withTransaction {
        if (recordingId.startsWith("legacy:")) {
            val date = recordingId.removePrefix("legacy:")
            validateBackupDate(date)
            val chunks = transcriptChunkDao.deleteDate(date)
            val annotation = organizationDao.delete(recordingNoteKey(recordingId))
            RecordingDeletionResult(recordingId, null, chunks > 0 || annotation > 0, legacyChunksDeleted = chunks)
        } else {
            validateRecordingId(recordingId)
            val recording = recordingDao.get(recordingId)
            require(recording?.audioStatus != RecordingAudioStatus.RECORDING) { "Stop and save this recording before deleting it" }
            val segments = segmentDao.deleteRecording(recordingId)
            val row = recordingDao.delete(recordingId)
            val annotation = organizationDao.delete(recordingNoteKey(recordingId))
            RecordingDeletionResult(recordingId, recording?.audioFileName, row > 0 || segments > 0 || annotation > 0,
                segmentsDeleted = segments)
        }
    }

    fun observeSavedRecordings(): Flow<List<SavedRecording>> = combine(
        segmentDao.observeAll(), transcriptChunkDao.observeAll(), recordingDao.observeAll()
    ) { segments, legacy, recordings -> savedRecordings(segments, legacy, recordings) }.flowOn(Dispatchers.Default)

    /** Keep the existing archive snapshot during capture; do not reread history for every live word. */
    fun observeSavedRecordings(captureActive: Flow<Boolean>): Flow<List<SavedRecording>> =
        observeWhenIdle(captureActive) { observeSavedRecordings() }

    suspend fun beginRecording(recordingId: String, timestampMs: Long) {
        recordingDao.insert(RecordingEntity(
            recordingId = recordingId,
            dateKey = dateKey(timestampMs),
            title = "Recording",
            startedAtEpochMs = timestampMs,
            durationMs = 0L,
            audioFileName = null,
            audioStatus = RecordingAudioStatus.RECORDING,
            updatedAtEpochMs = timestampMs
        ))
    }

    suspend fun finishRecording(recordingId: String, audioFileName: String?, durationMs: Long) {
        require(audioFileName == null || (audioFileName.isNotBlank() &&
            audioFileName != "." && audioFileName != ".." &&
            '/' !in audioFileName && '\\' !in audioFileName)) { "Audio filename must be a relative basename" }
        recordingDao.finish(recordingId, audioFileName, durationMs.coerceAtLeast(0L),
            if (audioFileName == null) RecordingAudioStatus.UNAVAILABLE else RecordingAudioStatus.READY,
            System.currentTimeMillis())
    }

    suspend fun updateRecordingTitle(recordingId: String, title: String) {
        val cleanTitle = title.trim().take(120)
        require(cleanTitle.isNotEmpty()) { "Recording title cannot be blank" }
        recordingDao.updateTitle(recordingId, cleanTitle, System.currentTimeMillis())
    }

    /** Call recovery before capture starts so an active recorder's file is never finalized here. */
    suspend fun unfinishedRecordings(): List<RecordingEntity> = recordingDao.unfinished()

    suspend fun getNote(dateKey: String): DailyNote? = dailyNoteDao.getOne(dateKey)?.toModel(json)

    suspend fun saveTranscript(recordingId: String, update: TranscriptUpdate, timestampMs: Long): String =
        segmentDao.save(recordingId, update, dateKey(timestampMs), timestampMs)

    suspend fun pendingSegments(dateKey: String): List<TranscriptSegmentEntity> = segmentDao.pendingSummary(dateKey)

    suspend fun saveSummary(note: DailyNote, segments: List<TranscriptSegmentEntity>, legacyIds: List<Long>) {
        database.withTransaction {
            upsertNote(note)
            // Only acknowledge exactly the revisions included in this AI request.
            // A partial revised while the request was running stays pending.
            segments.forEach { segmentDao.markSummarized(it.recordingId, it.segmentId, it.revision) }
            if (legacyIds.isNotEmpty()) transcriptChunkDao.markSummarized(legacyIds)
        }
    }

    suspend fun recentTranscript(dateKey: String, limit: Int = 12): List<TranscriptChunk> {
        return transcriptChunkDao.recent(dateKey, limit)
            .asReversed()
            .map { it.toModel() }
    }

    suspend fun latestChunk(dateKey: String): TranscriptChunk? = transcriptChunkDao.latest(dateKey)?.toModel()

    suspend fun transcriptForSummary(dateKey: String, sinceEpochMs: Long): List<TranscriptChunk> =
        transcriptChunkDao.forSummary(dateKey, sinceEpochMs).map { it.toModel() }

    suspend fun upsertNote(note: DailyNote) {
        dailyNoteDao.upsert(
            DailyNoteEntity(
                dateKey = note.dateKey,
                summary = note.summary,
                runningContext = note.runningContext,
                actionItemsJson = json.encodeToString(ListSerializer(String.serializer()), note.actionItems),
                updatedAtEpochMs = note.updatedAtEpochMs
            )
        )
    }

    fun todayKey(zoneId: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zoneId).toString()

    fun dateKey(timestampMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate().toString()
}

private fun TranscriptChunkEntity.toModel(): TranscriptChunk = TranscriptChunk(
    id = id,
    dateKey = dateKey,
    text = text,
    isFinal = isFinal,
    createdAtEpochMs = createdAtEpochMs
)

private fun NoteOrganizationEntity.toModel(): NoteOrganization = NoteOrganization(
    noteKey = noteKey,
    title = title,
    isBookmarked = isBookmarked,
    categoryId = categoryId,
    userSummary = userSummary,
    updatedAtEpochMs = updatedAtEpochMs
)

private fun DailyNoteEntity.toModel(json: Json): DailyNote = DailyNote(
    dateKey = dateKey,
    summary = summary,
    runningContext = runningContext,
    actionItems = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), actionItemsJson)
    }.getOrDefault(emptyList()),
    updatedAtEpochMs = updatedAtEpochMs
)
