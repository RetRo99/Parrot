package com.retro99.parrot.navigation

import com.retro99.base.ui.BaseIntent

sealed interface RootNavigationIntent : BaseIntent {
    data class OnLoginSuccess(val existingServerId: String? = null) : RootNavigationIntent
    data object OnLogout : RootNavigationIntent
    data class OnLoginClicked(val existingServerId: String? = null) : RootNavigationIntent
    data object OnBackFromLogin : RootNavigationIntent
}
