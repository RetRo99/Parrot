package com.retro99.login.ui.login

import com.retro99.base.server.ServerType

data class LoginViewState(
    val isLoading: Boolean = false,
    val isOAuthInProgress: Boolean = false,
    val isSignInEnabled: Boolean = false,
    val isOAuthSignInEnabled: Boolean = false,
    val serverConfigurationUnavailable: Boolean = false,
    val selectedServerType: ServerType = ServerType.Storyteller,
    val addressCheck: AddressCheck = AddressCheck.Idle,
    /** Set when a sign-in attempt itself could not reach the server. */
    val unreachableOnSignIn: String? = null,
    val urlError: LoginFieldError? = null,
    val loginError: String? = null,
    val credentialsRejected: Boolean = false,
    val focusRequest: LoginFocusRequest? = null,
    val usernameError: LoginFieldError? = null,
    val passwordError: LoginFieldError? = null,
) {
    /** Only after a successful address check that reported browser sign-in support. */
    val isOAuthVisible: Boolean
        get() = (addressCheck as? AddressCheck.Found)?.supportsBrowserSignIn == true
}

enum class LoginFieldError {
    InvalidUrl,
    Required,
}

/** What the server address check found. [host] is the address shown to the user. */
sealed interface AddressCheck {
    data object Idle : AddressCheck

    data object Checking : AddressCheck

    /** [switched] is true when the check changed the selected server type. */
    data class Found(
        val serverType: ServerType,
        val host: String,
        val switched: Boolean,
        val supportsBrowserSignIn: Boolean,
        val isInsecure: Boolean = false,
    ) : AddressCheck

    data class Unreachable(val host: String) : AddressCheck

    data object NotSupported : AddressCheck

    /** A failed check is only a warning; it never blocks signing in. */
    val isFailure: Boolean
        get() = this is Unreachable || this is NotSupported
}

enum class LoginField {
    Address,
    Username,
    Password,
}

/** One-shot request to move focus; a new [id] triggers again even for the same [field]. */
data class LoginFocusRequest(
    val field: LoginField,
    val id: Int,
)
