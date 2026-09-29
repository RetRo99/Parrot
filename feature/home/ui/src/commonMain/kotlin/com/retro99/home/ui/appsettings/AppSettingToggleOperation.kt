package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun executeAppSettingToggle(
    analytics: Analytics,
    setting: AppSettingsAnalyticsEvent.SettingToggle,
    isEnabled: Boolean,
    isRetry: Boolean,
    screen: String = "app_settings",
    persist: () -> Unit,
    successEvent: (isRetry: Boolean) -> AnalyticsEvent,
): Boolean {
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = screen,
        action = "toggle_setting",
        operation = setting.operation,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(
        AppSettingsAnalyticsEvent.SettingToggleAttempted(
            setting = setting,
            isEnabled = isEnabled,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        persist()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(
            AppSettingsAnalyticsEvent.SettingToggleCancelled(
                setting = setting,
                isEnabled = isEnabled,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        val failureContext = context(
            stage = "terminal",
            outcome = "failed",
            reasonCode = "preference_write_failed",
        )
        analytics.logEvent(
            AppSettingsAnalyticsEvent.SettingToggleFailed(
                setting = setting,
                isEnabled = isEnabled,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    analytics.logEvent(successEvent(isRetry))
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
