package com.retro99.parrot.navigation

import com.retro99.base.ui.BaseIntent

sealed interface RootNavigationIntent : BaseIntent {
    data object OnLoginSuccess : RootNavigationIntent
    data object OnGuestModeSelected : RootNavigationIntent
    data class OnHomeVisible(val entryId: Long) : RootNavigationIntent
    data object OnLogout : RootNavigationIntent
    data class OnLoginClicked(val existingServerId: String? = null) : RootNavigationIntent
    data object OnExistingServerLoginSuccess : RootNavigationIntent
    data class OnExistingServerLoginAttemptStarted(
        val serverId: String,
        val serverType: String,
        val correlationId: String,
    ) : RootNavigationIntent
    data class OnExistingServerLoginFailed(
        val serverId: String,
        val serverType: String,
        val correlationId: String,
    ) : RootNavigationIntent
    data object OnBackFromLogin : RootNavigationIntent
}
