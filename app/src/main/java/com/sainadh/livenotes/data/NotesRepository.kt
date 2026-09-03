package com.sainadh.livenotes.data

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val dailyNoteDao: DailyNoteDao,
    private val transcriptChunkDao: TranscriptChunkDao,
    private val json: Json = Json
) {
    fun observeToday(): Flow<DailyNote?> = dailyNoteDao.observeOne(todayKey()).map { it?.toModel(json) }

    fun observeAll(): Flow<List<DailyNote>> = dailyNoteDao.observeAll().map { list ->
        list.map { it.toModel(json) }
    }

    suspend fun getNote(dateKey: String): DailyNote? = dailyNoteDao.getOne(dateKey)?.toModel(json)

    suspend fun appendTranscript(dateKey: String, text: String, isFinal: Boolean, timestampMs: Long) {
        transcriptChunkDao.insert(
            TranscriptChunkEntity(
                dateKey = dateKey,
                text = text,
                isFinal = isFinal,
                createdAtEpochMs = timestampMs
            )
        )
    }

    suspend fun recentTranscript(dateKey: String, limit: Int = 12): List<TranscriptChunk> {
        return transcriptChunkDao.recent(dateKey, limit)
            .asReversed()
            .map { it.toModel() }
    }

    suspend fun latestChunk(dateKey: String): TranscriptChunk? = transcriptChunkDao.latest(dateKey)?.toModel()

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
