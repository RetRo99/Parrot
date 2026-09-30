package com.retro99.parrot.navigation

import kotlinx.coroutines.CancellationException

internal data class StartupAuthResolution(
    val shouldOpenLibrary: Boolean,
    val usedFallback: Boolean,
)

/** Resolves startup auth state without turning coroutine cancellation into an app failure. */
internal suspend fun resolveStartupAuthState(
    checkAuthState: suspend () -> Boolean,
    reportUnexpectedFailure: (Exception) -> Unit,
): StartupAuthResolution = try {
    StartupAuthResolution(
        shouldOpenLibrary = checkAuthState(),
        usedFallback = false,
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    reportUnexpectedFailure(failure)
    StartupAuthResolution(
        // On an unreadable persisted state, prefer a recoverable library route over
        // showing onboarding again to an existing user.
        shouldOpenLibrary = true,
        usedFallback = true,
    )
}
