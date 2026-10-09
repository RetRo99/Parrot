package com.retro99.login.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.retro99.base.ui.BaseScreen
import com.retro99.catalogue.ui.sources.CatalogueStandaloneAddScreen
import com.retro99.login.ui.login.LoginScreen
import com.retro99.login.ui.welcome.WelcomeScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LoginNavigation(
    onLoginSuccess: () -> Unit,
    onLoginAttemptStarted: (String, String, String) -> Unit,
    onLoginFailure: (String, String, String) -> Unit,
    onGuestModeSelected: () -> Unit,
    onCloudAccountRequested: (createAccount: Boolean) -> Unit,
    onPhoneFilesSelected: () -> Unit,
    onBack: ((entryPoint: String) -> Unit)? = null,
    onRootBack: (() -> Unit)? = null,
    startAtLogin: Boolean = false,
    existingServerId: String? = null,
    isRetryOrigin: Boolean = false,
    loginSourceScreen: String? = null,
    loginEntryPoint: String? = null,
    modifier: Modifier = Modifier,
    viewModel: LoginNavigationViewModel = koinViewModel {
        parametersOf(startAtLogin, existingServerId != null, loginSourceScreen, loginEntryPoint)
    },
) {
    BaseScreen(viewModel = viewModel) { state, intentDispatcher ->
        val rootWelcomeBackState = rememberNavigationEventState(NavigationEventInfo.None)
        val catalogueAddBackState = rememberNavigationEventState(NavigationEventInfo.None)
        var showCatalogueAdd by remember { mutableStateOf(false) }
        val isRootWelcomeBackEnabled =
            state.backStack == listOf(LoginDestination.Welcome) && onRootBack != null

        LaunchedEffect(state.backStack.lastOrNull()) {
            val visibleDestination = state.backStack.lastOrNull() ?: return@LaunchedEffect
            viewModel.onDestinationVisible(
                destination = visibleDestination,
                source = state.backStack.getOrNull(state.backStack.lastIndex - 1),
            )
        }

        LaunchedEffect(state.skipLoginComplete) {
            if (state.skipLoginComplete) {
                onGuestModeSelected()
            }
        }

        if (showCatalogueAdd) {
            CatalogueStandaloneAddScreen(
                onBack = { showCatalogueAdd = false },
                onCatalogueAdded = {
                    showCatalogueAdd = false
                    onGuestModeSelected()
                },
            )
        } else NavDisplay(
            backStack = state.backStack,
            onBack = {
                when {
                    state.backStack.size > 1 ->
                        intentDispatcher(LoginNavigationIntent.OnBackClicked)

                    onBack != null -> onBack("system_back")

                    onRootBack != null -> viewModel.onWelcomeSystemBack(onRootBack)
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
                        onCompactLayoutAvailable = viewModel::onWelcomeCompactLayoutAvailable,
                        onServerClick = {
                            intentDispatcher(LoginNavigationIntent.NavigateTo(LoginDestination.Login))
                        },
                        onCloudCreateAccountClick = { onCloudAccountRequested(true) },
                        onCloudSignInClick = { onCloudAccountRequested(false) },
                        onPhoneFilesClick = onPhoneFilesSelected,
                    )
                }

                entry<LoginDestination.Login> {
                    LoginScreen(
                        onSignInSuccess = onLoginSuccess,
                        onSignInAttemptStarted = onLoginAttemptStarted,
                        onSignInFailure = onLoginFailure,
                        existingServerId = existingServerId,
                        isRetryOrigin = isRetryOrigin,
                        onCatalogueAddSelected = { showCatalogueAdd = true },
                        draft = viewModel.loginDraft,
                        onBackClick = {
                            if (state.backStack.size <= 1 && onBack != null) {
                                onBack("toolbar_back")
                            } else {
                                intentDispatcher(LoginNavigationIntent.OnBackClicked)
                            }
                        },
                    )
                }
            }
        )

        NavigationBackHandler(
            state = rootWelcomeBackState,
            isBackEnabled = isRootWelcomeBackEnabled,
            onBackCompleted = {
                onRootBack?.let(viewModel::onWelcomeSystemBack)
            },
        )

        NavigationBackHandler(
            state = catalogueAddBackState,
            isBackEnabled = showCatalogueAdd,
            onBackCompleted = { showCatalogueAdd = false },
        )
    }
}
