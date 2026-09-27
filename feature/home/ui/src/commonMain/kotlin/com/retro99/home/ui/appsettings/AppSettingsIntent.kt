package com.retro99.home.ui.appsettings

import com.retro99.base.ui.BaseIntent

sealed interface AppSettingsIntent : BaseIntent {
    data class OnLoggingToggled(val enabled: Boolean) : AppSettingsIntent
    data class OnLogCrashesOnlyToggled(val enabled: Boolean) : AppSettingsIntent
    data class OnOpenLastBookToggled(val enabled: Boolean) : AppSettingsIntent
    data class OnShowContinueReadingToggled(val enabled: Boolean) : AppSettingsIntent
    data object OnShareLogsClicked : AppSettingsIntent
    data object OnClearLogsClicked : AppSettingsIntent
    data object OnLogsClearedMessageShown : AppSettingsIntent
    data object OnNoLogsMessageShown : AppSettingsIntent
    data object OnClearCurrentBookClicked : AppSettingsIntent
    data object OnCurrentBookClearedMessageShown : AppSettingsIntent
    data object OnCurrentBookClearFailedMessageShown : AppSettingsIntent
    data class OnProfileSelected(val profileId: String) : AppSettingsIntent
    data object OnAddProfileClicked : AppSettingsIntent
    data class OnAddProfileConfirmed(val name: String) : AppSettingsIntent
    data object OnAddProfileDismissed : AppSettingsIntent
    data class OnProfileLongPressed(val profileId: String) : AppSettingsIntent
    data object OnProfileMenuDismissed : AppSettingsIntent
    data object OnRenameProfileClicked : AppSettingsIntent
    data class OnRenameProfileConfirmed(val newName: String) : AppSettingsIntent
    data object OnRenameProfileDismissed : AppSettingsIntent
    data object OnDeleteProfileClicked : AppSettingsIntent
    data object OnDeleteProfileConfirmed : AppSettingsIntent
    data object OnDeleteProfileDismissed : AppSettingsIntent
}
