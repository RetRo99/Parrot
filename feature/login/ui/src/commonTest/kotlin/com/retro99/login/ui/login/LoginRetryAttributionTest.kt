package com.retro99.login.ui.login

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginRetryAttributionTest {
    @Test
    fun failedCardRetryMarksOnlyTheFirstAcceptedAttempt() {
        val attribution = LoginRetryAttribution(initialRetryOrigin = true)

        assertTrue(
            attribution.consume(
                serverTypeId = "storyteller",
                authMethod = "credentials",
                lastFailedLogin = null,
            ),
        )
        assertFalse(
            attribution.consume(
                serverTypeId = "storyteller",
                authMethod = "credentials",
                lastFailedLogin = null,
            ),
        )
    }

    @Test
    fun ordinaryLoginIsNotAReattempt() {
        val attribution = LoginRetryAttribution(initialRetryOrigin = false)

        assertFalse(
            attribution.consume(
                serverTypeId = "storyteller",
                authMethod = "credentials",
                lastFailedLogin = null,
            ),
        )
    }

    @Test
    fun priorFailureWithinTheSameLoginRouteStillCountsAsRetry() {
        val attribution = LoginRetryAttribution(initialRetryOrigin = false)

        assertTrue(
            attribution.consume(
                serverTypeId = "storyteller",
                authMethod = "credentials",
                lastFailedLogin = "storyteller" to "credentials",
            ),
        )
    }
}
