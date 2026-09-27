package com.retro99.login.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.login.domain.usecase.SkipLoginUseCase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginNavigationViewModelTest {

    @Test
    fun guestPreferenceFailureStaysOnWelcomeReportsFailureAndCanRetry() {
        val preferences = RecordingPreferences(failGuestPreferenceWrites = true)
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(preferences, analytics)

        viewModel.onIntent(LoginNavigationIntent.OnSkipLoginClicked)

        assertFalse(viewModel.currentViewState().skipLoginComplete)
        assertTrue(viewModel.currentViewState().guestModeError)
        assertEquals(
            listOf("welcome_action_attempted", "welcome_action_completed"),
            analytics.events.map { it.name },
        )
        assertEquals("failed", analytics.events.last().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("persist_preference", analytics.exceptions.single().second.stage)
        assertEquals("failed", analytics.exceptions.single().second.outcome)
        assertEquals(
            "guest_mode_persistence_failed",
            analytics.exceptions.single().second.reasonCode,
        )
        assertTrue(analytics.breadcrumbs.any { it.stage == "started" })
        assertTrue(analytics.breadcrumbs.any { it.stage == "persist_preference" && it.outcome == "failed" })
        assertFalse(analytics.breadcrumbs.any { it.stage == "preference_persisted" })

        preferences.failGuestPreferenceWrites = false
        viewModel.onIntent(LoginNavigationIntent.OnSkipLoginClicked)

        assertTrue(viewModel.currentViewState().skipLoginComplete)
        assertFalse(viewModel.currentViewState().guestModeError)
        assertEquals(true, preferences.getBoolean(PreferencesKey.SkippedLogin))
        assertEquals(
            listOf("failed", "succeeded"),
            analytics.events.filter { it.name == "welcome_action_completed" }
                .map { it.parameters["outcome"] },
        )
        assertEquals(1, analytics.exceptions.size)
        assertTrue(analytics.breadcrumbs.any { it.stage == "preference_persisted" && it.outcome == "succeeded" })
    }

    @Test
    fun welcomeSystemBackReportsExitOutcomeAndBreadcrumbsOnce() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(RecordingPreferences(), analytics)
        var exitRequests = 0

        viewModel.onWelcomeSystemBack { exitRequests += 1 }

        assertEquals(1, exitRequests)
        assertEquals(1, analytics.events.size)
        assertEquals("navigation_back", analytics.events.single().name)
        assertEquals(
            mapOf(
                "screen" to "welcome",
                "source_screen" to "welcome",
                "destination_screen" to "app_exit",
                "entry_point" to "system_back",
                "outcome" to "exited",
            ),
            analytics.events.single().parameters,
        )
        assertEquals(
            listOf("started" to "started", "exit_requested" to "succeeded"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
        assertTrue(analytics.exceptions.isEmpty())
    }

    @Test
    fun welcomeSystemBackReportsUnexpectedExitCallbackFailure() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(RecordingPreferences(), analytics)

        viewModel.onWelcomeSystemBack { throw IllegalStateException("private callback detail") }

        assertEquals(1, analytics.events.size)
        assertEquals("failed", analytics.events.single().parameters["outcome"])
        assertEquals(1, analytics.exceptions.size)
        assertEquals("app_exit_failed", analytics.exceptions.single().second.reasonCode)
        assertEquals(
            listOf("started" to "started", "request_exit" to "failed"),
            analytics.breadcrumbs.map { it.stage to it.outcome },
        )
    }

    @Test
    fun compactWelcomeLayoutLogsOneRecoveryBreadcrumb() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(RecordingPreferences(), analytics)

        viewModel.onWelcomeCompactLayoutAvailable()
        viewModel.onWelcomeCompactLayoutAvailable()

        assertEquals(1, analytics.breadcrumbs.size)
        assertEquals("welcome", analytics.breadcrumbs.single().screen)
        assertEquals("layout_adaptation", analytics.breadcrumbs.single().action)
        assertEquals("welcome_layout", analytics.breadcrumbs.single().operation)
        assertEquals("compact_viewport", analytics.breadcrumbs.single().stage)
        assertEquals("succeeded", analytics.breadcrumbs.single().outcome)
        assertEquals("scroll_enabled", analytics.breadcrumbs.single().reasonCode)
    }

    @Test
    fun repeatedDestinationVisibilityDoesNotDuplicateExposureOrBreadcrumb() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(RecordingPreferences(), analytics)

        viewModel.onDestinationVisible(LoginDestination.Welcome, source = null)
        viewModel.onDestinationVisible(LoginDestination.Welcome, source = null)

        assertEquals(
            1,
            analytics.events.count { it.name == "welcome_screen_viewed" },
        )
        assertEquals(
            1,
            analytics.breadcrumbs.count { it.action == "screen_view" },
        )
    }

    @Test
    fun returningToWelcomeUsesPreviousDestinationWhenBackStackHasNoSource() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(RecordingPreferences(), analytics)

        viewModel.onDestinationVisible(LoginDestination.Welcome, source = null)
        viewModel.onDestinationVisible(LoginDestination.Login, source = LoginDestination.Welcome)
        // After popping Login, the current back stack is only [Welcome], so its source is null.
        viewModel.onDestinationVisible(LoginDestination.Welcome, source = null)

        assertEquals(
            listOf("welcome_screen_viewed", "login_screen_viewed", "welcome_screen_viewed"),
            analytics.events.filter {
                it.name == "welcome_screen_viewed" || it.name == "login_screen_viewed"
            }.map { it.name },
        )
        assertEquals(
            3,
            analytics.breadcrumbs.count { it.action == "screen_view" },
        )
        assertEquals(
            1,
            analytics.events.count { it.name == "welcome_action_completed" },
        )

        val welcomeViews = analytics.events.filter { it.name == "welcome_screen_viewed" }
        assertEquals("splash", welcomeViews.first().parameters["source_screen"])
        assertEquals("app_launch", welcomeViews.first().parameters["entry_point"])
        assertEquals("login", welcomeViews.last().parameters["source_screen"])
        assertEquals("back_navigation", welcomeViews.last().parameters["entry_point"])

        val returnedWelcomeBreadcrumb = analytics.breadcrumbs.last { it.screen == "welcome" }
        assertEquals("login", returnedWelcomeBreadcrumb.sourceScreen)
        assertEquals("back_navigation", returnedWelcomeBreadcrumb.entryPoint)
    }

    @Test
    fun backIntentPreservesWelcomeAsTheOnlyDestination() {
        val viewModel = createViewModel(RecordingPreferences(), RecordingAnalytics())

        viewModel.onIntent(LoginNavigationIntent.OnBackClicked)

        assertEquals(listOf(LoginDestination.Welcome), viewModel.currentViewState().backStack)
    }

    @Test
    fun backIntentPopsLoginAndReturnsToWelcome() {
        val viewModel = createViewModel(RecordingPreferences(), RecordingAnalytics())
        viewModel.onIntent(LoginNavigationIntent.NavigateTo(LoginDestination.Login))

        viewModel.onIntent(LoginNavigationIntent.OnBackClicked)

        assertEquals(listOf(LoginDestination.Welcome), viewModel.currentViewState().backStack)
    }

    @Test
    fun existingServerLoginExposureUsesServerManagementAttribution() {
        val analytics = RecordingAnalytics()
        val viewModel = createViewModel(
            preferences = RecordingPreferences(),
            analytics = analytics,
            startAtLogin = true,
            isExistingServerLogin = true,
        )

        viewModel.onDestinationVisible(LoginDestination.Login, source = null)

        val viewed = analytics.events.single()
        assertEquals("login_screen_viewed", viewed.name)
        assertEquals("server_management", viewed.parameters["source_screen"])
        assertEquals("server_card_login", viewed.parameters["entry_point"])
        assertEquals("server_management", analytics.breadcrumbs.single().sourceScreen)
        assertEquals("server_card_login", analytics.breadcrumbs.single().entryPoint)
    }

    private fun createViewModel(
        preferences: RecordingPreferences,
        analytics: RecordingAnalytics,
        startAtLogin: Boolean = false,
        isExistingServerLogin: Boolean = false,
    ) = LoginNavigationViewModel(
        startAtLogin = startAtLogin,
        buildConfig = object : BuildConfig {
            override val isDebug: Boolean = true
            override val versionName: String = "test"
            override val versionCode: Int = 1
        },
        skipLoginUseCase = SkipLoginUseCase(preferences),
        analytics = analytics,
        isExistingServerLogin = isExistingServerLogin,
    )

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<Pair<Throwable, DiagnosticContext>>()

        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += throwable to context
        }
        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
        }
        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }
        override fun setUserId(userId: String?) = Unit
    }

    private class RecordingPreferences(
        var failGuestPreferenceWrites: Boolean = false,
    ) : Preferences {
        private val booleans = mutableMapOf<PreferencesKey, Boolean>()

        override fun getStringOrNull(key: PreferencesKey): String? = null
        override fun putString(key: PreferencesKey, value: String) = Unit
        override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(null)
        override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue

        override fun putBoolean(key: PreferencesKey, value: Boolean) {
            if (key == PreferencesKey.SkippedLogin && failGuestPreferenceWrites) {
                throw IllegalStateException("private storage error")
            }
            booleans[key] = value
        }

        override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
            flowOf(getBoolean(key, defaultValue))

        override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue
        override fun putLong(key: PreferencesKey, value: Long) = Unit
        override fun remove(key: PreferencesKey) = Unit
    }
}
