package com.retro99.analytics.implementation

actual fun sanitizeDiagnosticThrowable(throwable: Throwable): Throwable {
    if (throwable is SanitizedDiagnosticException) return throwable
    return SanitizedDiagnosticException(
        sourceType = throwable.stableDiagnosticType(),
        cause = throwable.cause?.let(::sanitizeDiagnosticThrowable),
    )
}
