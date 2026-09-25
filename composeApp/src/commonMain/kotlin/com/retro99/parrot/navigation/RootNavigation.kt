package com.retro99.parrot.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.retro99.base.ui.BaseScreen
import com.retro99.home.ui.navigation.HomeNavigation
import com.retro99.login.ui.navigation.LoginNavigation
import com.retro99.parrot.splash.SplashScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RootNavigation(
    modifier: Modifier = Modifier,
    viewModel: RootNavigationViewModel = koinViewModel(),
) {
    BaseScreen(viewModel = viewModel) { state, intentDispatcher ->
        NavDisplay(
            backStack = state.backStack,
            onBack = {
                // Allow back navigation only from non-initial Login (when navigating from settings)
                val currentDestination = state.backStack.lastOrNull()
                if (currentDestination is RootDestination.Login && !currentDestination.initial) {
                    intentDispatcher(RootNavigationIntent.OnBackFromLogin)
                }
                // Otherwise don't allow back navigation from root destinations
                // This prevents going back to Splash or Login after logging in
            },
            modifier = modifier,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<RootDestination.Splash> {
                    SplashScreen()
                }

                entry<RootDestination.Login> { destination ->
                    LoginNavigation(
                        onLoginSuccess = {
                            intentDispatcher(
                                RootNavigationIntent.OnLoginSuccess(destination.existingServerId),
                            )
                        },
                        onBack = if (!destination.initial) {
                            { intentDispatcher(RootNavigationIntent.OnBackFromLogin) }
                        } else {
                            null
                        },
                        startAtLogin = !destination.initial,
                        existingServerId = destination.existingServerId,
                    )
                }

                entry<RootDestination.Home> {
                    HomeNavigation(
                        onNavigateToLogin = { existingServerId ->
                            intentDispatcher(
                                RootNavigationIntent.OnLoginClicked(existingServerId),
                            )
                        },
                    )
                }
            },
        )
    }
}
