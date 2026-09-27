package com.retro99.login.ui.login

import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseIntent

sealed interface LoginIntent : BaseIntent {
    data object OnSignInClicked : LoginIntent
    data object OnOAuthSignInClicked : LoginIntent
    data object OnBackClicked : LoginIntent
    data object OnServerTypePickerOpened : LoginIntent
    data class OnServerTypePickerDismissed(val reason: ServerTypePickerDismissalReason) : LoginIntent
    data class OnServerTypeSelected(val serverType: ServerType) : LoginIntent
}

enum class ServerTypePickerDismissalReason(val reasonCode: String) {
    AnchorToggle("anchor_toggle"),
    DismissRequest("dismiss_request"),
}
