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
import kotlin.coroutines.cancellation.CancellationException

private const val CALLBACK_PREFIX = "storyteller://settings"
private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L

internal expect fun String.decodeUrlComponent(): String

object StorytellerOAuthCallbackRegistry {

    val shared = this

    private val mutex = Mutex()
    private var pendingToken: CompletableDeferred<AppResult<String>>? = null

    val isPendingActive: Boolean
        get() = pendingToken?.isActive == true

    suspend fun awaitToken(openBrowser: () -> Unit): AppResult<String> {
        val deferred = mutex.withLock {
            if (pendingToken != null) {
                return Err(AppError.AuthError("An OAuth sign-in is already in progress"))
            }

            CompletableDeferred<AppResult<String>>().also {
                pendingToken = it
            }
        }

        return try {
            openBrowser()
            withTimeout(CALLBACK_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Err(AppError.AuthError("OAuth sign-in timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(AppError.AuthError(e.message ?: "OAuth sign-in was cancelled or timed out"))
        } finally {
            mutex.withLock {
                if (pendingToken === deferred) {
                    pendingToken = null
                }
            }
        }
    }

    fun handleRedirect(uri: String): Boolean {
        if (!uri.startsWith(CALLBACK_PREFIX)) return false

        val token = uri.substringAfter('?', missingDelimiterValue = "")
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

        pendingToken?.complete(result)
        return true
    }

    fun cancelPending(message: String): Boolean {
        return pendingToken?.complete(Err(AppError.AuthError(message, isCancellation = true))) == true
    }
}
