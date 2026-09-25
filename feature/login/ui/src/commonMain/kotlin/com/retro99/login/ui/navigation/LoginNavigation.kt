package com.retro99.login.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.retro99.base.ui.BaseScreen
import com.retro99.login.ui.login.LoginScreen
import com.retro99.login.ui.welcome.WelcomeScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LoginNavigation(
    onLoginSuccess: () -> Unit,
    onBack: (() -> Unit)? = null,
    startAtLogin: Boolean = false,
    existingServerId: String? = null,
    modifier: Modifier = Modifier,
    viewModel: LoginNavigationViewModel = koinViewModel { parametersOf(startAtLogin) },
) {
    BaseScreen(viewModel = viewModel) { state, intentDispatcher ->
        LaunchedEffect(state.skipLoginComplete) {
            if (state.skipLoginComplete) {
                onLoginSuccess()
            }
        }

        NavDisplay(
            backStack = state.backStack,
            onBack = {
                if (state.backStack.size <= 1 && onBack != null) {
                    onBack()
                } else {
                    intentDispatcher(LoginNavigationIntent.OnBackClicked)
                }
            },
            modifier = modifier,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LoginDestination.Welcome> {
                    WelcomeScreen(
                        isDebug = state.isDebug,
                        onSignInClick = {
                            intentDispatcher(LoginNavigationIntent.NavigateTo(LoginDestination.Login))
                        },
                        onSkipLoginClick = {
                            intentDispatcher(LoginNavigationIntent.OnSkipLoginClicked)
                        },
                        onBack = onBack,
                    )
                }

                entry<LoginDestination.Login> {
                    LoginScreen(
                        onSignInSuccess = onLoginSuccess,
                        existingServerId = existingServerId,
                        onBackClick = {
                            if (state.backStack.size <= 1 && onBack != null) {
                                onBack()
                            } else {
                                intentDispatcher(LoginNavigationIntent.OnBackClicked)
                            }
                        },
                    )
                }
            }
        )
    }
}
