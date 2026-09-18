package com.retro99.cloudaccount.data

import com.retro99.cloud.implementation.CloudSessionState
import com.retro99.cloudaccount.domain.model.CloudAuthState
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CloudSessionStateExtTest {
    @Test
    fun `offline refresh does not require signing in again`() {
        val state = CloudSessionState(
            status = SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(IllegalStateException("Offline")),
            ),
            accountId = "account-a",
        )

        val result = state.toCloudAuthState()

        assertEquals("account-a", assertIs<CloudAuthState.RefreshUnavailable>(result).account.id)
    }

    @Test
    fun `revoked session requires authentication for the retained account`() {
        val state = CloudSessionState(
            status = SessionStatus.NotAuthenticated(),
            accountId = "account-a",
        )

        val result = state.toCloudAuthState()

        assertEquals("account-a", assertIs<CloudAuthState.ReauthenticationRequired>(result).account.id)
    }

    @Test
    fun `explicit sign out has no reauthentication requirement`() {
        val state = CloudSessionState(SessionStatus.NotAuthenticated())

        assertIs<CloudAuthState.SignedOut>(state.toCloudAuthState())
    }

    @Test
    fun `pending restoration is not reported as signed out`() {
        val state = CloudSessionState(SessionStatus.Initializing)

        assertIs<CloudAuthState.RestoringSession>(state.toCloudAuthState())
    }
}
