package com.sainadh.livenotes.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** One request in flight, with at most one pending refresh per day. */
internal class SummaryScheduler(
    scope: CoroutineScope,
    private val intervalMs: Long = 30_000L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val summarize: suspend (String) -> Result<Unit>
) {
    private val lock = Any()
    private val pending = linkedMapOf<String, Long>()
    private val nextAllowed = mutableMapOf<String, Long>()
    private var failedDate: String? = null
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        require(intervalMs > 0)
        scope.launch { work() }
    }

    fun request(dateKey: String, isFinal: Boolean) {
        synchronized(lock) {
            val now = clockMs()
            val due = if (isFinal) now else nextAllowed.getOrPut(dateKey) { now + intervalMs }
            pending[dateKey] = minOf(pending[dateKey] ?: due, due)
        }
        wakeups.trySend(Unit)
    }

    fun retryFailed() {
        val dateKey = synchronized(lock) { failedDate } ?: return
        request(dateKey, isFinal = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun work() {
        while (true) {
            val next = synchronized(lock) { pending.minByOrNull { it.value }?.toPair() }
            if (next == null) {
                wakeups.receive()
                continue
            }
            val waitMs = next.second - clockMs()
            if (waitMs > 0) {
                // A final result can wake the worker before the partial's deadline.
                select<Unit> {
                    wakeups.onReceive { }
                    onTimeout(waitMs) { }
                }
                continue
            }
            synchronized(lock) {
                pending.remove(next.first)
                nextAllowed[next.first] = clockMs() + intervalMs
                nextAllowed.keys.retainAll(pending.keys + next.first)
            }
            val result = try {
                summarize(next.first)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            synchronized(lock) { failedDate = if (result.isFailure) next.first else null }
            _error.value = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }
}
