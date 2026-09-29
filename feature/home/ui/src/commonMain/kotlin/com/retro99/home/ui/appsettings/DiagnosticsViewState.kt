package com.retro99.home.ui.appsettings

data class DiagnosticsViewState(
    val isLoggingEnabled: Boolean = false,
    val logCrashesOnly: Boolean = false,
    val showClearLogsConfirmation: Boolean = false,
    val showLogsClearedMessage: Boolean = false,
    val showLogsClearFailedMessage: Boolean = false,
    val canRetryLogsClear: Boolean = false,
    val showNoLogsMessage: Boolean = false,
    val showLogShareFailedMessage: Boolean = false,
    val canRetryLogShare: Boolean = false,
)

internal fun DiagnosticsViewState.withLogsClearOutcome(succeeded: Boolean): DiagnosticsViewState = if (succeeded) {
    copy(
        showClearLogsConfirmation = false,
        showLogsClearedMessage = true,
        showLogsClearFailedMessage = false,
        canRetryLogsClear = false,
    )
} else {
    copy(
        showClearLogsConfirmation = false,
        showLogsClearedMessage = false,
        showLogsClearFailedMessage = true,
        canRetryLogsClear = true,
    )
}
