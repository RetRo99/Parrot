package com.retro99.login.ui.login

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginSubmissionGateTest {

    @Test
    fun acceptsAtMostOneConcurrentSubmissionAndAllowsRetryAfterFailure() {
        val gate = LoginSubmissionGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())

        gate.finish()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }
}
