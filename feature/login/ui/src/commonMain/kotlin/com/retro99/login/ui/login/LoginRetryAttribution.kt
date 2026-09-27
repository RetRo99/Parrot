package com.retro99.login.ui.login

/** Carries a failed-card Retry origin into the first accepted authentication attempt. */
internal class LoginRetryAttribution(initialRetryOrigin: Boolean) {
    private var retryOriginPending = initialRetryOrigin

    fun consume(
        serverTypeId: String,
        authMethod: String,
        lastFailedLogin: Pair<String, String>?,
    ): Boolean {
        val isRetry = retryOriginPending || lastFailedLogin == (serverTypeId to authMethod)
        retryOriginPending = false
        return isRetry
    }
}
