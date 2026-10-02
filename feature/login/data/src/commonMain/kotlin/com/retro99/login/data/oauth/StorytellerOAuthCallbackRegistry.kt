package com.retro99.login.data.oauth

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val CALLBACK_PREFIX = "storyteller://settings"

// Storyteller's app token itself expires after 5 minutes.
private val CALLBACK_WINDOW: Duration = 5.minutes

internal expect fun String.decodeUrlComponent(): String

/**
 * Hands the `storyteller://settings?token=` callback to the one sign-in
 * attempt that is waiting for it. Storyteller has no `state` parameter, so
 * unsolicited, late and repeated callbacks are swallowed instead.
 */
object StorytellerOAuthCallbackRegistry {

    val shared = this

    private val mutex = Mutex()

    @Volatile
    private var pending: PendingAttempt? = null
    private var lastAttemptId = 0L

    internal var timeSource: TimeSource = TimeSource.Monotonic

    private class PendingAttempt(val id: Long) {
        val result = CompletableDeferred<AppResult<String>>()

        @Volatile
        var launchedAt: TimeMark? = null
    }

    val isPendingActive: Boolean
        get() = pending?.result?.isActive == true

    /** [openBrowser] receives the attempt id, for [cancelPending]. */
    suspend fun awaitToken(openBrowser: (attemptId: Long) -> Unit): AppResult<String> {
        val attempt = mutex.withLock {
            if (pending != null) {
                return Err(AppError.AuthError("An OAuth sign-in is already in progress"))
            }

            PendingAttempt(++lastAttemptId).also { pending = it }
        }

        return try {
            attempt.launchedAt = timeSource.markNow()
            openBrowser(attempt.id)
            withTimeout(CALLBACK_WINDOW) {
                attempt.result.await()
            }
        } catch (e: TimeoutCancellationException) {
            Err(AppError.AuthError("OAuth sign-in timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(AppError.AuthError(e.message ?: "OAuth sign-in was cancelled or timed out"))
        } finally {
            mutex.withLock {
                if (pending === attempt) {
                    pending = null
                }
            }
        }
    }

    /** True when [uri] is a Storyteller callback, whether or not it was accepted. */
    fun handleRedirect(uri: String): Boolean {
        if (!uri.isStorytellerCallback()) return false

        val attempt = pending ?: return true
        val launchedAt = attempt.launchedAt ?: return true
        if (launchedAt.elapsedNow() > CALLBACK_WINDOW) return true

        val token = uri.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
            .split('&')
            .mapNotNull { param ->
                val parts = param.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .firstOrNull { (key, _) -> key == "token" }
            ?.second
            ?.decodeUrlComponent()

        val result = if (token.isNullOrBlank()) {
            Err(AppError.AuthError("Storyteller did not return an OAuth app token"))
        } else {
            Ok(token)
        }

        // complete() is a no-op after the first call, so a token is used once.
        attempt.result.complete(result)
        return true
    }

    /** Cancels the pending attempt; with [attemptId], only if it is still that one. */
    fun cancelPending(message: String, attemptId: Long? = null): Boolean {
        val attempt = pending ?: return false
        if (attemptId != null && attempt.id != attemptId) return false
        return attempt.result.complete(Err(AppError.AuthError(message, isCancellation = true)))
    }

    private fun String.isStorytellerCallback(): Boolean {
        if (!startsWith(CALLBACK_PREFIX)) return false
        val rest = substring(CALLBACK_PREFIX.length)
        return rest.isEmpty() || rest[0] == '?' || rest[0] == '/' || rest[0] == '#'
    }
}
