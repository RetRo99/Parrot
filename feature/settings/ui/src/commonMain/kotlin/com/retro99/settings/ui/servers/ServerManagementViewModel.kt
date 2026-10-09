package com.retro99.settings.ui.servers

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.base.ui.BaseViewModel
import com.retro99.catalogue.ui.settings.CatalogueSettingsGateway
import com.retro99.settings.domain.usecase.LogoutUseCase
import kotlinx.coroutines.flow.first
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.CatalogueAccessProvider
import com.retro99.server.api.getCapabilities
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.settings.ui.servers.model.toUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@KoinViewModel
class ServerManagementViewModel(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val analytics: Analytics,
    @Provided private val catalogueAccessProvider: CatalogueAccessProvider,
    @Provided private val catalogueSettings: CatalogueSettingsGateway,
    @Provided private val logoutAll: LogoutUseCase,
    @InjectedParam private val onNavigateToLogin: (String?, Boolean) -> Unit,
    @InjectedParam private val stopPlaybackForServer: (String, DiagnosticContext) -> Unit,
) : BaseViewModel<ServerManagementViewState, ServerManagementIntent>(ServerManagementViewState()) {

    init {
        viewModelScope.launch {
            catalogueAccessProvider.observeSources().collect { sources ->
                updateState { it.copy(catalogueSources = sources.map { mapCatalogueSource(it.config, it.status) }) }
            }
        }
        observeServers()
    }

    override fun onIntent(intent: ServerManagementIntent) {
        when (intent) {
            ServerManagementIntent.OnSignOutEverything -> signOutEverything()
            is ServerManagementIntent.OnTurnOnCatalogue -> catalogueAction(intent.sourceId, turnOn = true)
            is ServerManagementIntent.OnRetryCatalogue -> catalogueAction(intent.sourceId, turnOn = false)
            is ServerManagementIntent.OnLoginClick ->
                onLoginClick(intent.serverId, intent.serverType, intent.isRetry)
            is ServerManagementIntent.OnLogoutClick -> onLogoutClick(intent.serverId, intent.serverType)
            is ServerManagementIntent.OnRemoveClick -> onRemoveClick(intent.serverId, intent.serverType)
            is ServerManagementIntent.OnRenameServer -> updateServerConfig(intent.serverId) { config ->
                config.copy(name = intent.name.trim())
            }
            is ServerManagementIntent.OnChangeAddress -> updateServerConfig(intent.serverId) { config ->
                config.copy(baseUrl = if (config.type == ServerType.Opds) intent.baseUrl else normalizeServerAddress(intent.baseUrl))
            }
            ServerManagementIntent.RetryFailedOperation -> retryFailedOperation()
            ServerManagementIntent.DismissOperationFailure -> dismissOperationFailure()
            ServerManagementIntent.RetryServerListLoad -> retryServerListLoad()
            ServerManagementIntent.OnAddServerClick -> {
                analytics.logEvent(ServerManagementAnalyticsEvent.ServerAddAttempted)
                onNavigateToLogin(null, false)
            }
        }
    }

    private fun observeServers(isRetry: Boolean = false) {
        // Catalogue state is not translated into ServerAuthState. Legacy cards remain
        // library-only; catalogue presentation/navigation is deliberately Phase 4.
        val source = flow {
            combine(
                serverRegistry.observeAllServers(),
                serverRegistry.observeAllAuthStates(),
            ) { servers, authStates ->
                servers
                    .filter { server -> server.type != ServerType.Local && !server.type.getCapabilities().supportsCatalogueBrowsing }
                    .map { server ->
                        ServerWithStatusUiModel(
                            server = server.toUiModel(),
                            authState = authStates[server.id] ?: ServerAuthState.NotAuthenticated(server.id),
                        )
                    }
            }
                .collect { emit(it) }
        }
        updateState {
            it.copy(
                isLoading = true,
                serverListLoadFailed = false,
            )
        }
        viewModelScope.launch {
            observeServerList(
                analytics = analytics,
                isRetry = isRetry,
                source = source,
                onValue = { serversWithStatus ->
                    updateState {
                        it.copy(
                            isLoading = false,
                            serverListLoadFailed = false,
                            servers = serversWithStatus,
                        )
                    }
                },
                onFailure = {
                    updateState {
                        it.copy(
                            isLoading = false,
                            serverListLoadFailed = true,
                        )
                    }
                },
            )
        }
    }

    private fun retryServerListLoad() {
        val state = currentViewState()
        if (!state.serverListLoadFailed || state.isLoading) return
        observeServers(isRetry = true)
    }

    private fun onLoginClick(serverId: String, serverType: ServerType, isRetry: Boolean) {
        if (serverType.getCapabilities().supportsCatalogueBrowsing) return
        analytics.logEvent(
            ServerManagementAnalyticsEvent.ServerLoginAttempted(
                serverType = serverType.identifier,
                isRetry = isRetry,
            ),
        )
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
        onNavigateToLogin(serverId, isRetry)
    }

    private var operationInProgress = false

    private fun signOutEverything() {
        if (operationInProgress) return
        operationInProgress = true
        updateState { it.copy(isOperationInProgress = true, catalogueOperationFailed = false) }
        viewModelScope.launch {
            try {
                silenceServersSignedOutOfEverything()
                logoutAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateState { it.copy(catalogueOperationFailed = true) }
            } finally {
                operationInProgress = false
                updateState { it.copy(isOperationInProgress = false) }
            }
        }
    }

    /**
     * "Sign out of everything" signs out of every remote server and never of the local
     * library, so the audio it stops is every remote server's and never a local book's
     * (QA-BUG-0049, decision 4). It goes through the same per-server rule as a single
     * logout: only one media session can be active, so at most one of these stops anything.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun silenceServersSignedOutOfEverything() {
        val correlationId = Uuid.random().toString()
        serverRegistry.getAllServers()
            .filter { server -> server.type != ServerType.Local }
            .forEach { server ->
                stopPlaybackForServer(
                    server.id,
                    DiagnosticContext(
                        screen = "server_management",
                        action = "sign_out_everything",
                        operation = "server_logout_all",
                        stage = "started",
                        outcome = "started",
                        serverType = server.type.identifier,
                        correlationId = correlationId,
                    ),
                )
            }
    }

    private fun catalogueAction(sourceId: String, turnOn: Boolean) {
        if (operationInProgress) return
        operationInProgress = true
        updateState { it.copy(isOperationInProgress = true, catalogueOperationFailed = false) }
        viewModelScope.launch {
            try {
                val source = catalogueSettings.observeSource(sourceId).first() ?: return@launch
                if (turnOn) catalogueSettings.setEnabled(source, true) else catalogueSettings.retry(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateState { it.copy(catalogueOperationFailed = true) }
            } finally {
                operationInProgress = false
                updateState { it.copy(isOperationInProgress = false) }
            }
        }
    }

    private fun onLogoutClick(serverId: String, serverType: ServerType, isRetry: Boolean = false) {
        runMutation(
            serverId = serverId,
            serverType = serverType,
            operation = ServerManagementAnalyticsEvent.Operation.Logout,
            isRetry = isRetry,
            beforeMutation = { operationContext ->
                stopPlaybackForServer(serverId, operationContext)
            },
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
        beforeMutation: suspend (DiagnosticContext) -> Unit = {},
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
                    beforeMutation = beforeMutation,
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

    private fun updateServerConfig(
        serverId: String,
        transform: (ServerConfig) -> ServerConfig,
    ) {
        viewModelScope.launch {
            try {
                val config = serverRegistry.getServer(serverId) ?: return@launch
                serverRegistry.updateServer(transform(config))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                analytics.logException(
                    error,
                    DiagnosticContext(
                        screen = "server_management",
                        action = "update_server_config",
                        operation = "update_server_config",
                        stage = "terminal",
                        outcome = "failed",
                    ),
                )
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
