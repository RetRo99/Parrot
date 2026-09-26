package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.server.ServerType

/** Reports unexpected login failures exactly once at the UI boundary that owns recovery. */
internal fun reportUnexpectedLoginFailure(
    analytics: Analytics,
    error: AppError,
    serverType: ServerType,
    authMethod: String,
) {
    // Rejections, OAuth cancellation and other AuthError outcomes are ordinary recovery paths.
    if (!error.shouldReportException || error is AppError.AuthError) return

    analytics.logException(
        error.toThrowable(),
        DiagnosticContext(
            screen = "login",
            action = "sign_in",
            operation = "${authMethod}_login",
            stage = "authentication",
            outcome = "failed",
            reasonCode = when (error) {
                is AppError.NetworkError ->
                    if (error.isConnectivity) "network_unavailable" else "network_failure"
                is AppError.ApiError -> "auth_http_error"
                is AppError.DatabaseError -> "local_database_failure"
                is AppError.UnknownError -> "unexpected_failure"
                is AppError.AuthError -> "auth_rejected"
                is AppError.NotFoundError -> "auth_resource_missing"
            },
            serverType = serverType.identifier,
        ),
    )
}
