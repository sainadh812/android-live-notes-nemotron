package com.sainadh.livenotes.ai

import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.NotesRepository
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
    suspend fun onTranscript(text: String, isFinal: Boolean, timestampMs: Long = System.currentTimeMillis()): Result<Unit> {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return Result.success(Unit)
        return try {
            val dateKey = repository.dateKey(timestampMs)
            repository.appendTranscript(dateKey, cleaned, isFinal, timestampMs)
            scheduler.request(dateKey, isFinal)
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
        val window = recent.joinToString("\n") { "[${if (it.isFinal) "final" else "partial"}] ${it.text}" }
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
        repository.upsertNote(
            DailyNote(
                dateKey = dateKey,
                summary = summary.summary,
                runningContext = summary.runningContext,
                actionItems = summary.actionItems,
                updatedAtEpochMs = recent.last().createdAtEpochMs
            )
        )
        return Result.success(Unit)
    }
}
