package com.retro99.login.ui.login

import com.retro99.base.server.ServerType

data class LoginViewState(
    val isLoading: Boolean = false,
    val isOAuthInProgress: Boolean = false,
    val isSignInEnabled: Boolean = false,
    val isOAuthSignInEnabled: Boolean = false,
    val isReauthentication: Boolean = false,
    val isServerConfigurationLoading: Boolean = false,
    val isExistingServerUnavailable: Boolean = false,
    val selectedServerType: ServerType = ServerType.Storyteller,
    val urlError: LoginFieldError? = null,
    val loginError: String? = null,
) {
    val isOAuthVisible: Boolean
        get() = selectedServerType == ServerType.Storyteller && !isReauthentication
}

enum class LoginFieldError {
    InvalidUrl,
}
