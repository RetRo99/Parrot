package com.retro99.parrot

import com.retro99.cloud.implementation.CloudOAuthCallbackRegistry

object CloudOAuthCallbackBridge {
    val shared = this

    fun handleRedirect(uri: String): Boolean {
        return CloudOAuthCallbackRegistry.handleRedirect(uri)
    }

    fun cancelPending(message: String): Boolean {
        return CloudOAuthCallbackRegistry.cancelPending(message)
    }
}
