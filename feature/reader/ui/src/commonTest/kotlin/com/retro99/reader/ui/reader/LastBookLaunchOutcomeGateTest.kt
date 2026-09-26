package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastBookLaunchOutcomeGateTest {
    @Test
    fun allowsOneTerminalOutcomePerAttemptAndOneAfterRetry() {
        val gate = LastBookLaunchOutcomeGate()

        assertTrue(gate.tryResolve())
        assertFalse(gate.tryResolve())

        gate.beginAttempt()

        assertTrue(gate.tryResolve())
        assertFalse(gate.tryResolve())
    }
}
