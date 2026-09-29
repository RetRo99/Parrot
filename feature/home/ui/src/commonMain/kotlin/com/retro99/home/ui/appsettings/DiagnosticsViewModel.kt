package com.retro99.home.ui.appsettings

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.FileLogger
import com.retro99.base.ui.BaseViewModel
import com.retro99.base.ui.sharing.FileSharer
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/** Logging options and the log-file actions, split out of the main settings screen. */
@KoinViewModel
class DiagnosticsViewModel(
    @Provided private val fileLogger: FileLogger,
    @Provided private val fileSharer: FileSharer,
    @Provided private val preferences: Preferences,
    @Provided private val analytics: Analytics,
) : BaseViewModel<DiagnosticsViewState, DiagnosticsIntent>(DiagnosticsViewState()) {

    private val failedSettingToggleTargets = mutableMapOf<AppSettingsAnalyticsEvent.SettingToggle, Boolean>()

    init {
        preferences.observeBoolean(PreferencesKey.FileLoggingEnabled, defaultValue = false)
            .onEach { enabled -> updateState { it.copy(isLoggingEnabled = enabled) } }
            .launchIn(viewModelScope)
        preferences.observeBoolean(PreferencesKey.FileLoggingCrashesOnly, defaultValue = false)
            .onEach { enabled -> updateState { it.copy(logCrashesOnly = enabled) } }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: DiagnosticsIntent) {
        when (intent) {
            is DiagnosticsIntent.OnLoggingToggled -> setLoggingEnabled(intent.enabled)
            is DiagnosticsIntent.OnLogCrashesOnlyToggled -> setLogCrashesOnly(intent.enabled)
            DiagnosticsIntent.OnShareLogsClicked -> shareLogs()
            DiagnosticsIntent.OnShareLogsFailedMessageShown -> {
                updateState { it.copy(showLogShareFailedMessage = false) }
            }
            DiagnosticsIntent.OnClearLogsClicked -> {
                updateState { it.copy(showClearLogsConfirmation = true) }
            }
            DiagnosticsIntent.OnClearLogsDismissed -> {
                updateState { it.copy(showClearLogsConfirmation = false) }
            }
            DiagnosticsIntent.OnClearLogsConfirmed -> clearLogs()
            DiagnosticsIntent.OnLogsClearedMessageShown -> {
                updateState { it.copy(showLogsClearedMessage = false) }
            }
            DiagnosticsIntent.OnLogsClearFailedMessageShown -> {
                updateState { it.copy(showLogsClearFailedMessage = false) }
            }
            DiagnosticsIntent.OnNoLogsMessageShown -> {
                updateState { it.copy(showNoLogsMessage = false) }
            }
        }
    }

    private fun setLoggingEnabled(enabled: Boolean) {
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.FileLogging,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.FileLoggingEnabled, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.FileLoggingToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(isLoggingEnabled = enabled) }
        }
    }

    private fun setLogCrashesOnly(enabled: Boolean) {
        runAppSettingToggle(
            setting = AppSettingsAnalyticsEvent.SettingToggle.CrashOnlyLogging,
            enabled = enabled,
            persist = { preferences.putBoolean(PreferencesKey.FileLoggingCrashesOnly, enabled) },
            successEvent = { isRetry ->
                AppSettingsAnalyticsEvent.CrashOnlyLoggingToggled(isEnabled = enabled, isRetry = isRetry)
            },
        ) {
            updateState { it.copy(logCrashesOnly = enabled) }
        }
    }

    private fun runAppSettingToggle(
        setting: AppSettingsAnalyticsEvent.SettingToggle,
        enabled: Boolean,
        persist: () -> Unit,
        successEvent: (isRetry: Boolean) -> AnalyticsEvent,
        onSuccess: () -> Unit,
    ) {
        val isRetry = failedSettingToggleTargets[setting] == enabled
        val succeeded = executeAppSettingToggle(
            analytics = analytics,
            setting = setting,
            isEnabled = enabled,
            isRetry = isRetry,
            screen = "diagnostics",
            persist = persist,
            successEvent = successEvent,
        )
        if (succeeded) {
            failedSettingToggleTargets.remove(setting)
            onSuccess()
        } else {
            failedSettingToggleTargets[setting] = enabled
            updateState { it.withAppSettingSaveFailure() }
        }
    }

    private fun shareLogs() {
        val isRetry = viewState.value.canRetryLogShare
        val outcome = executeLogShare(
            analytics = analytics,
            isRetry = isRetry,
            readLogContents = fileLogger::getLogContents,
            launchShareSheet = {
                fileSharer.shareFile(
                    filePath = fileLogger.getLogFilePath(),
                    mimeType = "text/plain",
                    title = "Share App Logs",
                )
            },
        )
        updateState {
            when (outcome) {
                LogShareOutcome.NoLogs -> it.copy(
                    showNoLogsMessage = true,
                    showLogShareFailedMessage = false,
                    canRetryLogShare = false,
                )
                LogShareOutcome.Opened -> it.copy(
                    showLogShareFailedMessage = false,
                    canRetryLogShare = false,
                )
                LogShareOutcome.Failed -> it.copy(
                    showLogShareFailedMessage = true,
                    canRetryLogShare = true,
                )
            }
        }
    }

    private fun clearLogs() {
        val succeeded = executeLogsClear(
            analytics = analytics,
            isRetry = viewState.value.canRetryLogsClear,
            clear = fileLogger::clearLogs,
        )
        updateState { it.withLogsClearOutcome(succeeded) }
    }
}
