package com.retro99.settings.ui.servers

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Collects the server list, measures its first usable value and contains upstream failures. */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun <T> observeServerList(
    analytics: Analytics,
    isRetry: Boolean,
    source: Flow<T>,
    onValue: (T) -> Unit,
    onFailure: (hasLoadedValue: Boolean) -> Unit,
) {
    val correlationId = Uuid.random().toString()
    fun context(
        operation: String,
        action: String,
        stage: String,
        outcome: String,
        reasonCode: String? = null,
    ) = DiagnosticContext(
        screen = "server_management",
        action = action,
        operation = operation,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )

    analytics.logEvent(ServerManagementAnalyticsEvent.ServerListLoadAttempted(isRetry))
    analytics.logBreadcrumb(
        context(
            operation = "server_list_load",
            action = "load_server_list",
            stage = if (isRetry) "retry" else "started",
            outcome = "started",
        ),
    )

    var hasLoadedValue = false
    try {
        source.collect { value ->
            onValue(value)
            if (!hasLoadedValue) {
                hasLoadedValue = true
                analytics.logEvent(
                    ServerManagementAnalyticsEvent.ServerListLoadCompleted(
                        outcome = ServerManagementAnalyticsEvent.ServerListLoadOutcome.Succeeded,
                        isRetry = isRetry,
                    ),
                )
                analytics.logBreadcrumb(
                    context(
                        operation = "server_list_load",
                        action = "load_server_list",
                        stage = "terminal",
                        outcome = "succeeded",
                    ),
                )
            }
        }
        if (!hasLoadedValue) {
            throw IllegalStateException("Server list flow completed without an initial value")
        }
    } catch (cancellation: CancellationException) {
        if (!hasLoadedValue) {
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerListLoadCompleted(
                    outcome = ServerManagementAnalyticsEvent.ServerListLoadOutcome.Cancelled,
                    isRetry = isRetry,
                ),
            )
            analytics.logBreadcrumb(
                context(
                    operation = "server_list_load",
                    action = "load_server_list",
                    stage = "terminal",
                    outcome = "cancelled",
                    reasonCode = "server_list_load_cancelled",
                ),
            )
        }
        throw cancellation
    } catch (error: Exception) {
        val operation = if (hasLoadedValue) "server_list_observation" else "server_list_load"
        val action = if (hasLoadedValue) "observe_server_list" else "load_server_list"
        val reasonCode = if (hasLoadedValue) {
            "server_list_observation_failed"
        } else {
            "server_list_load_failed"
        }
        if (hasLoadedValue) {
            analytics.logEvent(ServerManagementAnalyticsEvent.ServerListObservationFailed(isRetry))
        } else {
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerListLoadCompleted(
                    outcome = ServerManagementAnalyticsEvent.ServerListLoadOutcome.Failed,
                    isRetry = isRetry,
                ),
            )
        }
        val failureContext = context(
            operation = operation,
            action = action,
            stage = "terminal",
            outcome = "failed",
            reasonCode = reasonCode,
        )
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        onFailure(hasLoadedValue)
    }
}
