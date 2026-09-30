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
    data object OnUrlFocusLost : LoginIntent
    data object OnUrlHelpOpenRequested : LoginIntent
    data object OnUrlHelpOpened : LoginIntent
    data class OnUrlHelpDismissed(val reason: UrlHelpDismissalReason) : LoginIntent
    data class OnPasswordVisibilityChanged(val isVisible: Boolean) : LoginIntent
}

enum class ServerTypePickerDismissalReason(val reasonCode: String) {
    AnchorToggle("anchor_toggle"),
    DismissRequest("dismiss_request"),
}

enum class UrlHelpDismissalReason(val reasonCode: String) {
    GotIt("got_it"),
    AnchorToggle("anchor_toggle"),
    DismissRequest("dismiss_request"),
    ScreenExit("screen_exit"),
}
