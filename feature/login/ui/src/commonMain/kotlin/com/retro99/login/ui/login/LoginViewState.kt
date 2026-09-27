package com.retro99.login.ui.login

import com.retro99.base.server.ServerType

data class LoginViewState(
    val isLoading: Boolean = false,
    val isOAuthInProgress: Boolean = false,
    val isSignInEnabled: Boolean = false,
    val isOAuthSignInEnabled: Boolean = false,
    val serverConfigurationUnavailable: Boolean = false,
    val selectedServerType: ServerType = ServerType.Storyteller,
    val urlError: LoginFieldError? = null,
    val loginError: String? = null,
    val usernameError: LoginFieldError? = null,
    val passwordError: LoginFieldError? = null,
) {
    val isOAuthVisible: Boolean
        get() = selectedServerType == ServerType.Storyteller
}

enum class LoginFieldError {
    InvalidUrl,
    Required,
}
