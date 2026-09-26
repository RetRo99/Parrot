package com.retro99.analytics.implementation

actual fun sanitizeDiagnosticThrowable(throwable: Throwable): Throwable =
    sanitizeDiagnosticThrowable(throwable, mutableListOf(), 0)

private fun sanitizeDiagnosticThrowable(
    throwable: Throwable,
    ancestors: MutableList<Throwable>,
    depth: Int,
): Throwable {
    if (throwable is SanitizedDiagnosticException) return throwable
    if (depth >= MAX_CAUSE_DEPTH || ancestors.any { it === throwable }) {
        return SanitizedDiagnosticException("CauseChainTruncated", null)
    }

    ancestors += throwable
    val safeCause = throwable.cause?.let {
        sanitizeDiagnosticThrowable(it, ancestors, depth + 1)
    }
    ancestors.removeAt(ancestors.lastIndex)

    return SanitizedDiagnosticException(throwable.stableDiagnosticType(), safeCause).apply {
        // Keep actionable original frames while replacing every throwable/message in the chain.
        stackTrace = throwable.stackTrace
    }
}

private const val MAX_CAUSE_DEPTH = 8
