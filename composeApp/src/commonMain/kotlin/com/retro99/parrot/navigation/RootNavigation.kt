package com.retro99.parrot.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.retro99.base.ui.BaseScreen
import com.retro99.cloudaccount.ui.CloudAccountScreen
import com.retro99.home.ui.navigation.HomeNavigation
import com.retro99.login.ui.navigation.LoginNavigation
import com.retro99.parrot.splash.SplashScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RootNavigation(
    modifier: Modifier = Modifier,
    onRootWelcomeBack: (() -> Unit)? = null,
    viewModel: RootNavigationViewModel = koinViewModel(),
) {
    BaseScreen(viewModel = viewModel) { state, intentDispatcher ->
        NavDisplay(
            backStack = state.backStack,
            onBack = {
                // Allow back navigation only from non-initial Login (when navigating from settings)
                val currentDestination = state.backStack.lastOrNull()
                if (currentDestination is RootDestination.Login && !currentDestination.initial) {
                    intentDispatcher(RootNavigationIntent.OnBackFromLogin(entryPoint = "system_back"))
                } else if (currentDestination is RootDestination.CloudAccount) {
                    intentDispatcher(RootNavigationIntent.OnCloudAccountBack)
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
                                if (destination.existingServerId != null) {
                                    RootNavigationIntent.OnExistingServerLoginSuccess
                                } else {
                                    RootNavigationIntent.OnLoginSuccess
                                },
                            )
                        },
                        onLoginAttemptStarted = { serverId, serverType, correlationId ->
                            intentDispatcher(
                                RootNavigationIntent.OnExistingServerLoginAttemptStarted(
                                    serverId = serverId,
                                    serverType = serverType,
                                    correlationId = correlationId,
                                ),
                            )
                        },
                        onLoginFailure = { serverId, serverType, correlationId ->
                            intentDispatcher(
                                RootNavigationIntent.OnExistingServerLoginFailed(
                                    serverId = serverId,
                                    serverType = serverType,
                                    correlationId = correlationId,
                                ),
                            )
                        },
                        onGuestModeSelected = {
                            intentDispatcher(RootNavigationIntent.OnGuestModeSelected)
                        },
                        onCloudAccountRequested = { createAccount ->
                            intentDispatcher(RootNavigationIntent.OnCloudAccountRequested(createAccount))
                        },
                        onPhoneFilesSelected = {
                            intentDispatcher(RootNavigationIntent.OnPhoneFilesSelected)
                        },
                        onBack = if (!destination.initial) {
                            { entryPoint ->
                                intentDispatcher(RootNavigationIntent.OnBackFromLogin(entryPoint))
                            }
                        } else {
                            null
                        },
                        onRootBack = if (destination.initial) onRootWelcomeBack else null,
                        startAtLogin = !destination.initial,
                        existingServerId = destination.existingServerId,
                        isRetryOrigin = destination.isRetryOrigin,
                        loginSourceScreen = destination.sourceScreen,
                        loginEntryPoint = destination.entryPoint,
                    )
                }

                entry<RootDestination.Home> {
                    val homeEntry = state.homeEntry
                    val isHomeCurrent = state.backStack.lastOrNull() == RootDestination.Home
                    LaunchedEffect(homeEntry?.id, isHomeCurrent) {
                        if (isHomeCurrent) {
                            homeEntry?.let { entry ->
                                intentDispatcher(RootNavigationIntent.OnHomeVisible(entry.id))
                            }
                        }
                    }
                    HomeNavigation(
                        openPhoneFilesRequestId = homeEntry
                            ?.takeIf { isHomeCurrent && it.openPhoneFilesOnArrival }
                            ?.id,
                        onPhoneFilesRequestConsumed = { requestId ->
                            intentDispatcher(RootNavigationIntent.OnPhoneFilesRequestConsumed(requestId))
                        },
                        onNavigateToLogin = { existingServerId, isRetry ->
                            intentDispatcher(
                                RootNavigationIntent.OnLoginClicked(
                                    existingServerId = existingServerId,
                                    isRetry = isRetry,
                                    sourceScreen = "server_management",
                                    entryPoint = if (existingServerId == null) {
                                        "add_server_button"
                                    } else {
                                        "server_card_login"
                                    },
                                ),
                            )
                        },
                        failedExistingServerLoginIds = state.failedExistingServerLoginIds,
                    )
                }

                entry<RootDestination.CloudAccount> { destination ->
                    CloudAccountScreen(
                        onBack = { intentDispatcher(RootNavigationIntent.OnCloudAccountBack) },
                        initialCreateAccount = destination.createAccount,
                        onAuthenticated = {
                            intentDispatcher(RootNavigationIntent.OnCloudAccountAuthenticated)
                        },
                    )
                }
            },
        )
    }
}
