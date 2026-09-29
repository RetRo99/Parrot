package com.retro99.cloud.implementation

/** Safe typed outcome for the Google OAuth callback; provider text and callback data are omitted. */
class CloudOAuthException(val reason: Reason) : Exception(
    when (reason) {
        Reason.Cancelled -> "Google sign-in was cancelled"
        Reason.TimedOut -> "Google sign-in timed out"
        Reason.ProviderFailure -> "Google sign-in failed"
    },
) {
    enum class Reason {
        Cancelled,
        TimedOut,
        ProviderFailure,
    }
}
