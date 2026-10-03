package com.retro99.parrot.navigation

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductUsage
import com.retro99.analytics.api.clearUserIdentity
import com.retro99.analytics.api.AuthAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.auth.domain.usecase.CheckAuthStateUseCase
import com.retro99.auth.domain.usecase.LogoutUseCase
import com.retro99.base.ui.BaseViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class RootNavigationViewModel(
    private val checkAuthStateUseCase: CheckAuthStateUseCase,
    private val logoutUseCase: LogoutUseCase,
    @Provided private val analytics: Analytics,
    @Provided private val productUsage: ProductUsage,
) : BaseViewModel<RootNavigationState, RootNavigationIntent>(RootNavigationState()) {

    private var nextHomeEntryId = 0L
    private val homeExposureGate = HomeExposureGate()

    init {
        productUsage.appLaunched()
        analytics.clearUserIdentity()
        checkAuthState()
    }

    override fun onIntent(intent: RootNavigationIntent) {
        when (intent) {
            RootNavigationIntent.OnLoginSuccess -> handleLoginSuccess()
            RootNavigationIntent.OnGuestModeSelected -> handleGuestModeSelected()
            is RootNavigationIntent.OnCloudAccountRequested ->
                handleCloudAccountRequested(intent.createAccount)
            RootNavigationIntent.OnCloudAccountAuthenticated -> handleCloudAccountAuthenticated()
            RootNavigationIntent.OnCloudAccountBack -> handleCloudAccountBack()
            RootNavigationIntent.OnPhoneFilesSelected -> handlePhoneFilesSelected()
            is RootNavigationIntent.OnPhoneFilesRequestConsumed ->
                handlePhoneFilesRequestConsumed(intent.homeEntryId)
            is RootNavigationIntent.OnHomeVisible -> handleHomeVisible(intent.entryId)
            RootNavigationIntent.OnLogout -> handleLogout()
            is RootNavigationIntent.OnLoginClicked ->
                handleLoginClicked(
                    existingServerId = intent.existingServerId,
                    isRetry = intent.isRetry,
                    sourceScreen = intent.sourceScreen,
                    entryPoint = intent.entryPoint,
                )
            RootNavigationIntent.OnExistingServerLoginSuccess -> handleExistingServerLoginSuccess()
            is RootNavigationIntent.OnExistingServerLoginAttemptStarted ->
                handleExistingServerLoginAttemptStarted(intent)
            is RootNavigationIntent.OnExistingServerLoginFailed ->
                handleExistingServerLoginFailed(intent)
            is RootNavigationIntent.OnBackFromLogin -> handleBackFromLogin(intent.entryPoint)
        }
    }

    private fun handleLoginClicked(
        existingServerId: String?,
        isRetry: Boolean,
        sourceScreen: String?,
        entryPoint: String?,
    ) {
        updateState { state ->
            state.copy(
                backStack = state.backStack + RootDestination.Login(
                    initial = false,
                    existingServerId = existingServerId,
                    isRetryOrigin = isRetry,
                    sourceScreen = sourceScreen,
                    entryPoint = entryPoint,
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
                failedExistingServerLoginIds = (state.backStack.lastOrNull() as? RootDestination.Login)
                    ?.existingServerId
                    ?.let { state.failedExistingServerLoginIds - it }
                    ?: state.failedExistingServerLoginIds,
                homeEntry = if (backStack.lastOrNull() == RootDestination.Home) {
                    createHomeEntry(sourceScreen = "login", entryPoint = "existing_server_login_success")
                } else {
                    state.homeEntry
                },
            )
        }
    }

    private fun handleExistingServerLoginFailed(intent: RootNavigationIntent.OnExistingServerLoginFailed) {
        var stateUpdated = false
        updateState { state ->
            val currentLogin = state.backStack.lastOrNull() as? RootDestination.Login
            if (currentLogin?.existingServerId != intent.serverId ||
                intent.serverId in state.failedExistingServerLoginIds
            ) {
                state
            } else {
                stateUpdated = true
                state.copy(
                    failedExistingServerLoginIds = state.failedExistingServerLoginIds + intent.serverId,
                )
            }
        }
        if (stateUpdated) {
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "server_management",
                    sourceScreen = "login",
                    destinationScreen = "server_management",
                    entryPoint = "server_card_login",
                    action = "reauthenticate_server",
                    operation = "existing_server_login",
                    stage = "failure_state_retained",
                    outcome = "failed",
                    reasonCode = "login_failed",
                    serverType = intent.serverType,
                    correlationId = intent.correlationId,
                ),
            )
        }
    }

    private fun handleExistingServerLoginAttemptStarted(
        intent: RootNavigationIntent.OnExistingServerLoginAttemptStarted,
    ) {
        val state = viewState.value
        val currentLogin = state.backStack.lastOrNull() as? RootDestination.Login
        if (currentLogin?.existingServerId != intent.serverId ||
            intent.serverId !in state.failedExistingServerLoginIds
        ) {
            return
        }

        updateState { current ->
            current.copy(
                failedExistingServerLoginIds = current.failedExistingServerLoginIds - intent.serverId,
            )
        }
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "server_management",
                sourceScreen = "login",
                destinationScreen = "login",
                entryPoint = "server_card_login",
                action = "reauthenticate_server",
                operation = "existing_server_login",
                stage = "retry_started",
                outcome = "started",
                reasonCode = "login_retry",
                serverType = intent.serverType,
                correlationId = intent.correlationId,
            ),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun handleBackFromLogin(entryPoint: String) {
        val stateBeforeBack = viewState.value
        val login = stateBeforeBack.backStack.lastOrNull() as? RootDestination.Login ?: return
        if (login.initial || stateBeforeBack.backStack.size <= 1) return

        val context = RootLoginBackContext(
            sourceScreen = "login",
            destinationScreen = login.sourceScreen ?: "home",
            entryPoint = entryPoint,
            correlationId = Uuid.random().toString(),
        )
        logRootLoginBackAttempt(analytics, context)

        var applied = false
        updateState { state ->
            val currentLogin = state.backStack.lastOrNull() as? RootDestination.Login
            if (state.backStack.size <= 1 || currentLogin != login || currentLogin.initial) {
                return@updateState state
            }
            val backStack = state.backStack.dropLast(1)
            applied = true
            state.copy(
                backStack = backStack,
                homeEntry = if (backStack.lastOrNull() == RootDestination.Home) {
                    createHomeEntry(sourceScreen = "login", entryPoint = "back_navigation")
                } else {
                    state.homeEntry
                },
            )
        }
        logRootLoginBackCompleted(analytics, context, applied)
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
            val destination = if (resolution.shouldOpenLibrary) {
                RootDestination.Home
            } else {
                RootDestination.Login(true)
            }
            analytics.logEvent(
                NavigationAnalyticsEvent.AppLaunchRouteResolved(
                    destination = if (resolution.shouldOpenLibrary) "home" else "welcome",
                    outcome = if (resolution.usedFallback) "fallback" else "success",
                    reasonCode = if (resolution.usedFallback) "auth_state_check_failed" else null,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = if (resolution.shouldOpenLibrary) "home" else "welcome",
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
                    homeEntry = if (resolution.shouldOpenLibrary) {
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

    private fun handleCloudAccountRequested(createAccount: Boolean) {
        updateState { state ->
            state.copy(backStack = state.backStack + RootDestination.CloudAccount(createAccount))
        }
    }

    private fun handleCloudAccountAuthenticated() {
        updateState { state ->
            state.copy(
                backStack = listOf(RootDestination.Home),
                homeEntry = createHomeEntry(
                    sourceScreen = "welcome",
                    entryPoint = "cloud_account_authenticated",
                ),
            )
        }
    }

    private fun handleCloudAccountBack() {
        updateState { state ->
            if (state.backStack.lastOrNull() is RootDestination.CloudAccount && state.backStack.size > 1) {
                state.copy(backStack = state.backStack.dropLast(1))
            } else {
                state
            }
        }
    }

    private fun handlePhoneFilesSelected() {
        updateState { state ->
            state.copy(
                backStack = listOf(RootDestination.Home),
                homeEntry = createHomeEntry(
                    sourceScreen = "welcome",
                    entryPoint = "phone_files",
                    openPhoneFilesOnArrival = true,
                ),
            )
        }
    }

    private fun handlePhoneFilesRequestConsumed(homeEntryId: Long) {
        updateState { state ->
            val entry = state.homeEntry
            if (entry?.id == homeEntryId && entry.openPhoneFilesOnArrival) {
                state.copy(homeEntry = entry.copy(openPhoneFilesOnArrival = false))
            } else {
                state
            }
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

    private fun createHomeEntry(
        sourceScreen: String,
        entryPoint: String,
        openPhoneFilesOnArrival: Boolean = false,
    ): RootHomeEntry =
        RootHomeEntry(
            id = ++nextHomeEntryId,
            sourceScreen = sourceScreen,
            entryPoint = entryPoint,
            openPhoneFilesOnArrival = openPhoneFilesOnArrival,
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
