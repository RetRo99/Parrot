package com.retro99.cloudaccount.ui

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Reports non-empty email validation results on focus loss, never individual keystrokes or values. */
internal class CloudAccountEmailValidationTelemetry(
    private val analytics: Analytics,
) {
    private var lastReportedIsValid: Boolean? = null
    private var activeFailureCorrelationId: String? = null

    @OptIn(ExperimentalUuidApi::class)
    fun onValidationRequested(email: String, isValid: Boolean, mode: String) {
        if (email.isBlank()) {
            lastReportedIsValid = null
            activeFailureCorrelationId = null
            return
        }

        if (lastReportedIsValid == isValid) return

        val correlationId = if (isValid) {
            activeFailureCorrelationId ?: Uuid.random().toString()
        } else {
            Uuid.random().toString()
        }
        lastReportedIsValid = isValid
        activeFailureCorrelationId = if (isValid) null else correlationId
        reportResult(isValid = isValid, mode = mode, correlationId = correlationId)
    }

    private fun reportResult(isValid: Boolean, mode: String, correlationId: String) {
        val outcome = if (isValid) "succeeded" else "failed"
        val reasonCode = if (isValid) null else "invalid_format"
        analytics.logEvent(CloudAccountAnalyticsEvent.EmailValidationResult(isValid, mode))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "sync_and_backup",
                action = "validate_email",
                operation = "cloud_email_validation",
                stage = "validation",
                outcome = outcome,
                reasonCode = reasonCode,
                correlationId = correlationId,
            ),
        )
    }
}
