package com.retro99.login.ui.login

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginOperationTest {

    @Test
    fun unexpectedThrowBecomesRecoverableFailureResult() = runTest {
        val result = performLoginSafely { error("private persistence details") }

        val failure = result.fold(
            success = { error("Expected an unexpected-operation failure") },
            failure = { it },
        )
        val unknownError = assertIs<AppError.UnknownError>(failure)
        assertEquals("Login failed unexpectedly. Please retry.", unknownError.message)
    }

    @Test
    fun successfulResultPassesThrough() = runTest {
        val result = performLoginSafely { Ok(Unit) }

        result.fold(
            success = { assertEquals(Unit, it) },
            failure = { error("Unexpected login failure: $it") },
        )
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val cancellation = CancellationException("cancelled")
        val caught = runCatching {
            performLoginSafely { throw cancellation }
        }.exceptionOrNull()

        assertEquals(cancellation, caught)
    }
}
