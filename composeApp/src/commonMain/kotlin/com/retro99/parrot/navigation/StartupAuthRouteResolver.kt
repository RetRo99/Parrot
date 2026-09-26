package com.retro99.parrot.navigation

import kotlinx.coroutines.CancellationException

internal data class StartupAuthResolution(
    val isAuthenticated: Boolean,
    val usedFallback: Boolean,
)

/** Resolves startup auth state without turning coroutine cancellation into an app failure. */
internal suspend fun resolveStartupAuthState(
    checkAuthState: suspend () -> Boolean,
    reportUnexpectedFailure: (Exception) -> Unit,
): StartupAuthResolution = try {
    StartupAuthResolution(
        isAuthenticated = checkAuthState(),
        usedFallback = false,
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    reportUnexpectedFailure(failure)
    StartupAuthResolution(
        isAuthenticated = false,
        usedFallback = true,
    )
}
