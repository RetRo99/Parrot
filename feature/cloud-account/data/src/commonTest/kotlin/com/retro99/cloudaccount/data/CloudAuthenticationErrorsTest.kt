package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CloudAccountException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class CloudAuthenticationErrorsTest {
    @Test
    fun `backend authentication codes target the right field`() {
        val error = Exception("backend response")
        assertIs<CloudAccountException.InvalidCredentials>(classifyAuthenticationResponse("invalid_credentials", error))
        assertIs<CloudAccountException.WeakPassword>(classifyAuthenticationResponse("weak_password", error))
        assertIs<CloudAccountException.EmailAlreadyRegistered>(classifyAuthenticationResponse("email_exists", error))
        assertSame(error, classifyAuthenticationResponse("unexpected", error))
    }

    @Test
    fun `offline and timeout failures get a recoverable message`() {
        assertIs<CloudAccountException.NetworkUnavailable>(classifyAuthenticationError(Exception("Unable to resolve host")))
        assertIs<CloudAccountException.NetworkUnavailable>(classifyAuthenticationError(Exception("request timed out")))
        assertIs<CloudAccountException.NetworkUnavailable>(classifyAuthenticationError(Exception("HttpClient failed", Exception("Network is unreachable"))))
    }

    @Test
    fun `unknown errors are preserved for existing reporting`() {
        val error = IllegalStateException("Unexpected server state")
        assertSame(error, classifyAuthenticationError(error))
    }

    @Test
    fun `cancellation is never converted into an authentication error`() = runTest {
        assertFailsWith<CancellationException> {
            withAuthenticationErrors<Unit> { throw CancellationException("cancelled") }
        }
    }
}
