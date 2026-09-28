package com.retro99.home.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun executeBubblePositionSave(
    analytics: Analytics,
    side: BubbleSide,
    isRetry: Boolean,
    persist: () -> Unit,
): Boolean {
    val bubbleSide = side.name.lowercase()
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "home",
        entryPoint = "floating_bubble",
        action = "move_continue_reading_bubble",
        operation = "bubble_position_save",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(
        NavigationAnalyticsEvent.BubblePositionSaveAttempted(
            bubbleSide = bubbleSide,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        persist()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(
            NavigationAnalyticsEvent.BubblePositionSaveCompleted(
                bubbleSide = bubbleSide,
                outcome = NavigationAnalyticsEvent.BubblePositionSaveOutcome.Cancelled,
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
            NavigationAnalyticsEvent.BubblePositionSaveCompleted(
                bubbleSide = bubbleSide,
                outcome = NavigationAnalyticsEvent.BubblePositionSaveOutcome.Failed,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    analytics.logEvent(
        NavigationAnalyticsEvent.BubblePositionSaveCompleted(
            bubbleSide = bubbleSide,
            outcome = NavigationAnalyticsEvent.BubblePositionSaveOutcome.Succeeded,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
