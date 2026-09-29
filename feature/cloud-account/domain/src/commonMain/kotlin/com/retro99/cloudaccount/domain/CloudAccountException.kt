package com.retro99.cloudaccount.domain

sealed class CloudAccountException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : CloudAccountException("Cloud account is not configured")

    class ProfileAlreadyLinked :
        CloudAccountException("Cloud profile is already linked to a different account")

    class LocalStatePersistence(
        cause: Throwable,
        val cleanupFailed: Boolean = false,
    ) : CloudAccountException("Cloud account state could not be saved on this device", cause)
}
