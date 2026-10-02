package com.retro99.reader.data.recap

import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupabaseRecapAuthTokensTest {

    @Test
    fun aLoadingSessionIsNeitherSignedInNorOut() {
        assertNull(SupabaseRecapAuthTokens.signedInOrUnknown(SessionStatus.Initializing))
    }

    @Test
    fun aMissingSessionIsSignedOut() {
        assertEquals(false, SupabaseRecapAuthTokens.signedInOrUnknown(SessionStatus.NotAuthenticated(isSignOut = false)))
    }
}
