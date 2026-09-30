package com.retro99.auth.domain.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckAuthStateUseCaseTest {

    @Test
    fun `fresh install remains on welcome`() {
        assertFalse(
            shouldBypassWelcome(
                hasSkippedLogin = false,
                hasImportedBooks = false,
                hasConfiguredRemoteServer = false,
                hasAuthenticatedRemoteServer = false,
            ),
        )
    }

    @Test
    fun `existing guest preference bypasses welcome`() {
        assertTrue(bypass(hasSkippedLogin = true))
    }

    @Test
    fun `imported local library bypasses welcome`() {
        assertTrue(bypass(hasImportedBooks = true))
    }

    @Test
    fun `configured server bypasses welcome even when signed out`() {
        assertTrue(bypass(hasConfiguredRemoteServer = true))
    }

    @Test
    fun `authenticated cloud or server account bypasses welcome`() {
        assertTrue(bypass(hasAuthenticatedRemoteServer = true))
    }

    private fun bypass(
        hasSkippedLogin: Boolean = false,
        hasImportedBooks: Boolean = false,
        hasConfiguredRemoteServer: Boolean = false,
        hasAuthenticatedRemoteServer: Boolean = false,
    ) = shouldBypassWelcome(
        hasSkippedLogin = hasSkippedLogin,
        hasImportedBooks = hasImportedBooks,
        hasConfiguredRemoteServer = hasConfiguredRemoteServer,
        hasAuthenticatedRemoteServer = hasAuthenticatedRemoteServer,
    )
}
