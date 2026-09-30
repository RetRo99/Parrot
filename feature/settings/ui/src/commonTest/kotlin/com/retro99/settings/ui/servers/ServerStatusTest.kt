package com.retro99.settings.ui.servers

import com.retro99.base.server.ServerType
import com.retro99.server.api.AuthError
import com.retro99.server.api.ServerAuthState
import com.retro99.settings.ui.servers.model.ServerUiModel
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerStatusTest {

    private fun server(authState: ServerAuthState) = ServerWithStatusUiModel(
        server = ServerUiModel("id", "Storyteller", ServerType.Storyteller, "https://books.retar.si"),
        authState = authState,
    )

    @Test
    fun `Test authenticated server maps to connected`() {
        // Given
        val item = server(ServerAuthState.Authenticated("id", "rok@example.com", 0L))

        // When
        val status = item.toStatus(loginFailed = false)

        // Then
        assertEquals(ServerStatus.Connected("rok@example.com"), status)
    }

    @Test
    fun `Test failed sign in on signed out server maps to password rejected`() {
        // Given
        val item = server(ServerAuthState.NotAuthenticated("id"))

        // When / Then
        assertEquals(ServerStatus.SignedOut, item.toStatus(loginFailed = false))
        assertEquals(ServerStatus.PasswordRejected, item.toStatus(loginFailed = true))
    }

    @Test
    fun `Test expired and failed sessions map to session expired`() {
        // Given
        val expired = server(ServerAuthState.TokenExpired("id", 0L))
        val failed = server(ServerAuthState.AuthenticationFailed("id", AuthError.TokenRefreshFailed, 0L))

        // When / Then
        assertEquals(ServerStatus.SessionExpired, expired.toStatus(loginFailed = false))
        assertEquals(ServerStatus.SessionExpired, failed.toStatus(loginFailed = false))
        assertTrue(ServerStatus.SessionExpired.isProblem)
    }

    @Test
    fun `Test address helpers`() {
        assertEquals("https://books.retar.si", normalizeServerAddress(" books.retar.si/ "))
        assertEquals("http://10.0.0.2:8080", normalizeServerAddress("http://10.0.0.2:8080/"))
        assertEquals("books.retar.si", serverHost("https://books.retar.si/api"))
        assertTrue(isEncryptedAddress("https://a.b"))
        assertFalse(isEncryptedAddress("http://a.b"))
        assertTrue(isValidServerAddress("books.retar.si"))
        assertFalse(isValidServerAddress("  "))
    }
}
