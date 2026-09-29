package com.retro99.home.ui.appsettings

import com.retro99.base.ui.BaseIntent

sealed interface DiagnosticsIntent : BaseIntent {
    data class OnLoggingToggled(val enabled: Boolean) : DiagnosticsIntent
    data class OnLogCrashesOnlyToggled(val enabled: Boolean) : DiagnosticsIntent
    data object OnShareLogsClicked : DiagnosticsIntent
    data object OnShareLogsFailedMessageShown : DiagnosticsIntent
    data object OnClearLogsClicked : DiagnosticsIntent
    data object OnClearLogsConfirmed : DiagnosticsIntent
    data object OnClearLogsDismissed : DiagnosticsIntent
    data object OnLogsClearedMessageShown : DiagnosticsIntent
    data object OnLogsClearFailedMessageShown : DiagnosticsIntent
    data object OnNoLogsMessageShown : DiagnosticsIntent
}
