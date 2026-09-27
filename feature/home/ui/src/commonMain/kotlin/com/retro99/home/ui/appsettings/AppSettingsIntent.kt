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
    data class OnAddProfileDismissed(val entryPoint: String) : AppSettingsIntent
    data object OnProfileNameEdited : AppSettingsIntent
    data class OnProfileLongPressed(val profileId: String, val entryPoint: String) : AppSettingsIntent
    data class OnProfileMenuDismissed(val entryPoint: String) : AppSettingsIntent
    data object OnRenameProfileClicked : AppSettingsIntent
    data class OnRenameProfileConfirmed(val newName: String) : AppSettingsIntent
    data class OnRenameProfileDismissed(val entryPoint: String) : AppSettingsIntent
    data object OnDeleteProfileClicked : AppSettingsIntent
    data object OnDeleteProfileConfirmed : AppSettingsIntent
    data class OnDeleteProfileDismissed(val entryPoint: String) : AppSettingsIntent
    data object OnProfileOperationFailedMessageShown : AppSettingsIntent
}
