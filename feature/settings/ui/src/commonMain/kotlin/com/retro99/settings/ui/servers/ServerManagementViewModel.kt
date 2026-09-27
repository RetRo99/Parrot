package com.retro99.settings.ui.servers

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.base.ui.BaseViewModel
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.settings.ui.servers.model.toUiModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class ServerManagementViewModel(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val analytics: Analytics,
    @InjectedParam private val onNavigateToLogin: (String?) -> Unit,
) : BaseViewModel<ServerManagementViewState, ServerManagementIntent>(ServerManagementViewState()) {

    init {
        observeServers()
    }

    override fun onIntent(intent: ServerManagementIntent) {
        when (intent) {
            is ServerManagementIntent.OnLoginClick -> onLoginClick(intent.serverId, intent.serverType)
            is ServerManagementIntent.OnLogoutClick -> onLogoutClick(intent.serverId, intent.serverType)
            is ServerManagementIntent.OnRemoveClick -> onRemoveClick(intent.serverId, intent.serverType)
            ServerManagementIntent.RetryFailedOperation -> retryFailedOperation()
            ServerManagementIntent.DismissOperationFailure -> dismissOperationFailure()
            ServerManagementIntent.OnAddServerClick -> {
                analytics.logEvent(ServerManagementAnalyticsEvent.ServerAddAttempted)
                onNavigateToLogin(null)
            }
        }
    }

    private fun observeServers() {
        combine(
            serverRegistry.observeAllServers(),
            serverRegistry.observeAllAuthStates(),
        ) { servers, authStates ->
            servers
                .filter { server -> server.type != ServerType.Local }
                .map { server ->
                    ServerWithStatusUiModel(
                        server = server.toUiModel(),
                        authState = authStates[server.id] ?: ServerAuthState.NotAuthenticated(server.id),
                    )
                }
        }
            .onEach { serversWithStatus ->
                updateState {
                    it.copy(
                        isLoading = false,
                        servers = serversWithStatus,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun onLoginClick(serverId: String, serverType: ServerType) {
        analytics.logEvent(ServerManagementAnalyticsEvent.ServerLoginAttempted(serverType.identifier))
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "server_management",
                sourceScreen = "server_management",
                destinationScreen = "login",
                entryPoint = "server_card_login",
                action = "reauthenticate_server",
                operation = "existing_server_login",
                stage = "navigation",
                outcome = "started",
                serverType = serverType.identifier,
            ),
        )
        onNavigateToLogin(serverId)
    }

    private var operationInProgress = false

    private fun onLogoutClick(serverId: String, serverType: ServerType, isRetry: Boolean = false) {
        runMutation(
            serverId = serverId,
            serverType = serverType,
            operation = ServerManagementAnalyticsEvent.Operation.Logout,
            isRetry = isRetry,
        ) {
            serverRegistry.clearCredentials(serverId)
        }
    }

    private fun onRemoveClick(serverId: String, serverType: ServerType, isRetry: Boolean = false) {
        runMutation(
            serverId = serverId,
            serverType = serverType,
            operation = ServerManagementAnalyticsEvent.Operation.Remove,
            isRetry = isRetry,
        ) {
            serverRegistry.removeServer(serverId)
        }
    }

    private fun runMutation(
        serverId: String,
        serverType: ServerType,
        operation: ServerManagementAnalyticsEvent.Operation,
        isRetry: Boolean,
        mutate: suspend () -> Unit,
    ) {
        if (operationInProgress) return
        operationInProgress = true
        updateState { it.copy(isOperationInProgress = true, operationFailure = null) }

        viewModelScope.launch {
            try {
                val succeeded = runServerManagementOperation(
                    analytics = analytics,
                    operation = operation,
                    serverType = serverType,
                    isRetry = isRetry,
                    mutate = mutate,
                )
                if (!succeeded) {
                    updateState {
                        it.copy(
                            operationFailure = ServerManagementOperationFailure(
                                serverId = serverId,
                                serverType = serverType,
                                operation = operation,
                            ),
                        )
                    }
                }
            } finally {
                operationInProgress = false
                updateState { it.copy(isOperationInProgress = false) }
            }
        }
    }

    private fun retryFailedOperation() {
        val failure = currentViewState().operationFailure ?: return
        when (failure.operation) {
            ServerManagementAnalyticsEvent.Operation.Logout ->
                onLogoutClick(failure.serverId, failure.serverType, isRetry = true)

            ServerManagementAnalyticsEvent.Operation.Remove ->
                onRemoveClick(failure.serverId, failure.serverType, isRetry = true)
        }
    }

    private fun dismissOperationFailure() {
        updateState { it.copy(operationFailure = null) }
    }
}
