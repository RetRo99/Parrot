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
}
