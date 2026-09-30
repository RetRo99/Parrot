package com.retro99.parrot.navigation

import kotlinx.serialization.Serializable

sealed interface RootDestination {

    @Serializable
    data object Splash : RootDestination

    @Serializable
    data class Login(
        val initial: Boolean,
        val existingServerId: String? = null,
        val isRetryOrigin: Boolean = false,
        val sourceScreen: String? = null,
        val entryPoint: String? = null,
    ) : RootDestination

    @Serializable
    data object Home : RootDestination

    @Serializable
    data class CloudAccount(val createAccount: Boolean) : RootDestination
}
