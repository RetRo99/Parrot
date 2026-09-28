package com.retro99.settings.ui.servers

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.server.api.ServerType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal suspend fun runServerManagementOperation(
    analytics: Analytics,
    operation: ServerManagementAnalyticsEvent.Operation,
    serverType: ServerType,
    isRetry: Boolean,
    beforeMutation: suspend (DiagnosticContext) -> Unit = {},
    mutate: suspend () -> Unit,
): Boolean {
    val correlationId = Uuid.random().toString()
    fun context(stage: String, outcome: String, reasonCode: String? = null) = DiagnosticContext(
        screen = "server_management",
        action = operation.action,
        operation = operation.operation,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        serverType = serverType.identifier,
        correlationId = correlationId,
    )

    analytics.logEvent(
        ServerManagementAnalyticsEvent.OperationAttempted(
            operation = operation,
            serverType = serverType.identifier,
            isRetry = isRetry,
        ),
    )
    analytics.logBreadcrumb(context(stage = "started", outcome = "started"))

    try {
        beforeMutation(context(stage = "started", outcome = "started"))
        mutate()
    } catch (cancellation: CancellationException) {
        analytics.logEvent(
            ServerManagementAnalyticsEvent.OperationCancelled(
                operation = operation,
                serverType = serverType.identifier,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(context(stage = "terminal", outcome = "cancelled", reasonCode = "operation_cancelled"))
        throw cancellation
    } catch (error: Exception) {
        val failureContext = context(
            stage = "terminal",
            outcome = "failed",
            reasonCode = operation.failureReasonCode,
        )
        analytics.logEvent(
            ServerManagementAnalyticsEvent.OperationFailed(
                operation = operation,
                serverType = serverType.identifier,
                isRetry = isRetry,
            ),
        )
        analytics.logBreadcrumb(failureContext)
        analytics.logException(error, failureContext)
        return false
    }

    when (operation) {
        ServerManagementAnalyticsEvent.Operation.Logout ->
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerLoggedOut(
                    serverType = serverType.identifier,
                    isRetry = isRetry,
                ),
            )

        ServerManagementAnalyticsEvent.Operation.Remove ->
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerRemoved(
                    serverType = serverType.identifier,
                    isRetry = isRetry,
                ),
            )
    }
    analytics.logBreadcrumb(context(stage = "terminal", outcome = "succeeded"))
    return true
}
