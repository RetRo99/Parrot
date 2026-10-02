package com.retro99.login.data.oauth

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class StorytellerOAuthCallbackRegistryTest {

    private val registry = StorytellerOAuthCallbackRegistry
    private val clock = TestTimeSource()

    @BeforeTest
    fun setUp() {
        registry.timeSource = clock
    }

    @AfterTest
    fun tearDown() {
        registry.cancelPending("test cleanup")
        registry.lastAbandonedAt = null
        registry.timeSource = TimeSource.Monotonic
    }

    @Test
    fun callbackForPendingAttemptDeliversToken() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        assertTrue(registry.handleRedirect("storyteller://settings?token=abc%2Edef"))

        assertEquals(Ok("abc.def"), pending.await())
    }

    @Test
    fun unsolicitedCallbackIsSwallowedAndDoesNotLeakIntoNextAttempt() = runTest {
        assertTrue(registry.handleRedirect("storyteller://settings?token=attacker"))

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }
        registry.handleRedirect("storyteller://settings?token=mine")

        assertEquals(Ok("mine"), pending.await())
    }

    @Test
    fun secondCallbackCannotReplaceFirstToken() = runTest {
        var attemptId = -1L
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken { attemptId = it }
        }

        registry.handleRedirect("storyteller://settings?token=first")
        registry.handleRedirect("storyteller://settings?token=second")

        assertEquals(Ok("first"), pending.await())
        assertFalse(registry.cancelPending("late", attemptId))
    }

    @Test
    fun callbackAfterWindowIsRejected() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        clock += 6.minutes
        assertTrue(registry.handleRedirect("storyteller://settings?token=late"))
        assertTrue(registry.isPendingActive)

        val error = pending.await().getError()
        assertIs<AppError.AuthError>(error)
        assertEquals("OAuth sign-in timed out", error.message)
    }

    @Test
    fun cancelForStaleAttemptIdDoesNotCancelCurrentAttempt() = runTest {
        var attemptId = -1L
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken { attemptId = it }
        }

        assertFalse(registry.cancelPending("stale", attemptId - 1))
        assertTrue(registry.isPendingActive)
        assertTrue(registry.cancelPending("mine", attemptId))

        val error = assertIs<AppError.AuthError>(pending.await().getError())
        assertTrue(error.isCancellation)
    }

    @Test
    fun secondConcurrentAttemptIsRefused() = runTest {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        val second = registry.awaitToken { error("browser must not open") }

        assertIs<AppError.AuthError>(second.getError())
        registry.handleRedirect("storyteller://settings?token=t")
        assertEquals(Ok("t"), first.await())
    }

    @Test
    fun lookalikeSchemesAreNotTreatedAsCallbacks() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        assertFalse(registry.handleRedirect("storyteller://settingsevil?token=x"))
        assertFalse(registry.handleRedirect("parrot://settings?token=x"))
        assertTrue(registry.isPendingActive)

        registry.handleRedirect("storyteller://settings?token=real")
        assertEquals(Ok("real"), pending.await())
    }

    @Test
    fun callbackWithoutTokenFailsAttempt() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        registry.handleRedirect("storyteller://settings")

        assertIs<AppError.AuthError>(pending.await().getError())
    }

    @Test
    fun malformedOrEmptyTokenFailsAttemptWithoutThrowing() = runTest {
        listOf(
            "storyteller://settings?token=%",
            "storyteller://settings?token=%zz",
            "storyteller://settings?token=",
            "storyteller://settings?token=%E0%A4%A",
        ).forEach { uri ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                registry.awaitToken {}
            }

            assertTrue(registry.handleRedirect(uri))

            assertIs<AppError.AuthError>(pending.await().getError(), uri)
        }
    }

    @Test
    fun tokenIsReadFromQueryNotFragment() = runTest {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }

        registry.handleRedirect("storyteller://settings?a=1&token=q#token=frag")

        assertEquals(Ok("q"), pending.await())
    }

    @Test
    fun sessionRedirectOnlyCompletesItsOwnAttempt() = runTest {
        var attemptId = -1L
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken { attemptId = it }
        }

        assertTrue(registry.handleSessionRedirect("storyteller://settings?token=old", attemptId - 1))
        assertTrue(registry.isPendingActive)
        registry.handleSessionRedirect("storyteller://settings?token=mine", attemptId)

        assertEquals(Ok("mine"), pending.await())
    }

    @Test
    fun unboundCallbackRightAfterAbandonedAttemptFailsClosed() = runTest {
        var firstId = -1L
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken { firstId = it }
        }
        registry.cancelPending("user went back", firstId)
        first.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }
        clock += 1.seconds
        registry.handleRedirect("storyteller://settings?token=late-from-first")

        val error = assertIs<AppError.AuthError>(second.await().getError())
        assertFalse(error.isCancellation)

        // The rejection itself does not extend the quiet period.
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitToken {}
        }
        clock += 3.seconds
        registry.handleRedirect("storyteller://settings?token=fresh")
        assertEquals(Ok("fresh"), third.await())
    }
}
