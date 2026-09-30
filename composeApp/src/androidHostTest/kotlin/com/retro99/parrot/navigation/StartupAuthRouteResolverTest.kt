package com.retro99.parrot.navigation

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupAuthRouteResolverTest {

    @Test
    fun successfulReturningUserCheckSelectsLibraryWithoutReporting() = runTest {
        var reported: Exception? = null

        val result = resolveStartupAuthState(
            checkAuthState = { true },
            reportUnexpectedFailure = { reported = it },
        )

        assertEquals(StartupAuthResolution(shouldOpenLibrary = true, usedFallback = false), result)
        assertEquals(null, reported)
    }

    @Test
    fun successfulFirstRunCheckSelectsWelcomeWithoutReporting() = runTest {
        val reported = mutableListOf<Exception>()

        val result = resolveStartupAuthState(
            checkAuthState = { false },
            reportUnexpectedFailure = reported::add,
        )

        assertEquals(StartupAuthResolution(shouldOpenLibrary = false, usedFallback = false), result)
        assertEquals(emptyList(), reported)
    }

    @Test
    fun failedPersistedStateCheckReportsOnceAndOpensLibraryFallback() = runTest {
        val failure = IllegalStateException("private fixture detail")
        val reported = mutableListOf<Exception>()

        val result = resolveStartupAuthState(
            checkAuthState = { throw failure },
            reportUnexpectedFailure = reported::add,
        )

        assertEquals(StartupAuthResolution(shouldOpenLibrary = true, usedFallback = true), result)
        assertEquals(listOf<Exception>(failure), reported)
    }

    @Test
    fun cancellationIsRethrownAndNotReported() = runTest {
        val cancellation = CancellationException("expected cancellation")
        val reported = mutableListOf<Exception>()

        var thrown: CancellationException? = null
        try {
            resolveStartupAuthState(
                checkAuthState = { throw cancellation },
                reportUnexpectedFailure = reported::add,
            )
        } catch (failure: CancellationException) {
            thrown = failure
        }

        assertEquals(cancellation, thrown)
        assertEquals(emptyList<Exception>(), reported)
    }
}

private fun runTest(block: suspend () -> Unit) {
    var completion: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                completion = result
            }
        },
    )
    checkNotNull(completion) { "The test block suspended unexpectedly" }.getOrThrow()
}
