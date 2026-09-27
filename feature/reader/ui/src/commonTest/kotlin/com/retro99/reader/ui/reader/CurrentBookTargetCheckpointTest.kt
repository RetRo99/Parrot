package com.retro99.reader.ui.reader

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentBookTargetCheckpointTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun checkpointsOnceAtTheQualificationBoundary() = runTest {
        var checkpointCount = 0
        val checkpoint = CurrentBookTargetCheckpoint(
            scope = this,
            delayMillis = 60_000,
            onCheckpointDue = { checkpointCount++ },
        )

        checkpoint.start()
        checkpoint.start()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(0, checkpointCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, checkpointCount)

        checkpoint.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, checkpointCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun closingBeforeTheQualificationBoundaryCancelsCheckpoint() = runTest {
        var checkpointCount = 0
        val checkpoint = CurrentBookTargetCheckpoint(
            scope = this,
            delayMillis = 60_000,
            onCheckpointDue = { checkpointCount++ },
        )

        checkpoint.start()
        advanceTimeBy(30_000)
        checkpoint.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(0, checkpointCount)
    }
}
