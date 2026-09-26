package com.retro99.login.ui.welcome

internal enum class WelcomeBuildBadgeVariant {
    Debug,
    Release,
}

internal fun welcomeBuildBadgeVariant(isDebug: Boolean): WelcomeBuildBadgeVariant =
    if (isDebug) WelcomeBuildBadgeVariant.Debug else WelcomeBuildBadgeVariant.Release
