package com.retro99.login.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.base.ui.BaseViewModel
import com.retro99.login.domain.usecase.SkipLoginUseCase
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class LoginNavigationViewModel(
    private val startAtLogin: Boolean,
    @Provided private val buildConfig: BuildConfig,
    @Provided private val skipLoginUseCase: SkipLoginUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<LoginNavigationState, LoginNavigationIntent>(
    initialLoginNavigationState(startAtLogin, buildConfig),
) {

    fun onDestinationVisible(destination: LoginDestination, source: LoginDestination?) {
        when (destination) {
            LoginDestination.Welcome -> {
                val sourceScreen = if (source == null) "splash" else "login"
                val entryPoint = if (source == null) "app_launch" else "back_navigation"
                analytics.logEvent(AuthAnalyticsEvent.WelcomeViewed(sourceScreen, entryPoint))
                analytics.logBreadcrumb(
                    DiagnosticContext(
                        screen = "welcome",
                        sourceScreen = sourceScreen,
                        entryPoint = entryPoint,
                        action = "screen_view",
                        operation = "onboarding_route",
                        stage = "visible",
                        outcome = "succeeded",
                    ),
                )
            }

            LoginDestination.Login -> {
                val sourceScreen = when {
                    source == LoginDestination.Welcome -> "welcome"
                    startAtLogin -> "home"
                    else -> "login_navigation"
                }
                val entryPoint = when {
                    source == LoginDestination.Welcome -> "get_started"
                    startAtLogin -> "add_server"
                    else -> "navigation"
                }
                if (source == LoginDestination.Welcome) {
                    analytics.logEvent(
                        AuthAnalyticsEvent.WelcomeActionCompleted(
                            action = "get_started",
                            outcome = "succeeded",
                        ),
                    )
                }
                analytics.logEvent(AuthAnalyticsEvent.LoginViewed(sourceScreen, entryPoint))
                analytics.logBreadcrumb(
                    DiagnosticContext(
                        screen = "login",
                        sourceScreen = sourceScreen,
                        entryPoint = entryPoint,
                        action = "screen_view",
                        operation = "login_route",
                        stage = "visible",
                        outcome = "succeeded",
                    ),
                )
            }
        }
    }

    override fun onIntent(intent: LoginNavigationIntent) {
        when (intent) {
            LoginNavigationIntent.OnBackClicked -> {
                updateState { state ->
                    state.copy(backStack = state.backStack.dropLast(1))
                }
            }

            is LoginNavigationIntent.NavigateTo -> {
                val fromWelcome =
                    viewState.value.backStack.lastOrNull() == LoginDestination.Welcome &&
                        intent.destination == LoginDestination.Login
                if (fromWelcome) {
                    analytics.logEvent(AuthAnalyticsEvent.WelcomeActionAttempted("get_started"))
                    analytics.logBreadcrumb(
                        DiagnosticContext(
                            screen = "welcome",
                            action = "get_started",
                            operation = "onboarding_route",
                            stage = "started",
                            outcome = "started",
                        ),
                    )
                }
                updateState { state ->
                    state.copy(backStack = state.backStack + intent.destination)
                }
            }

            LoginNavigationIntent.OnSkipLoginClicked -> {
                updateState { state -> state.copy(guestModeError = false) }
                analytics.logEvent(AuthAnalyticsEvent.WelcomeActionAttempted("browse_without_account"))
                val startContext = DiagnosticContext(
                    screen = "welcome",
                    action = "browse_without_account",
                    operation = "persist_guest_mode",
                    stage = "started",
                    outcome = "started",
                )
                analytics.logBreadcrumb(startContext)
                try {
                    skipLoginUseCase()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val failureContext = startContext.copy(
                        stage = "persist_preference",
                        outcome = "failed",
                        reasonCode = "guest_mode_persistence_failed",
                    )
                    analytics.logEvent(
                        AuthAnalyticsEvent.WelcomeActionCompleted(
                            action = "browse_without_account",
                            outcome = "failed",
                        ),
                    )
                    analytics.logBreadcrumb(failureContext)
                    analytics.logException(failure, failureContext)
                    updateState { state -> state.copy(guestModeError = true) }
                    return
                }
                analytics.logEvent(
                    AuthAnalyticsEvent.WelcomeActionCompleted(
                        action = "browse_without_account",
                        outcome = "succeeded",
                    ),
                )
                analytics.logBreadcrumb(
                    DiagnosticContext(
                        screen = "welcome",
                        action = "browse_without_account",
                        operation = "persist_guest_mode",
                        stage = "preference_persisted",
                        outcome = "succeeded",
                    ),
                )
                updateState { state ->
                    state.copy(skipLoginComplete = true, guestModeError = false)
                }
            }
        }
    }
}
