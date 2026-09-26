package com.retro99.analytics.implementation

/**
 * A sanitized throwable preserves the exception type/cause structure without retaining any
 * exception message, which may contain request data, file paths, or user-authored content.
 */
internal class SanitizedDiagnosticException(
    val sourceType: String,
    cause: Throwable?,
) : Exception("Handled failure ($sourceType)", cause)

internal expect fun sanitizeDiagnosticThrowable(throwable: Throwable): Throwable

/** Free-form context is intentionally omitted; operation breadcrumbs carry stable context. */
internal fun sanitizeDiagnosticMessage(message: String?): String? =
    message?.let { "Handled exception; free-form context omitted" }

internal fun Throwable.stableDiagnosticType(): String =
    this::class.simpleName
        ?.takeIf { SAFE_EXCEPTION_TYPE.matches(it) }
        ?: "Throwable"

private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z][A-Za-z0-9_\$]{0,79}")
