package com.retro99.cloudaccount.ui

import com.retro99.base.ui.BaseIntent

sealed interface CloudAccountIntent : BaseIntent {
    data object OnBackClicked : CloudAccountIntent

    data object OnSubmitClicked : CloudAccountIntent

    data object OnSwitchToSignInClicked : CloudAccountIntent

    data object OnSwitchToCreateAccountClicked : CloudAccountIntent

    data object OnSignOutClicked : CloudAccountIntent

    data object OnSyncClicked : CloudAccountIntent

    data object OnLinkConfirmed : CloudAccountIntent

    data object OnLinkDismissed : CloudAccountIntent
}
