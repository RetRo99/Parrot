package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentBookTargetSaveStateTest {
    @Test
    fun successfulCheckpointPreventsDuplicateCloseWrite() {
        val state = CurrentBookTargetSaveState()

        assertFalse(state.hasSucceeded)
        assertFalse(state.isRetry)

        state.recordResult(succeeded = true)

        assertTrue(state.hasSucceeded)
        assertFalse(state.isRetry)
    }

    @Test
    fun failedCheckpointRemainsEligibleForOneExplicitRetry() {
        val state = CurrentBookTargetSaveState()

        state.recordResult(succeeded = false)
        assertFalse(state.hasSucceeded)
        assertTrue(state.isRetry)

        state.recordResult(succeeded = true)
        assertTrue(state.hasSucceeded)
        assertFalse(state.isRetry)
    }
}
