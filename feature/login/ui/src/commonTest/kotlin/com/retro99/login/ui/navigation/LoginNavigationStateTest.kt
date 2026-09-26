package com.retro99.login.ui.navigation

import com.retro99.base.buildconfig.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class LoginNavigationStateTest {

    @Test
    fun initialWelcomeRouteUsesInjectedDebugBuildConfig() {
        val debug = initialLoginNavigationState(
            startAtLogin = false,
            buildConfig = TestBuildConfig(isDebug = true),
        )
        val release = initialLoginNavigationState(
            startAtLogin = false,
            buildConfig = TestBuildConfig(isDebug = false),
        )

        assertEquals(listOf(LoginDestination.Welcome), debug.backStack)
        assertEquals(true, debug.isDebug)
        assertEquals(false, release.isDebug)
    }

    private class TestBuildConfig(
        override val isDebug: Boolean,
    ) : BuildConfig {
        override val versionName: String = "test"
        override val versionCode: Int = 0
    }
}
