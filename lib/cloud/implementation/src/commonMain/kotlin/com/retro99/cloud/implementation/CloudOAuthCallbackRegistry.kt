package com.retro99.cloud.implementation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val CALLBACK_PREFIX = "parrot://auth/callback"
private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L

internal expect fun String.decodeCloudOAuthUrlComponent(): String

object CloudOAuthCallbackRegistry {
    val shared = this

    private val mutex = Mutex()
    private var pendingCode: CompletableDeferred<String>? = null

    suspend fun awaitCode(openBrowser: () -> Unit): String {
        val deferred = mutex.withLock {
            check(pendingCode == null) { "A Google sign-in is already in progress" }
            CompletableDeferred<String>().also { pendingCode = it }
        }

        return try {
            openBrowser()
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                deferred.await()
            } ?: throw CloudOAuthException(CloudOAuthException.Reason.TimedOut)
        } finally {
            mutex.withLock {
                if (pendingCode === deferred) {
                    pendingCode = null
                }
            }
        }
    }

    fun handleRedirect(uri: String): Boolean {
        if (uri != CALLBACK_PREFIX && !uri.startsWith("$CALLBACK_PREFIX?")) return false

        val parameters = uri.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
            .split('&')
            .mapNotNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1].decodeCloudOAuthUrlComponent()
                } else {
                    null
                }
            }
            .toMap()
        val code = parameters["code"]
        val error = parameters["error"]
        val deferred = pendingCode ?: return true

        if (code.isNullOrBlank()) {
            val reason = when (error?.lowercase()) {
                null, "access_denied", "user_cancelled", "user_canceled", "cancelled" ->
                    CloudOAuthException.Reason.Cancelled
                else -> CloudOAuthException.Reason.ProviderFailure
            }
            deferred.completeExceptionally(CloudOAuthException(reason))
        } else {
            deferred.complete(code)
        }
        return true
    }

    /** `message` is intentionally ignored so caller/provider text cannot escape into diagnostics. */
    @Suppress("UNUSED_PARAMETER")
    fun cancelPending(message: String): Boolean {
        return pendingCode?.completeExceptionally(
            CloudOAuthException(CloudOAuthException.Reason.Cancelled),
        ) == true
    }
}
