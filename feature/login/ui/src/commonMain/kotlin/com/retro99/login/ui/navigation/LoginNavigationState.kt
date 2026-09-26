package com.retro99.login.ui.navigation

import com.retro99.base.buildconfig.BuildConfig

data class LoginNavigationState(
    val backStack: List<LoginDestination> = listOf(LoginDestination.Welcome),
    val isDebug: Boolean = false,
    val skipLoginComplete: Boolean = false,
)

internal fun initialLoginNavigationState(
    startAtLogin: Boolean,
    buildConfig: BuildConfig,
): LoginNavigationState = LoginNavigationState(
    backStack = if (startAtLogin) {
        listOf(LoginDestination.Login)
    } else {
        listOf(LoginDestination.Welcome)
    },
    isDebug = buildConfig.isDebug,
)
