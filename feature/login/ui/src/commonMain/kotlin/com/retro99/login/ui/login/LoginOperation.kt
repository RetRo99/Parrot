package com.retro99.login.ui.login

import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import kotlin.coroutines.cancellation.CancellationException

/** Keeps unexpected operation exceptions on the normal Login recovery path without swallowing cancel. */
internal suspend fun performLoginSafely(
    operation: suspend () -> CompletableResult,
): CompletableResult = try {
    operation()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Err(AppError.UnknownError(UnexpectedLoginException(failure)))
}

private class UnexpectedLoginException(cause: Throwable) :
    Exception("Login failed unexpectedly. Please retry.", cause)
