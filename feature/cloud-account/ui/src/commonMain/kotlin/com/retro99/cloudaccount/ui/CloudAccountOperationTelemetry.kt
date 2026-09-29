package com.retro99.cloudaccount.ui

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.CloudAccountAnalyticsEvent
import com.retro99.analytics.api.CloudAccountOperation
import com.retro99.analytics.api.CloudAccountObservation
import com.retro99.analytics.api.DiagnosticContext
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal sealed interface CloudAccountOperationOutcome {
    data class Succeeded(val resultCode: String? = null) : CloudAccountOperationOutcome
    data class Failed(val reasonCode: String) : CloudAccountOperationOutcome
}

internal sealed interface CloudAccountOperationExecution<out T> {
    data class Returned<T>(
        val value: T,
        val outcome: CloudAccountOperationOutcome,
    ) : CloudAccountOperationExecution<T>

    data class Threw(val error: Exception) : CloudAccountOperationExecution<Nothing>

    data class Cancelled(val reasonCode: String) : CloudAccountOperationExecution<Nothing>
}

/** Emits one attempt and one terminal outcome; correlation IDs stay in diagnostics only. */
internal class CloudAccountOperationTelemetry(
    private val analytics: Analytics,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun <T> execute(
        operation: CloudAccountOperation,
        entryPoint: String,
        isRetry: Boolean,
        authMethod: String? = null,
        mode: String? = null,
        isEnabled: Boolean? = null,
        cancellationReasonCode: String = "operation_cancelled",
        expectedCancellationReasonCode: (Exception) -> String? = { null },
        reportUnexpectedFailure: (Exception) -> Boolean = { false },
        failureReasonCode: (Exception) -> String = { "operation_failed" },
        classify: (T) -> CloudAccountOperationOutcome = { CloudAccountOperationOutcome.Succeeded() },
        block: suspend CloudAccountOperationTrace.() -> T,
    ): CloudAccountOperationExecution<T> {
        val correlationId = Uuid.random().toString()
        val startedAt = TimeSource.Monotonic.markNow()
        val trace = CloudAccountOperationTrace(analytics, operation, correlationId)
        analytics.logEvent(
            CloudAccountAnalyticsEvent.OperationAttempted(
                operation = operation,
                entryPoint = entryPoint,
                isRetry = isRetry,
                authMethod = authMethod,
                mode = mode,
                isEnabled = isEnabled,
            ),
        )
        trace.record(stage = "started", outcome = "started", entryPoint = entryPoint)

        val value = try {
            block(trace)
        } catch (cancellation: CancellationException) {
            analytics.logEvent(
                CloudAccountAnalyticsEvent.OperationCancelled(
                    operation = operation,
                    entryPoint = entryPoint,
                    isRetry = isRetry,
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    authMethod = authMethod,
                    mode = mode,
                    isEnabled = isEnabled,
                    reasonCode = cancellationReasonCode,
                ),
            )
            trace.record(
                stage = "terminal",
                outcome = "cancelled",
                entryPoint = entryPoint,
                reasonCode = cancellationReasonCode,
            )
            throw cancellation
        } catch (error: Exception) {
            val cancellationReason = expectedCancellationReasonCode(error)
            if (cancellationReason != null) {
                analytics.logEvent(
                    CloudAccountAnalyticsEvent.OperationCancelled(
                        operation = operation,
                        entryPoint = entryPoint,
                        isRetry = isRetry,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                        authMethod = authMethod,
                        mode = mode,
                        isEnabled = isEnabled,
                        reasonCode = cancellationReason,
                    ),
                )
                trace.record(
                    stage = "terminal",
                    outcome = "cancelled",
                    entryPoint = entryPoint,
                    reasonCode = cancellationReason,
                )
                return CloudAccountOperationExecution.Cancelled(cancellationReason)
            }
            val reasonCode = failureReasonCode(error)
            val failureContext = trace.context(
                stage = "terminal",
                outcome = "failed",
                entryPoint = entryPoint,
                reasonCode = reasonCode,
            )
            analytics.logEvent(
                CloudAccountAnalyticsEvent.OperationFailed(
                    operation = operation,
                    entryPoint = entryPoint,
                    isRetry = isRetry,
                    reasonCode = reasonCode,
                    durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                    authMethod = authMethod,
                    mode = mode,
                    isEnabled = isEnabled,
                ),
            )
            analytics.logBreadcrumb(failureContext)
            if (reportUnexpectedFailure(error)) {
                analytics.logException(error, failureContext)
            }
            return CloudAccountOperationExecution.Threw(error)
        }

        val outcome = classify(value)
        val durationMs = startedAt.elapsedNow().inWholeMilliseconds
        when (outcome) {
            is CloudAccountOperationOutcome.Succeeded -> {
                analytics.logEvent(
                    CloudAccountAnalyticsEvent.OperationSucceeded(
                        operation = operation,
                        entryPoint = entryPoint,
                        isRetry = isRetry,
                        durationMs = durationMs,
                        authMethod = authMethod,
                        mode = mode,
                        isEnabled = isEnabled,
                        resultCode = outcome.resultCode,
                    ),
                )
                trace.record(
                    stage = "terminal",
                    outcome = "succeeded",
                    entryPoint = entryPoint,
                    reasonCode = outcome.resultCode,
                )
            }
            is CloudAccountOperationOutcome.Failed -> {
                analytics.logEvent(
                    CloudAccountAnalyticsEvent.OperationFailed(
                        operation = operation,
                        entryPoint = entryPoint,
                        isRetry = isRetry,
                        reasonCode = outcome.reasonCode,
                        durationMs = durationMs,
                        authMethod = authMethod,
                        mode = mode,
                        isEnabled = isEnabled,
                    ),
                )
                trace.record(
                    stage = "terminal",
                    outcome = "failed",
                    entryPoint = entryPoint,
                    reasonCode = outcome.reasonCode,
                )
            }
        }
        return CloudAccountOperationExecution.Returned(value, outcome)
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun reportCloudAccountObservationFailure(
    analytics: Analytics,
    observation: CloudAccountObservation,
    error: Exception,
) {
    val correlationId = Uuid.random().toString()
    val operation = when (observation) {
        CloudAccountObservation.AuthState -> "cloud_auth_state_observation"
        CloudAccountObservation.SyncStatus -> "cloud_sync_status_observation"
    }
    val action = when (observation) {
        CloudAccountObservation.AuthState -> "observe_auth_state"
        CloudAccountObservation.SyncStatus -> "observe_sync_status"
    }
    val reasonCode = when (observation) {
        CloudAccountObservation.AuthState -> "auth_state_observation_failed"
        CloudAccountObservation.SyncStatus -> "sync_status_observation_failed"
    }
    analytics.logEvent(CloudAccountAnalyticsEvent.ObservationFailed(observation, reasonCode))
    analytics.logBreadcrumb(
        DiagnosticContext(
            screen = "sync_and_backup",
            action = action,
            operation = operation,
            stage = "observation",
            outcome = "started",
            correlationId = correlationId,
        ),
    )
    val failureContext = DiagnosticContext(
        screen = "sync_and_backup",
        action = action,
        operation = operation,
        stage = "observation",
        outcome = "failed",
        reasonCode = reasonCode,
        correlationId = correlationId,
    )
    analytics.logBreadcrumb(failureContext)
    analytics.logException(error, failureContext)
}

internal class CloudAccountOperationTrace internal constructor(
    private val analytics: Analytics,
    private val operation: CloudAccountOperation,
    private val correlationId: String,
) {
    fun stage(stage: String, outcome: String = "started", reasonCode: String? = null) {
        record(stage = stage, outcome = outcome, reasonCode = reasonCode)
    }

    internal fun record(
        stage: String,
        outcome: String,
        entryPoint: String? = null,
        reasonCode: String? = null,
    ) {
        analytics.logBreadcrumb(context(stage, outcome, entryPoint, reasonCode))
    }

    internal fun context(
        stage: String,
        outcome: String,
        entryPoint: String? = null,
        reasonCode: String? = null,
    ): DiagnosticContext = DiagnosticContext(
        screen = "sync_and_backup",
        entryPoint = entryPoint,
        action = operation.action,
        operation = operation.operation,
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = correlationId,
    )
}

/** A user retry means a prior accepted attempt failed; cancellation alone is not a retry. */
internal class CloudAccountRetryTracker {
    private val failedOperations = mutableSetOf<CloudAccountOperation>()

    fun isRetry(operation: CloudAccountOperation): Boolean = operation in failedOperations

    fun record(operation: CloudAccountOperation, outcome: CloudAccountOperationOutcome) {
        when (outcome) {
            is CloudAccountOperationOutcome.Failed -> failedOperations += operation
            is CloudAccountOperationOutcome.Succeeded -> failedOperations -= operation
        }
    }

    fun recordFailure(operation: CloudAccountOperation) {
        failedOperations += operation
    }

    fun recordSuccess(operation: CloudAccountOperation) {
        failedOperations -= operation
    }
}
