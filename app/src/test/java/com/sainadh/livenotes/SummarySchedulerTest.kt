package com.sainadh.livenotes

import com.sainadh.livenotes.ai.SummaryScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SummarySchedulerTest {
    @Test
    fun partialBurstProducesOneRefreshAfterInterval() = runTest {
        val calls = mutableListOf<String>()
        val scheduler = SummaryScheduler(backgroundScope, clockMs = { testScheduler.currentTime }) {
            calls += it
            Result.success(Unit)
        }
        repeat(100) { scheduler.request("2026-09-06", false) }
        runCurrent()
        assertEquals(0, calls.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("2026-09-06"), calls)
    }

    @Test
    fun finalPromotesPendingPartialWithoutWaitingThirtySeconds() = runTest {
        var calls = 0
        val scheduler = SummaryScheduler(backgroundScope, clockMs = { testScheduler.currentTime }) {
            calls++
            Result.success(Unit)
        }
        scheduler.request("today", false)
        runCurrent()
        advanceTimeBy(1_000)
        scheduler.request("today", true)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun updatesDuringSlowRequestCoalesceAndNeverRunConcurrently() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var maxActive = 0
        val scheduler = SummaryScheduler(backgroundScope, clockMs = { testScheduler.currentTime }) {
            calls++
            active++
            maxActive = maxOf(maxActive, active)
            if (calls == 1) release.await()
            active--
            Result.success(Unit)
        }
        scheduler.request("today", true)
        runCurrent()
        repeat(200) { scheduler.request("today", false) }
        scheduler.request("today", true)
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maxActive)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun pendingDaysAreNotLostAcrossMidnight() = runTest {
        val calls = mutableListOf<String>()
        val scheduler = SummaryScheduler(backgroundScope, clockMs = { testScheduler.currentTime }) {
            calls += it
            Result.success(Unit)
        }
        scheduler.request("yesterday", false)
        scheduler.request("today", true)
        runCurrent()
        assertEquals(listOf("today"), calls)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("today", "yesterday"), calls)
    }

    @Test
    fun failureIsVisibleAndNextSuccessClearsIt() = runTest {
        var calls = 0
        val scheduler = SummaryScheduler(backgroundScope, clockMs = { testScheduler.currentTime }) {
            if (++calls == 1) error("Connection failed")
            Result.success(Unit)
        }
        scheduler.request("today", true)
        runCurrent()
        assertEquals("Connection failed", scheduler.error.value)
        scheduler.retryFailed()
        runCurrent()
        assertEquals(2, calls)
        assertNull(scheduler.error.value)
    }
}
