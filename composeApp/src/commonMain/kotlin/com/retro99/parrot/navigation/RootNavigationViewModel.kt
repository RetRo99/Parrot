package com.retro99.parrot.navigation

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AuthAnalyticsEvent
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
            is RootNavigationIntent.OnLoginSuccess -> {
                handleLoginSuccess(intent.existingServerId)
            }
            RootNavigationIntent.OnLogout -> handleLogout()
            is RootNavigationIntent.OnLoginClicked -> {
                handleLoginClicked(intent.existingServerId)
            }
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

    private fun handleBackFromLogin() {
        updateState { state ->
            state.copy(backStack = state.backStack.dropLast(1))
        }
    }

    private fun checkAuthState() {
        viewModelScope.launch {
            val isLoggedIn = checkAuthStateUseCase()
            val destination = if (isLoggedIn) {
                RootDestination.Home
            } else {
                RootDestination.Login(true)
            }
            updateState { state ->
                state.copy(backStack = listOf(destination))
            }
        }
    }

    private fun handleLoginSuccess(existingServerId: String?) {
        updateState { state ->
            if (existingServerId != null) {
                state.copy(backStack = state.backStack.dropLast(1))
            } else {
                state.copy(backStack = listOf(RootDestination.Home))
            }
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
