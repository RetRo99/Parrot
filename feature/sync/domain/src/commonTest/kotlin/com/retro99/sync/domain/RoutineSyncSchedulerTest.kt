package com.retro99.sync.domain

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineSyncSchedulerTest {
    @Test
    fun coalescesDirtySignalsUntilThePositionIsIdle() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = RoutineSyncScheduler(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            requestSync = { requests += testScheduler.currentTime },
        )

        scheduler.markDirty()
        runCurrent()
        advanceTimeBy(1_000)
        scheduler.markDirty()
        advanceTimeBy(2_999)
        assertEquals(emptyList(), requests)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(4_000L), requests)
        scheduler.close()
    }

    @Test
    fun enforcesMinimumIntervalBetweenRoutineRequests() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = RoutineSyncScheduler(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            requestSync = { requests += testScheduler.currentTime },
        )

        scheduler.markDirty()
        runCurrent()
        advanceTimeBy(RoutineSyncScheduler.IDLE_DEBOUNCE_MS)
        runCurrent()
        scheduler.markDirty()
        advanceTimeBy(14_999)
        assertEquals(listOf(3_000L), requests)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(3_000L, 18_000L), requests)
        scheduler.close()
    }

    @Test
    fun maximumDirtyWaitBoundsContinuousUpdates() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = RoutineSyncScheduler(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            requestSync = { requests += testScheduler.currentTime },
        )

        scheduler.markDirty()
        runCurrent()
        repeat(14) {
            advanceTimeBy(2_000)
            scheduler.markDirty()
        }
        advanceTimeBy(1_999)
        assertEquals(emptyList(), requests)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(30_000L), requests)
        scheduler.close()
    }

    /**
     * Regression: closing while the worker was parked in the debounce wait used to throw
     * ClosedReceiveChannelException into the host scope and crash the app.
     */
    @Test
    fun closeWhileWorkerIsWaitingDoesNotThrow() = runTest {
        val requests = mutableListOf<Long>()
        val scheduler = RoutineSyncScheduler(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            requestSync = { requests += testScheduler.currentTime },
        )

        scheduler.markDirty()
        runCurrent()
        // The worker is now parked in the debounce wait, which is where close() used to
        // resume it with an exception. This must not throw and must not fire a request.
        scheduler.close()
        advanceTimeBy(RoutineSyncScheduler.MAX_DIRTY_WAIT_MS)
        runCurrent()

        assertEquals(emptyList(), requests)
    }

    @Test
    fun closeIsIdempotent() = runTest {
        val scheduler = RoutineSyncScheduler(
            scope = this,
            nowMillis = { testScheduler.currentTime },
            requestSync = { },
        )

        scheduler.markDirty()
        runCurrent()
        scheduler.close()
        // Callers close both from the reader and from onCleared().
        scheduler.close()
    }
}
