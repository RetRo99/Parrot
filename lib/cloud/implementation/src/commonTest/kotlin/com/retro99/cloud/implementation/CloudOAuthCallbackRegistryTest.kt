package com.retro99.cloud.implementation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudOAuthCallbackRegistryTest {
    @Test
    fun `access denied callback is typed cancellation and omits provider description`() = runTest {
        supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                CloudOAuthCallbackRegistry.awaitCode {}
            }

            assertTrue(
                CloudOAuthCallbackRegistry.handleRedirect(
                    "parrot://auth/callback?error=access_denied&error_description=private%20provider%20message",
                ),
            )
            val error = pending.failure()

            assertEquals(CloudOAuthException.Reason.Cancelled, error.reason)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun `provider callback failure remains distinct from user cancellation`() = runTest {
        supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                CloudOAuthCallbackRegistry.awaitCode {}
            }

            CloudOAuthCallbackRegistry.handleRedirect(
                "parrot://auth/callback?error=server_error&error_description=private%20provider%20message",
            )
            val error = pending.failure()

            assertEquals(CloudOAuthException.Reason.ProviderFailure, error.reason)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun `explicit app cancellation callback maps to typed cancellation`() = runTest {
        supervisorScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                CloudOAuthCallbackRegistry.awaitCode {}
            }

            assertTrue(CloudOAuthCallbackRegistry.cancelPending("private cancellation detail"))
            val error = pending.failure()

            assertEquals(CloudOAuthException.Reason.Cancelled, error.reason)
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }
}

private suspend fun kotlinx.coroutines.Deferred<String>.failure(): CloudOAuthException {
    try {
        await()
    } catch (error: CloudOAuthException) {
        return error
    }
    error("Expected OAuth callback failure")
}
