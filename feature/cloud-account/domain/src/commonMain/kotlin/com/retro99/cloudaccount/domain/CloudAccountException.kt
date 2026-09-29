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
}

enum class OAuthFailureReason(val analyticsCode: String) {
    TimedOut("oauth_timed_out"),
    ProviderFailure("oauth_provider_failed"),
}
