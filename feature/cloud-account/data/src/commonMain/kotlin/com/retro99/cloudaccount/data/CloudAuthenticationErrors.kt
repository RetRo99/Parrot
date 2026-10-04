package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CloudAccountException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException

internal suspend fun <T> withAuthenticationErrors(block: suspend () -> T): T = try {
    block()
} catch (error: Exception) {
    if (error is CancellationException) throw error
    throw classifyAuthenticationError(error)
}

internal fun classifyAuthenticationError(error: Exception): Exception {
    if (error is RestException) return classifyAuthenticationResponse(error.error, error)
    val message = generateSequence<Throwable>(error) { it.cause }
        .take(8).joinToString(" ") { "${it::class.simpleName} ${it.message.orEmpty()}" }.lowercase()
    return when {
        listOf(
            "unknownhost", "unable to resolve host", "failed to connect", "network is unreachable",
            "unresolvedaddress", "connectexception", "timeout", "timed out", "not connected to the internet",
        ).any { it in message } ->
            CloudAccountException.NetworkUnavailable(error)
        else -> error
    }
}

internal fun classifyAuthenticationResponse(code: String, error: Exception): Exception = when (code) {
    "invalid_credentials" -> CloudAccountException.InvalidCredentials(error)
    "weak_password" -> CloudAccountException.WeakPassword(error)
    "email_exists", "user_already_exists" -> CloudAccountException.EmailAlreadyRegistered(error)
    else -> error
}
