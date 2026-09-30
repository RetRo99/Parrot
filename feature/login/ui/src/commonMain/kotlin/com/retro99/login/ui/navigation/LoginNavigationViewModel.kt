package com.retro99.login.ui.navigation

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.buildconfig.BuildConfig
import com.retro99.base.ui.BaseViewModel
import com.retro99.login.domain.usecase.SkipLoginUseCase
import com.retro99.login.ui.login.LoginDraft
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class LoginNavigationViewModel(
    private val startAtLogin: Boolean,
    @Provided private val buildConfig: BuildConfig,
    @Provided private val skipLoginUseCase: SkipLoginUseCase,
    @Provided private val analytics: Analytics,
    private val isExistingServerLogin: Boolean = false,
    private val loginSourceScreen: String? = null,
    private val loginEntryPoint: String? = null,
) : BaseViewModel<LoginNavigationState, LoginNavigationIntent>(
    initialLoginNavigationState(startAtLogin, buildConfig),
) {
    /** Lives as long as the login flow, so the form survives Login being popped and reopened. */
    val loginDraft = LoginDraft()

    private var lastVisibleDestination: LoginDestination? = null
    private var compactWelcomeLayoutBreadcrumbLogged = false

    fun onWelcomeCompactLayoutAvailable() {
        if (compactWelcomeLayoutBreadcrumbLogged) return
        compactWelcomeLayoutBreadcrumbLogged = true
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "welcome",
                action = "layout_adaptation",
                operation = "welcome_layout",
                stage = "compact_viewport",
                outcome = "succeeded",
                reasonCode = "scroll_enabled",
            ),
        )
    }

    private fun resetCompactWelcomeLayoutBreadcrumb() {
        compactWelcomeLayoutBreadcrumbLogged = false
    }

    fun onDestinationVisible(destination: LoginDestination, source: LoginDestination?) {
        if (lastVisibleDestination == destination) return
        val previousVisibleDestination = lastVisibleDestination
        lastVisibleDestination = destination
        val resolvedSource = source ?: previousVisibleDestination

        if (destination != LoginDestination.Welcome) {
            resetCompactWelcomeLayoutBreadcrumb()
        }
        when (destination) {
            LoginDestination.Welcome -> {
                val sourceScreen = if (resolvedSource == null) "splash" else "login"
                val entryPoint = if (resolvedSource == null) "app_launch" else "back_navigation"
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
                val sourceScreen = loginSourceScreen ?: when {
                    isExistingServerLogin -> "server_management"
                    resolvedSource == LoginDestination.Welcome -> "welcome"
                    startAtLogin -> "home"
                    else -> "login_navigation"
                }
                val entryPoint = loginEntryPoint ?: when {
                    isExistingServerLogin -> "server_card_login"
                    resolvedSource == LoginDestination.Welcome -> "get_started"
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

    fun onWelcomeSystemBack(onExit: () -> Unit) {
        val context = DiagnosticContext(
            screen = "welcome",
            sourceScreen = "welcome",
            entryPoint = "system_back",
            action = "system_back",
            operation = "exit_app",
        )
        analytics.logBreadcrumb(context.copy(stage = "started", outcome = "started"))
        try {
            onExit()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val failureContext = context.copy(
                stage = "request_exit",
                outcome = "failed",
                reasonCode = "app_exit_failed",
            )
            analytics.logEvent(
                NavigationAnalyticsEvent.WelcomeRootBackCompleted(
                    NavigationAnalyticsEvent.WelcomeRootBackOutcome.Failed,
                ),
            )
            analytics.logBreadcrumb(failureContext)
            analytics.logException(failure, failureContext)
            return
        }

        analytics.logEvent(
            NavigationAnalyticsEvent.WelcomeRootBackCompleted(
                NavigationAnalyticsEvent.WelcomeRootBackOutcome.Exited,
            ),
        )
        analytics.logBreadcrumb(context.copy(stage = "exit_requested", outcome = "succeeded"))
    }

    override fun onIntent(intent: LoginNavigationIntent) {
        when (intent) {
            LoginNavigationIntent.OnBackClicked -> {
                updateState { state ->
                    if (state.backStack.size > 1) {
                        state.copy(backStack = state.backStack.dropLast(1))
                    } else {
                        state
                    }
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
