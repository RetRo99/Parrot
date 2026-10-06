package com.retro99.home.ui.navigation

/** Root back behavior is independent of popping screens inside a tab. */
enum class HomeRootBackPolicy {
    StayInTab,
    ReturnToStartTab,
}

internal expect fun platformHomeRootBackPolicy(): HomeRootBackPolicy
