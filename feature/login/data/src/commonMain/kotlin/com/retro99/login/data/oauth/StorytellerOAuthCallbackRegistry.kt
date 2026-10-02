package com.retro99.login.data.oauth

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val CALLBACK_PREFIX = "storyteller://settings"

// Storyteller's app token itself expires after 5 minutes.
private val CALLBACK_WINDOW: Duration = 5.minutes

// A callback still in flight for an abandoned attempt may land in the next.
private val QUIET_PERIOD_AFTER_ABANDON: Duration = 3.seconds

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

    @Volatile
    internal var lastAbandonedAt: TimeMark? = null

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
            lastAbandonedAt = timeSource.markNow()
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
    fun handleRedirect(uri: String): Boolean = deliver(uri, attemptId = null)

    /** For a platform session that only ever reports its own [attemptId]. */
    internal fun handleSessionRedirect(uri: String, attemptId: Long): Boolean =
        deliver(uri, attemptId)

    /** Cancels the pending attempt; with [attemptId], only if it is still that one. */
    fun cancelPending(message: String, attemptId: Long? = null): Boolean {
        val attempt = pending ?: return false
        if (attemptId != null && attempt.id != attemptId) return false
        val cancelled = attempt.result.complete(Err(AppError.AuthError(message, isCancellation = true)))
        if (cancelled) lastAbandonedAt = timeSource.markNow()
        return cancelled
    }

    private fun deliver(uri: String, attemptId: Long?): Boolean {
        if (!uri.isStorytellerCallback()) return false

        val attempt = pending ?: return true
        if (attemptId != null && attempt.id != attemptId) return true
        val launchedAt = attempt.launchedAt ?: return true
        if (launchedAt.elapsedNow() > CALLBACK_WINDOW) return true

        val token = uri.callbackToken()
        val result = when {
            // Unbound callbacks can't be told apart; fail closed so a retry works.
            attemptId == null && abandonedRecently() ->
                Err(AppError.AuthError("OAuth sign-in was interrupted, please try again"))
            token.isNullOrBlank() ->
                Err(AppError.AuthError("Storyteller did not return an OAuth app token"))
            else -> Ok(token)
        }

        // complete() is a no-op after the first call, so a token is used once.
        attempt.result.complete(result)
        return true
    }

    private fun abandonedRecently(): Boolean =
        lastAbandonedAt?.let { it.elapsedNow() < QUIET_PERIOD_AFTER_ABANDON } == true

    private fun String.isStorytellerCallback(): Boolean {
        if (!startsWith(CALLBACK_PREFIX)) return false
        val rest = substring(CALLBACK_PREFIX.length)
        return rest.isEmpty() || rest[0] == '?' || rest[0] == '/' || rest[0] == '#'
    }

    // Malformed escapes come from untrusted intents; they must not throw.
    private fun String.callbackToken(): String? = runCatching {
        parseQueryString(Url(this).encodedQuery, decode = false)["token"]
            ?.decodeURLQueryComponent(plusIsSpace = true)
    }.getOrNull()
}
