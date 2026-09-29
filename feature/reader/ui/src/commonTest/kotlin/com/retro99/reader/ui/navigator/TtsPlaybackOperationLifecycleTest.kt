package com.retro99.reader.ui.navigator

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlaybackOperationLifecycleTest {

    @Test
    fun cancellingPendingRequestPreventsItsPlaybackContinuation() = runTest {
        val lifecycle = TtsPlaybackOperationLifecycle(
            scope = backgroundScope,
            startupTimeoutMs = 30_000L,
            onStartupTimeout = {},
        )
        var playbackStarted = false

        lifecycle.launchRequest {
            delay(1_000L)
            playbackStarted = true
        }
        lifecycle.cancelPendingRequest()
        advanceUntilIdle()

        assertFalse(playbackStarted)
    }

    @Test
    fun startupTimeoutRunsOnceAtItsOperationSpecificDeadline() = runTest {
        var timeoutCount = 0
        val lifecycle = TtsPlaybackOperationLifecycle(
            scope = backgroundScope,
            startupTimeoutMs = 30_000L,
            onStartupTimeout = { timeoutCount++ },
        )

        lifecycle.armStartupTimeout()
        advanceTimeBy(29_999L)
        runCurrent()
        assertEquals(0, timeoutCount)
        assertTrue(lifecycle.hasStartupTimeout)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, timeoutCount)
        assertFalse(lifecycle.hasStartupTimeout)
    }

    @Test
    fun cancellingStartupTimeoutDoesNotReportFailure() = runTest {
        var timeoutCount = 0
        val lifecycle = TtsPlaybackOperationLifecycle(
            scope = backgroundScope,
            startupTimeoutMs = 30_000L,
            onStartupTimeout = { timeoutCount++ },
        )

        lifecycle.armStartupTimeout()
        advanceTimeBy(10_000L)
        lifecycle.cancelStartupTimeout()
        advanceUntilIdle()

        assertEquals(0, timeoutCount)
        assertFalse(lifecycle.hasStartupTimeout)
    }
}
