package com.retro99.cloud.implementation

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.test.Test
import kotlin.test.assertFalse

class CloudSessionStateTest {
    @Test
    fun `remembered account does not make a revoked session authenticated`() {
        val state = CloudSessionState(
            status = SessionStatus.NotAuthenticated(),
            accountId = "account-a",
        )

        assertFalse(state.isAuthenticatedAs("account-a"))
    }

    @Test
    fun `remembered account does not make an unavailable refresh authenticated`() {
        val state = CloudSessionState(
            status = SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(IllegalStateException("Offline")),
            ),
            accountId = "account-a",
        )

        assertFalse(state.isAuthenticatedAs("account-a"))
    }
}
