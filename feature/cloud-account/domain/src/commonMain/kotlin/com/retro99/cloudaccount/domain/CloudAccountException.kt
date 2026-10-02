package com.retro99.cloudaccount.domain

sealed class CloudAccountException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : CloudAccountException("Cloud account is not configured")

    class ProfileAlreadyLinked :
        CloudAccountException("Cloud profile is already linked to a different account")

    class LocalStatePersistence(
        cause: Throwable,
        val cleanupFailed: Boolean = false,
    ) : CloudAccountException("Cloud account state could not be saved on this device", cause)

    class OAuthCancelled : CloudAccountException("Google sign-in was cancelled")

    class OAuthFailure(val reason: OAuthFailureReason) : CloudAccountException("Google sign-in failed")

    // The server wants a recent sign-in before deleting the account.
    class ReauthenticationRequired(cause: Throwable? = null) :
        CloudAccountException("Sign in again to delete the cloud account", cause)
}

enum class OAuthFailureReason(val analyticsCode: String) {
    TimedOut("oauth_timed_out"),
    ProviderFailure("oauth_provider_failed"),
}
