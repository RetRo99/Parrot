package com.retro99.parrot.navigation

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.clearUserIdentity
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.auth.domain.usecase.CheckAuthStateUseCase
import com.retro99.auth.domain.usecase.LogoutUseCase
import com.retro99.base.ui.BaseViewModel
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class RootNavigationViewModel(
    private val checkAuthStateUseCase: CheckAuthStateUseCase,
    private val logoutUseCase: LogoutUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<RootNavigationState, RootNavigationIntent>(RootNavigationState()) {

    private var nextHomeEntryId = 0L
    private val homeExposureGate = HomeExposureGate()

    init {
        analytics.clearUserIdentity()
        checkAuthState()
    }

    override fun onIntent(intent: RootNavigationIntent) {
        when (intent) {
            RootNavigationIntent.OnLoginSuccess -> handleLoginSuccess()
            RootNavigationIntent.OnGuestModeSelected -> handleGuestModeSelected()
            is RootNavigationIntent.OnHomeVisible -> handleHomeVisible(intent.entryId)
            RootNavigationIntent.OnLogout -> handleLogout()
            is RootNavigationIntent.OnLoginClicked -> handleLoginClicked(intent.existingServerId)
            RootNavigationIntent.OnExistingServerLoginSuccess -> handleExistingServerLoginSuccess()
            RootNavigationIntent.OnBackFromLogin -> handleBackFromLogin()
        }
    }

    private fun handleLoginClicked(existingServerId: String?) {
        updateState { state ->
            state.copy(
                backStack = state.backStack + RootDestination.Login(
                    initial = false,
                    existingServerId = existingServerId,
                ),
            )
        }
    }

    private fun handleExistingServerLoginSuccess() {
        updateState { state ->
            val backStack = if (
                state.backStack.size > 1 && state.backStack.lastOrNull() is RootDestination.Login
            ) {
                state.backStack.dropLast(1)
            } else {
                listOf(RootDestination.Home)
            }
            state.copy(
                backStack = backStack,
                homeEntry = if (backStack.lastOrNull() == RootDestination.Home) {
                    createHomeEntry(sourceScreen = "login", entryPoint = "existing_server_login_success")
                } else {
                    state.homeEntry
                },
            )
        }
    }

    private fun handleBackFromLogin() {
        updateState { state ->
            val backStack = state.backStack.dropLast(1)
            state.copy(
                backStack = backStack,
                homeEntry = if (backStack.lastOrNull() == RootDestination.Home) {
                    createHomeEntry(sourceScreen = "login", entryPoint = "back_navigation")
                } else {
                    state.homeEntry
                },
            )
        }
    }

    private fun checkAuthState() {
        viewModelScope.launch {
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "splash",
                    action = "resolve_startup_route",
                    operation = "check_auth_state",
                    stage = "started",
                    outcome = "started",
                ),
            )
            val resolution = resolveStartupAuthState(
                checkAuthState = { checkAuthStateUseCase() },
                reportUnexpectedFailure = { failure ->
                    analytics.logException(
                        failure,
                        DiagnosticContext(
                            screen = "splash",
                            action = "resolve_startup_route",
                            operation = "check_auth_state",
                            stage = "read_persisted_state",
                            outcome = "failed",
                            reasonCode = "auth_state_check_failed",
                        ),
                    )
                },
            )
            val destination = if (resolution.isAuthenticated) {
                RootDestination.Home
            } else {
                RootDestination.Login(true)
            }
            analytics.logEvent(
                NavigationAnalyticsEvent.AppLaunchRouteResolved(
                    destination = if (resolution.isAuthenticated) "home" else "welcome",
                    outcome = if (resolution.usedFallback) "fallback" else "success",
                    reasonCode = if (resolution.usedFallback) "auth_state_check_failed" else null,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = if (resolution.isAuthenticated) "home" else "welcome",
                    sourceScreen = "splash",
                    entryPoint = "app_launch",
                    action = "resolve_startup_route",
                    operation = "check_auth_state",
                    stage = "route_selected",
                    outcome = if (resolution.usedFallback) "fallback" else "succeeded",
                    reasonCode = if (resolution.usedFallback) "auth_state_check_failed" else "auth_state_check_completed",
                ),
            )
            updateState { state ->
                state.copy(
                    backStack = listOf(destination),
                    homeEntry = if (resolution.isAuthenticated) {
                        createHomeEntry(sourceScreen = "splash", entryPoint = "app_launch")
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun handleLoginSuccess() {
        updateState { state ->
            state.copy(
                backStack = listOf(RootDestination.Home),
                homeEntry = createHomeEntry(sourceScreen = "login", entryPoint = "login_success"),
            )
        }
    }

    private fun handleGuestModeSelected() {
        updateState { state ->
            state.copy(
                backStack = listOf(RootDestination.Home),
                homeEntry = createHomeEntry(
                    sourceScreen = "welcome",
                    entryPoint = "browse_without_account",
                ),
            )
        }
    }

    private fun handleHomeVisible(entryId: Long) {
        val state = viewState.value
        val entry = state.homeEntry ?: return
        if (state.backStack.lastOrNull() != RootDestination.Home ||
            !homeExposureGate.shouldReport(entryId, entry.id)
        ) {
            return
        }

        analytics.logEvent(
            NavigationAnalyticsEvent.HomeViewed(
                sourceScreen = entry.sourceScreen,
                entryPoint = entry.entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "home",
                sourceScreen = entry.sourceScreen,
                entryPoint = entry.entryPoint,
                action = "screen_view",
                operation = "home_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    private fun createHomeEntry(sourceScreen: String, entryPoint: String): RootHomeEntry =
        RootHomeEntry(
            id = ++nextHomeEntryId,
            sourceScreen = sourceScreen,
            entryPoint = entryPoint,
        )

    private fun handleLogout() {
        analytics.logEvent(AuthAnalyticsEvent.LogoutClicked)
        viewModelScope.launch {
            logoutUseCase.logoutAll()
            analytics.logEvent(AuthAnalyticsEvent.LogoutCompleted)
            analytics.setUserId(null)
            updateState { state ->
                state.copy(backStack = listOf(RootDestination.Login(true)))
            }
        }
    }
}
