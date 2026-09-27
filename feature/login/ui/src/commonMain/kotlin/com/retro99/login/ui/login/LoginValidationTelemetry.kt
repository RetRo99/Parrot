package com.retro99.login.ui.login

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.server.ServerType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Reports a bounded event only when URL validation enters an invalid state, not per keystroke. */
internal class LoginValidationTelemetry(
    private val analytics: Analytics,
) {
    private var hasReportedInvalidUrl = false

    fun onUrlValidationChanged(
        error: LoginFieldError?,
        hasValidServerUrl: Boolean,
        serverType: ServerType,
    ) {
        if (hasValidServerUrl) hasReportedInvalidUrl = false
        if (error != LoginFieldError.InvalidUrl || hasReportedInvalidUrl) return
        hasReportedInvalidUrl = true

        report(serverType, field = "server_url", reasonCode = "invalid_url")
    }

    fun onRequiredFieldsMissing(serverType: ServerType) {
        report(serverType, field = "required_fields", reasonCode = "required_fields_missing")
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun report(serverType: ServerType, field: String, reasonCode: String) {
        val correlationId = Uuid.random().toString()
        analytics.logEvent(
            AuthAnalyticsEvent.LoginValidationFailed(
                serverType = serverType.identifier,
                field = field,
                reasonCode = reasonCode,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "login",
                action = "validate_form",
                operation = "login_validation",
                stage = "terminal",
                outcome = "failed",
                reasonCode = reasonCode,
                serverType = serverType.identifier,
                correlationId = correlationId,
            ),
        )
    }
}
