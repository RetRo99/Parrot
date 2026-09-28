package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class LogShareOutcome {
    NoLogs,
    Opened,
    Failed,
}

@OptIn(ExperimentalUuidApi::class)
internal fun executeLogShare(
    analytics: Analytics,
    isRetry: Boolean,
    readLogContents: () -> String,
    launchShareSheet: () -> Unit,
): LogShareOutcome {
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "app_settings",
        action = "share_logs",
        operation = "share_logs",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(AppSettingsAnalyticsEvent.LogShareAttempted(isRetry))
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    fun failed(error: Exception, reasonCode: String): LogShareOutcome {
        val failureContext = context(stage = "terminal", outcome = "failed", reasonCode = reasonCode)
        analytics.logEvent(AppSettingsAnalyticsEvent.LogShareFailed(reasonCode, isRetry))
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return LogShareOutcome.Failed
    }

    val contents = try {
        readLogContents()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(AppSettingsAnalyticsEvent.LogShareCancelled(isRetry))
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        return failed(error, "log_read_failed")
    }
    if (contents.isEmpty()) {
        analytics.logEvent(AppSettingsAnalyticsEvent.LogShareUnavailable("no_logs", isRetry))
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "unavailable", reasonCode = "no_logs"))
        return LogShareOutcome.NoLogs
    }

    try {
        launchShareSheet()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(AppSettingsAnalyticsEvent.LogShareCancelled(isRetry))
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        return failed(error, "share_launch_failed")
    }

    analytics.logEvent(AppSettingsAnalyticsEvent.LogShareSheetLaunchSucceeded(isRetry))
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return LogShareOutcome.Opened
}
