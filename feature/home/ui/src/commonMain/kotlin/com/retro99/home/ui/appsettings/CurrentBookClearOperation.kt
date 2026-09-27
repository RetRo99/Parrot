package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun executeCurrentBookClear(
    analytics: Analytics,
    isRetry: Boolean,
    clear: () -> Unit,
): Boolean {
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "app_settings",
        action = "clear_current_book",
        operation = "clear_current_book",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(AppSettingsAnalyticsEvent.CurrentBookClearAttempted(isRetry))
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        clear()
    } catch (cancellation: CancellationException) {
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        val failureContext = context(
            stage = "terminal",
            outcome = "failed",
            reasonCode = "current_book_clear_failed",
        )
        analytics.logEvent(AppSettingsAnalyticsEvent.CurrentBookClearFailed(isRetry))
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    analytics.logEvent(AppSettingsAnalyticsEvent.CurrentBookCleared(isRetry))
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
