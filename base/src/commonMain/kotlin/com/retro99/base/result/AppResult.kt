package com.retro99.base.result

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.annotation.UnsafeResultValueAccess
import com.github.michaelbull.result.asErr
import com.github.michaelbull.result.onFailure
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import resources.translations.error_api_generic
import resources.translations.error_api_unknown
import resources.translations.error_database_generic
import resources.translations.error_database_specific
import resources.translations.error_network_connectivity
import resources.translations.error_network_generic
import resources.translations.error_unknown
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.cancellation.CancellationException

typealias AppResult<T> = Result<T, AppError>

typealias CompletableResult = Result<Unit, AppError>

inline fun <V, E> Result<V, E>.andThenAlways(action: (Result<V, E>) -> Result<V, E>): Result<V, E> {
    return action(this)
}

sealed class AppError(open val message: String?) {
    data class NetworkError(
        val throwable: Throwable,
        val isConnectivity: Boolean = false,
        val isTimeout: Boolean = false,
        val isExpectedFailure: Boolean = isConnectivity || isTimeout,
    ) :
        AppError(throwable.message)

    data class ApiError(val code: Int, override val message: String? = null) : AppError(message)
    data class DatabaseError(val throwable: Throwable, val table: String? = null) :
        AppError(throwable.message)

    data class UnknownError(val throwable: Throwable) : AppError(throwable.message)

    /**
     * Authentication error (e.g., invalid credentials, expired token).
     */
    data class AuthError(
        override val message: String?,
        val isCancellation: Boolean = false,
    ) : AppError(message)

    /**
     * Resource not found error.
     */
    data class NotFoundError(override val message: String?) : AppError(message)

    /** Expected user/auth/transport failures use operation outcomes, not Crashlytics reports. */
    val shouldReportException: Boolean
        get() = when (this) {
            is NetworkError -> !isExpectedFailure
            is ApiError -> code !in 400..599
            is AuthError -> false
            else -> true
        }

    /**
     * Converts this AppError to a Throwable for logging purposes.
     */
    fun toThrowable(): Throwable = when (this) {
        is NetworkError -> throwable
        is DatabaseError -> throwable
        is UnknownError -> throwable
        is ApiError -> Exception("API Error $code: $message")
        is AuthError -> Exception("Auth Error: $message")
        is NotFoundError -> Exception("Not Found: $message")
    }

    fun toStringRes(): StringResource {
        return when (this) {
            is NetworkError -> if (isConnectivity) {
                StringRes.error_network_connectivity
            } else {
                StringRes.error_network_generic
            }

            is ApiError -> if (message != null) {
                StringRes.error_api_generic
            } else {
                StringRes.error_api_unknown
            }

            is DatabaseError -> if (table != null) {
                StringRes.error_database_specific
            } else {
                StringRes.error_database_generic
            }

            is UnknownError -> StringRes.error_unknown

            is AuthError -> StringRes.error_api_generic

            is NotFoundError -> StringRes.error_api_generic
        }
    }
}

/**
 * Calls the specified function [block] with [this] value as its receiver and returns its
 * encapsulated result if invocation was successful, catching any [Throwable] exception that was
 * thrown from the [block] function execution and encapsulating it as an AppError.
 * CancellationException is rethrown to allow proper coroutine cancellation.
 */
@OptIn(ExperimentalContracts::class)
inline infix fun <T, V> T.runCatchingAsAppError(block: T.() -> V): Result<V, AppError> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return try {
        Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Err(AppError.UnknownError(e))
    }
}

/**
 * Maps this [AppResult] by applying the [transform] function to the value if this result is Ok.
 * Unlike the standard [map], this catches any exception thrown by [transform] and wraps it
 * as [AppError.UnknownError].
 * CancellationException is rethrown to allow proper coroutine cancellation.
 */
@OptIn(ExperimentalContracts::class, UnsafeResultValueAccess::class)
inline infix fun <V, U> AppResult<V>.mapCatching(transform: (V) -> U): AppResult<U> {
    contract {
        callsInPlace(transform, InvocationKind.AT_MOST_ONCE)
    }
    return when {
        isOk -> try {
            Ok(transform(value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Err(AppError.UnknownError(e))
        }
        else -> this.asErr()
    }
}

/**
 * Logs the AppError using the provided Analytics instance and returns the error unchanged.
 * Useful for chaining in error handling flows.
 *
 * @param analytics The Analytics instance to use for logging
 * @param context A descriptive message about where the error occurred
 * @return The same AppError for chaining
 */
fun AppError.log(analytics: Analytics, context: String): AppError {
    if (shouldReportException) {
        analytics.logException(toThrowable(), context)
    }
    return this
}

/** Logs an unexpected error with validated structured context and returns it unchanged. */
fun AppError.log(analytics: Analytics, context: DiagnosticContext): AppError {
    if (shouldReportException) {
        analytics.logException(toThrowable(), context)
    }
    return this
}

/**
 * Extension function on AppResult that logs any error and returns the result unchanged.
 * Useful for adding logging to existing error handling chains.
 *
 * @param analytics The Analytics instance to use for logging
 * @param context A descriptive message about where the error occurred
 * @return The same Result for chaining
 */
fun <T> AppResult<T>.logOnFailure(analytics: Analytics, context: String): AppResult<T> {
    return onFailure { error ->
        error.log(analytics, context)
    }
}
