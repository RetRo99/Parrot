package com.retro99.settings.ui.servers

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
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
    @InjectedParam private val onNavigateToLogin: () -> Unit,
) : BaseViewModel<ServerManagementViewState, ServerManagementIntent>(ServerManagementViewState()) {

    init {
        observeServers()
    }

    override fun onIntent(intent: ServerManagementIntent) {
        when (intent) {
            is ServerManagementIntent.OnLoginClick -> onLoginClick(intent.serverId)
            is ServerManagementIntent.OnLogoutClick -> onLogoutClick(intent.serverId)
            is ServerManagementIntent.OnRemoveClick -> onRemoveClick(intent.serverId)
            ServerManagementIntent.OnAddServerClick -> {
                analytics.logEvent(ServerManagementAnalyticsEvent.ServerAddAttempted)
                onNavigateToLogin()
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

    private fun onLoginClick(serverId: String) {
        onNavigateToLogin()
    }

    private fun onLogoutClick(serverId: String) {
        viewModelScope.launch {
            val serverType = serverRegistry.getServer(serverId)?.type?.name ?: "unknown"
            analytics.logEvent(ServerManagementAnalyticsEvent.ServerLoggedOut(serverType = serverType))
            serverRegistry.clearCredentials(serverId)
        }
    }

    private fun onRemoveClick(serverId: String) {
        viewModelScope.launch {
            val serverType = serverRegistry.getServer(serverId)?.type?.name ?: "unknown"
            analytics.logEvent(ServerManagementAnalyticsEvent.ServerRemoved(serverType = serverType))
            serverRegistry.removeServer(serverId)
        }
    }
}
