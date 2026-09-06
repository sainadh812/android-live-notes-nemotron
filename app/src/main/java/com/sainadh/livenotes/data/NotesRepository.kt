package com.sainadh.livenotes.data

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
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
    fun observeToday(): Flow<DailyNote?> = dailyNoteDao.observeOne(todayKey()).map { it?.toModel(json) }

    fun observeAll(): Flow<List<DailyNote>> = dailyNoteDao.observeAll().map { list ->
        list.map { it.toModel(json) }
    }

    fun observeSavedRecordings(): Flow<List<SavedRecording>> = combine(
        segmentDao.observeAll(), transcriptChunkDao.observeAll()
    ) { segments, legacy -> savedRecordings(segments, legacy) }.flowOn(Dispatchers.Default)

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

private fun DailyNoteEntity.toModel(json: Json): DailyNote = DailyNote(
    dateKey = dateKey,
    summary = summary,
    runningContext = runningContext,
    actionItems = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), actionItemsJson)
    }.getOrDefault(emptyList()),
    updatedAtEpochMs = updatedAtEpochMs
)
