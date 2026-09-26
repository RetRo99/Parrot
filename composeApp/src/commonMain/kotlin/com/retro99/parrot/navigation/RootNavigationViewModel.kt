package com.retro99.parrot.navigation

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
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

    init {
        checkAuthState()
    }

    override fun onIntent(intent: RootNavigationIntent) {
        when (intent) {
            RootNavigationIntent.OnLoginSuccess -> handleLoginSuccess()
            RootNavigationIntent.OnLogout -> handleLogout()
            RootNavigationIntent.OnLoginClicked -> handleLoginClicked()
            RootNavigationIntent.OnBackFromLogin -> handleBackFromLogin()
        }
    }

    private fun handleLoginClicked() {
        updateState { state ->
            state.copy(backStack = state.backStack + RootDestination.Login(false))
        }
    }

    private fun handleBackFromLogin() {
        updateState { state ->
            state.copy(backStack = state.backStack.dropLast(1))
        }
    }

    private fun checkAuthState() {
        viewModelScope.launch {
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
            updateState { state ->
                state.copy(backStack = listOf(destination))
            }
        }
    }

    private fun handleLoginSuccess() {
        updateState { state ->
            state.copy(backStack = listOf(RootDestination.Home))
        }
    }

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
