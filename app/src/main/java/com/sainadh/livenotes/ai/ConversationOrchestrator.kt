package com.sainadh.livenotes.ai

import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class ConversationOrchestrator(
    private val repository: NotesRepository,
    private val apiKeyStore: ApiKeyStore,
    private val chatCompletionClient: ChatCompletionClient,
    summaryScope: CoroutineScope
) {
    private val scheduler = SummaryScheduler(summaryScope, summarize = ::summarize)
    val summaryError: StateFlow<String?> = scheduler.error

    fun retrySummary() = scheduler.retryFailed()

    /** Persist before returning; network work belongs to the application scope. */
    suspend fun onTranscript(recordingId: String, update: TranscriptUpdate, timestampMs: Long = System.currentTimeMillis()): Result<Unit> {
        return try {
            val dateKey = repository.saveTranscript(recordingId, update, timestampMs)
            scheduler.request(dateKey, update.endsUtterance)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun summarize(dateKey: String): Result<Unit> {
        val apiKey = apiKeyStore.readApiKey()
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("No API key configured"))
        val provider = apiKeyStore.readProvider()
        val existing = repository.getNote(dateKey)
        val recent = repository.transcriptForSummary(dateKey, existing?.updatedAtEpochMs ?: 0L)
        val segments = repository.pendingSegments(dateKey)
        val legacyWindow = recent.joinToString("\n") { "[legacy ${if (it.isFinal) "final" else "partial"}] ${it.text}" }
        val segmentWindow = formatTranscriptSegments(segments)
        val window = listOf(legacyWindow, segmentWindow).filter { it.isNotBlank() }.joinToString("\n")
        if (window.isBlank()) return Result.success(Unit)
        val result = chatCompletionClient.summarizeConversation(
            LlmSummaryRequest(
                provider = provider,
                apiKey = apiKey,
                model = apiKeyStore.readModel(provider),
                priorSummary = existing?.summary.orEmpty(),
                runningContext = existing?.runningContext.orEmpty(),
                recentTranscript = window
            )
        )
        val error = result.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) throw error
            return Result.failure(error)
        }
        val summary = result.getOrThrow()
        repository.saveSummary(
            DailyNote(
                dateKey = dateKey,
                summary = summary.summary,
                runningContext = summary.runningContext,
                actionItems = summary.actionItems,
                updatedAtEpochMs = maxOf(
                    existing?.updatedAtEpochMs ?: 0L,
                    recent.maxOfOrNull { it.createdAtEpochMs } ?: 0L,
                    segments.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
                )
            ), segments, recent.map { it.id }
        )
        return Result.success(Unit)
    }
}
