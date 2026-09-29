package com.retro99.analytics.api

/** Stable, non-identifying Cloud Account operations used by Analytics and diagnostics. */
enum class CloudAccountOperation(
    val action: String,
    val operation: String,
) {
    RestoreSession("restore_session", "cloud_session_restore"),
    Authentication("authenticate_account", "cloud_authentication"),
    ProfileLink("link_profile", "cloud_profile_link"),
    LinkConflictCleanup("cleanup_link_conflict", "cloud_link_conflict_cleanup"),
    StorageUsage("load_storage_usage", "cloud_storage_usage"),
    AutoBackup("set_auto_backup", "cloud_auto_backup"),
    SignOut("sign_out", "cloud_sign_out"),
    DeleteAccount("delete_account", "cloud_account_delete"),
    SyncNow("sync_now", "cloud_sync_now"),
}

enum class CloudAccountConsentKind(val value: String) {
    AccountTerms("account_terms"),
    UploadRights("upload_rights"),
}

enum class CloudAccountObservation(val value: String) {
    AuthState("auth_state"),
    SyncStatus("sync_status"),
}

/** Feature-use and terminal-outcome events. Payload dimensions are deliberately bounded. */
sealed interface CloudAccountAnalyticsEvent : AnalyticsEvent {
    data class OperationAttempted(
        val operation: CloudAccountOperation,
        val entryPoint: String,
        val isRetry: Boolean,
        val authMethod: String? = null,
        val mode: String? = null,
        val isEnabled: Boolean? = null,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_operation_attempted"
        override val parameters: Map<String, Any> = operationParameters(
            operation = operation,
            entryPoint = entryPoint,
            isRetry = isRetry,
            authMethod = authMethod,
            mode = mode,
            isEnabled = isEnabled,
            stage = "started",
            outcome = "started",
        )
    }

    data class OperationSucceeded(
        val operation: CloudAccountOperation,
        val entryPoint: String,
        val isRetry: Boolean,
        val durationMs: Long,
        val authMethod: String? = null,
        val mode: String? = null,
        val isEnabled: Boolean? = null,
        val resultCode: String? = null,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_operation_succeeded"
        override val parameters: Map<String, Any> = operationParameters(
            operation = operation,
            entryPoint = entryPoint,
            isRetry = isRetry,
            authMethod = authMethod,
            mode = mode,
            isEnabled = isEnabled,
            stage = "terminal",
            outcome = "succeeded",
            durationMs = durationMs,
            resultCode = resultCode,
        )
    }

    data class OperationFailed(
        val operation: CloudAccountOperation,
        val entryPoint: String,
        val isRetry: Boolean,
        val reasonCode: String,
        val durationMs: Long,
        val authMethod: String? = null,
        val mode: String? = null,
        val isEnabled: Boolean? = null,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_operation_failed"
        override val parameters: Map<String, Any> = operationParameters(
            operation = operation,
            entryPoint = entryPoint,
            isRetry = isRetry,
            authMethod = authMethod,
            mode = mode,
            isEnabled = isEnabled,
            stage = "terminal",
            outcome = "failed",
            reasonCode = reasonCode,
            durationMs = durationMs,
        )
    }

    data class OperationCancelled(
        val operation: CloudAccountOperation,
        val entryPoint: String,
        val isRetry: Boolean,
        val durationMs: Long,
        val authMethod: String? = null,
        val mode: String? = null,
        val isEnabled: Boolean? = null,
        val reasonCode: String = "operation_cancelled",
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_operation_cancelled"
        override val parameters: Map<String, Any> = operationParameters(
            operation = operation,
            entryPoint = entryPoint,
            isRetry = isRetry,
            authMethod = authMethod,
            mode = mode,
            isEnabled = isEnabled,
            stage = "terminal",
            outcome = "cancelled",
            reasonCode = reasonCode,
            durationMs = durationMs,
        )
    }

    data class ModeChanged(val mode: String) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_mode_changed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "change_account_form_mode",
            "operation" to "cloud_account_mode_change",
            "mode" to mode,
        )
    }

    data class PasswordVisibilityChanged(val isVisible: Boolean) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_password_visibility_changed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "toggle_password_visibility",
            "operation" to "cloud_account_password_visibility",
            "is_visible" to isVisible,
        )
    }

    data class ConsentChanged(
        val kind: CloudAccountConsentKind,
        val isAccepted: Boolean,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_consent_changed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "change_consent",
            "operation" to "cloud_account_consent",
            "consent_kind" to kind.value,
            "is_enabled" to isAccepted,
        )
    }

    data class ConfirmationShown(val operation: String) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_confirmation_shown"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "show_confirmation",
            "operation" to operation,
        )
    }

    data class ConfirmationDismissed(
        val operation: String,
        val entryPoint: String,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_confirmation_dismissed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "dismiss_confirmation",
            "operation" to operation,
            "entry_point" to entryPoint,
            "outcome" to "cancelled",
        )
    }

    data class ConfirmationConfirmed(
        val operation: String,
        val entryPoint: String,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_confirmation_confirmed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "confirm_operation",
            "operation" to operation,
            "entry_point" to entryPoint,
            "outcome" to "accepted",
        )
    }

    data class ObservationFailed(
        val observation: CloudAccountObservation,
        val reasonCode: String,
    ) : CloudAccountAnalyticsEvent {
        override val name: String = "cloud_account_observation_failed"
        override val parameters: Map<String, Any> = mapOf(
            "screen" to "sync_and_backup",
            "action" to "observe_state",
            "operation" to "cloud_account_observation",
            "observation" to observation.value,
            "stage" to "terminal",
            "outcome" to "failed",
            "reason_code" to reasonCode,
        )
    }
}

private fun operationParameters(
    operation: CloudAccountOperation,
    entryPoint: String,
    isRetry: Boolean,
    authMethod: String?,
    mode: String?,
    isEnabled: Boolean?,
    stage: String,
    outcome: String,
    reasonCode: String? = null,
    durationMs: Long? = null,
    resultCode: String? = null,
): Map<String, Any> = buildMap {
    put("screen", "sync_and_backup")
    put("action", operation.action)
    put("operation", operation.operation)
    put("entry_point", entryPoint)
    put("stage", stage)
    put("outcome", outcome)
    put("is_retry", isRetry)
    authMethod?.let { put("auth_method", it) }
    mode?.let { put("mode", it) }
    isEnabled?.let { put("is_enabled", it) }
    reasonCode?.let { put("reason_code", it) }
    durationMs?.let { put("duration_ms", it.coerceAtLeast(0L)) }
    resultCode?.let { put("result_code", it) }
}
