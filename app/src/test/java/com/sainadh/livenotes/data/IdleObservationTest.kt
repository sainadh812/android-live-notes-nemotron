package com.sainadh.livenotes.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleObservationTest {
    @Test fun oneHourOfLiveUpdatesDoesNotRereadTheArchiveAndIdleRefreshesIt() = runTest {
        val captureActive = MutableStateFlow(false)
        val databaseRevision = MutableStateFlow(0)
        var reads = 0
        var activeObservers = 0
        val archive = observeWhenIdle(captureActive) {
            flow {
                activeObservers++
                try {
                    databaseRevision.collect { revision ->
                        reads++
                        emit("archive $revision")
                    }
                } finally {
                    activeObservers--
                }
            }
        }.stateIn(backgroundScope, SharingStarted.Eagerly, "loading")
        runCurrent()
        assertEquals("archive 0", archive.value)
        assertEquals(1, activeObservers)

        captureActive.value = true
        runCurrent()
        assertEquals(0, activeObservers)
        // Two transcript updates per second for an hour, including a collector scheduling turn each.
        repeat(7_200) { revision ->
            databaseRevision.value = revision + 1
            runCurrent()
        }
        assertEquals(1, reads)
        assertEquals("archive 0", archive.value)

        captureActive.value = false
        runCurrent()
        assertEquals(1, activeObservers)
        assertEquals(2, reads)
        assertEquals("archive 7200", archive.value)
    }

    @Test fun openingTheAppDuringCaptureDoesNotStartHistoryObservation() = runTest {
        val captureActive = MutableStateFlow(true)
        var subscriptions = 0
        val archive = observeWhenIdle(captureActive) {
            flow { subscriptions++; emit("saved meeting") }
        }.stateIn(backgroundScope, SharingStarted.Eagerly, "cached")
        runCurrent()
        assertEquals(0, subscriptions)
        assertEquals("cached", archive.value)

        captureActive.value = false
        runCurrent()
        assertEquals(1, subscriptions)
        assertEquals("saved meeting", archive.value)
    }
}
