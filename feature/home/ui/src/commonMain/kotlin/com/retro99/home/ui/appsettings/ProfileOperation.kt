package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Private retry history scoped to an operation session or target; keys never enter telemetry. */
internal class ProfileOperationRetryTracker {
    private val failedOperationKeys = mutableSetOf<String>()
    private var nextSessionId = 0L

    fun newSessionKey(operation: AppSettingsAnalyticsEvent.ProfileOperation): String =
        "${operation.operation}:session_${++nextSessionId}"

    fun isRetry(key: String): Boolean = key in failedOperationKeys

    fun recordFailure(key: String) {
        failedOperationKeys += key
    }

    fun recordSuccess(key: String) {
        failedOperationKeys -= key
    }

    fun clear(key: String) {
        failedOperationKeys -= key
    }
}

/** Runs one accepted profile mutation with one bounded attempt and terminal diagnostic. */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeProfileOperation(
    analytics: Analytics,
    operation: AppSettingsAnalyticsEvent.ProfileOperation,
    isRetry: Boolean,
    successEvent: (Boolean) -> AnalyticsEvent,
    execute: suspend () -> Unit,
): Boolean {
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "app_settings",
        action = operation.action,
        operation = operation.operation,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(AppSettingsAnalyticsEvent.ProfileOperationAttempted(operation, isRetry))
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        execute()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ProfileOperationCancelled(
                profileOperation = operation,
                entryPoint = "operation_cancellation",
                isRetry = isRetry,
                reasonCode = "operation_cancelled",
            ),
        )
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled"))
        throw cancellation
    } catch (error: Exception) {
        val failureContext = context(
            stage = "terminal",
            outcome = "failed",
            reasonCode = "profile_operation_failed",
        )
        analytics.logEvent(AppSettingsAnalyticsEvent.ProfileOperationFailed(operation, isRetry))
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    analytics.logEvent(successEvent(isRetry))
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
