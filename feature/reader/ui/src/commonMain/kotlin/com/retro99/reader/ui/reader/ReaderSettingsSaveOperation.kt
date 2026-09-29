package com.retro99.reader.ui.reader

import com.github.michaelbull.result.onFailure
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.base.result.log
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persists a Reader-owned settings update and reports returned failures at this caller boundary.
 * The shared data layer suppresses its generic report so SettingsViewModel can attach its own
 * context; this keeps the separate ReaderViewModel caller from silently discarding an [Err].
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun persistReaderSettingsUpdateFromReader(
    currentSettings: ReaderSettingsDomainModel,
    update: (ReaderSettingsDomainModel) -> ReaderSettingsDomainModel,
    saveSettings: suspend (ReaderSettingsDomainModel) -> CompletableResult,
    analytics: Analytics,
): CompletableResult? {
    val updatedSettings = update(currentSettings)
    if (updatedSettings == currentSettings) return null

    return saveSettings(updatedSettings).onFailure { error ->
        val isCancellation = error is AppError.AuthError && error.isCancellation
        val diagnosticContext = DiagnosticContext(
            screen = "reader",
            sourceScreen = "reader",
            entryPoint = "reader_settings_control",
            action = "save_reader_setting",
            operation = "reader_settings_save",
            stage = "terminal",
            outcome = if (isCancellation) "cancelled" else "failed",
            reasonCode = error.toReaderSettingsSaveReasonCode(),
            correlationId = Uuid.random().toString(),
        )
        analytics.logBreadcrumb(diagnosticContext)
        error.log(analytics, diagnosticContext)
    }
}

private fun AppError.toReaderSettingsSaveReasonCode(): String = when (this) {
    is AppError.NetworkError -> when {
        isTimeout -> "timeout"
        isConnectivity -> "connectivity"
        else -> "network_error"
    }
    is AppError.ApiError -> "api_error"
    is AppError.DatabaseError -> "database_error"
    is AppError.UnknownError -> "unknown_error"
    is AppError.AuthError -> if (isCancellation) "cancelled" else "auth_error"
    is AppError.NotFoundError -> "not_found"
}
