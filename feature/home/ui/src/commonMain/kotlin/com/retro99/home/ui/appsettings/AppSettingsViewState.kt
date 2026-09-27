package com.retro99.home.ui.appsettings

import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.user.api.UserProfile

data class AppSettingsViewState(
    val isLoggingEnabled: Boolean = false,
    val logCrashesOnly: Boolean = false,
    val openLastBookOnLaunch: Boolean = false,
    val showContinueReading: Boolean = true,
    val hasCurrentlyReadingBook: Boolean = false,
    val showLogsClearedMessage: Boolean = false,
    val showNoLogsMessage: Boolean = false,
    val showCurrentBookClearedMessage: Boolean = false,
    val showCurrentBookClearFailedMessage: Boolean = false,
    val canRetryCurrentBookClear: Boolean = false,
    val userProfiles: List<UserProfile> = emptyList(),
    val activeProfile: UserProfile? = null,
    val showAddProfileDialog: Boolean = false,
    val selectedProfileForMenu: UserProfile? = null,
    val showRenameProfileDialog: Boolean = false,
    val showDeleteProfileDialog: Boolean = false,
    val showProfileOperationFailedMessage: Boolean = false,
    val showDuplicateProfileNameError: Boolean = false,
    val isProfileOperationInProgress: Boolean = false,
) {
    val canDeleteSelectedProfile: Boolean
        get() = userProfiles.size > 1
}

internal fun AppSettingsViewState.withCurrentlyReading(
    currentlyReading: CurrentlyReadingDomainModel?,
): AppSettingsViewState = copy(hasCurrentlyReadingBook = currentlyReading != null)

internal fun AppSettingsViewState.withCurrentBookClearOutcome(
    succeeded: Boolean,
): AppSettingsViewState = if (succeeded) {
    copy(
        showCurrentBookClearedMessage = true,
        showCurrentBookClearFailedMessage = false,
        hasCurrentlyReadingBook = false,
        canRetryCurrentBookClear = false,
    )
} else {
    copy(
        showCurrentBookClearedMessage = false,
        showCurrentBookClearFailedMessage = true,
        canRetryCurrentBookClear = true,
    )
}
