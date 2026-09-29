package com.retro99.cloudaccount.ui

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Emits one usage event and a correlated diagnostic breadcrumb for each actual form-mode change. */
internal class CloudAccountModeTelemetry(
    private val analytics: Analytics,
) {
    @OptIn(ExperimentalUuidApi::class)
    fun onModeChanged(mode: String) {
        val correlationId = Uuid.random().toString()
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "sync_and_backup",
                entryPoint = "form_mode_switch",
                action = "change_account_form_mode",
                operation = "cloud_account_mode_change",
                stage = "started",
                outcome = "started",
                correlationId = correlationId,
            ),
        )
        analytics.logEvent(CloudAccountAnalyticsEvent.ModeChanged(mode))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "sync_and_backup",
                entryPoint = "form_mode_switch",
                action = "change_account_form_mode",
                operation = "cloud_account_mode_change",
                stage = "terminal",
                outcome = "succeeded",
                correlationId = correlationId,
            ),
        )
    }
}
