package com.retro99.login.ui.login

import com.retro99.base.server.ServerType

/**
 * What the user typed on the login form, kept outside the login screen's own ViewModel so it
 * survives leaving and re-entering the screen. The password is never stored.
 */
class LoginDraft {
    var serverType: ServerType = ServerType.Storyteller
    var address: String = ""
    var username: String = ""

    fun clear() {
        serverType = ServerType.Storyteller
        address = ""
        username = ""
    }
}
