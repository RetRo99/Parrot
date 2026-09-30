package com.retro99.settings.ui.servers

import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerType
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel

/** What a server card tells the user about the connection. */
internal sealed interface ServerStatus {
    data class Connected(val account: String) : ServerStatus

    data object SignedOut : ServerStatus

    /** A sign-in attempt for this server just failed. */
    data object PasswordRejected : ServerStatus

    /** The stored session can no longer be used and could not be refreshed. */
    data object SessionExpired : ServerStatus

    val isProblem: Boolean
        get() = this is PasswordRejected || this is SessionExpired
}

internal fun ServerWithStatusUiModel.toStatus(loginFailed: Boolean): ServerStatus =
    when (authState) {
        is ServerAuthState.Authenticated -> ServerStatus.Connected(authState.username)
        is ServerAuthState.NotAuthenticated ->
            if (loginFailed) ServerStatus.PasswordRejected else ServerStatus.SignedOut
        is ServerAuthState.TokenExpired,
        is ServerAuthState.AuthenticationFailed,
        -> ServerStatus.SessionExpired
    }

/** Parrot Cloud signs in through its own account screen, so it has no sign-in action here. */
internal fun ServerType.canSignInFromServers(): Boolean =
    this == ServerType.Storyteller || this == ServerType.Audiobookshelf
