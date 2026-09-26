package com.retro99.login.ui.welcome

import kotlin.test.Test
import kotlin.test.assertEquals

class WelcomeBuildBadgeVariantTest {

    @Test
    fun debugBuildSelectsDebugBadge() {
        assertEquals(WelcomeBuildBadgeVariant.Debug, welcomeBuildBadgeVariant(isDebug = true))
    }

    @Test
    fun releaseBuildSelectsReleaseBadge() {
        assertEquals(WelcomeBuildBadgeVariant.Release, welcomeBuildBadgeVariant(isDebug = false))
    }
}
