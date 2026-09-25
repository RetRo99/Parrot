package com.retro99.settings.ui.servers

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.base.ui.BaseViewModel
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.library.domain.grouping.AudiobookshelfPairingResult
import com.retro99.library.domain.grouping.LibrarySourceIdentityPairingRepository
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentityPairingException
import com.retro99.server.api.library.LibrarySourceIdentityPairingFailure
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.settings.ui.servers.model.ServerWithStatusUiModel
import com.retro99.settings.ui.servers.model.toUiModel
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class ServerManagementViewModel(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val analytics: Analytics,
    @Provided private val pairingRepository: LibrarySourceIdentityPairingRepository,
    @Provided private val cloudAccountRepository: CloudAccountRepository,
    @Provided private val userRegistry: UserRegistry,
    @InjectedParam private val onNavigateToLogin: (String?) -> Unit,
) : BaseViewModel<ServerManagementViewState, ServerManagementIntent>(ServerManagementViewState()) {

    init {
        observeServers()
    }

    override fun onIntent(intent: ServerManagementIntent) {
        when (intent) {
            is ServerManagementIntent.OnLoginClick -> onLoginClick(intent.serverId)
            is ServerManagementIntent.OnLogoutClick -> onLogoutClick(intent.serverId)
            is ServerManagementIntent.OnRemoveClick -> onRemoveClick(intent.serverId)
            is ServerManagementIntent.OnPairingClick -> openPairing(intent.serverId)
            ServerManagementIntent.OnPairingDismissed -> dismissPairing()
            is ServerManagementIntent.OnPairingCodeChanged -> updatePairingCode(intent.code)
            ServerManagementIntent.OnCreatePairingCodeClick -> createPairingCode()
            ServerManagementIntent.OnImportPairingCodeClick -> importPairingCode()
            ServerManagementIntent.OnShowPairingCodeClick -> showPairingCode()
            ServerManagementIntent.OnUnpairClick -> unpair()
            ServerManagementIntent.OnAddServerClick -> {
                analytics.logEvent(
                    ServerManagementAnalyticsEvent.ServerAdded(serverType = "unknown"),
                )
                onNavigateToLogin(null)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeServers() {
        combine(
            serverRegistry.observeAllServers(),
            serverRegistry.observeAllAuthStates(),
            userRegistry.observeActiveProfile()
                .map { profile -> profile?.id ?: UserRegistry.DEFAULT_USER_ID }
                .distinctUntilChanged(),
            cloudAccountRepository.observeAuthState(),
        ) { servers, authStates, localProfileId, cloudAuthState ->
            ServerObservation(
                configurations = servers.filter { server -> server.type != ServerType.Local },
                authStates = authStates,
                localProfileId = localProfileId,
                cloudAuthState = cloudAuthState,
            )
        }
            .flatMapLatest { observation ->
                val audiobookshelfServers = observation.configurations.filter { server ->
                    server.type == ServerType.Audiobookshelf
                }
                val pairingStatuses = if (audiobookshelfServers.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    val statusFlows = audiobookshelfServers.map { server ->
                        pairingRepository.observeAudiobookshelfPairingStatus(
                            profileId = LibraryProfileId(observation.localProfileId),
                            serverId = server.id,
                        )
                    }
                    combine(statusFlows) { statuses ->
                        audiobookshelfServers.mapIndexed { index, server ->
                            server.id to statuses[index]
                        }.toMap()
                    }
                }
                pairingStatuses.map { statuses -> observation to statuses }
            }
            .onEach { (observation, pairingStatuses) ->
                val serversWithStatus = observation.configurations.map { server ->
                    ServerWithStatusUiModel(
                        server = server.toUiModel(),
                        authState = observation.authStates[server.id]
                            ?: ServerAuthState.NotAuthenticated(server.id),
                    )
                }
                updateState {
                    val profileChanged = it.localProfileId != observation.localProfileId
                    val selectedServerStillExists = serversWithStatus.any { server ->
                        server.server.id == it.pairingServerId &&
                            server.server.type == ServerType.Audiobookshelf
                    }
                    val cloudChanged = cloudAccountChanged(it, observation.cloudAuthState)
                    val clearPairingUi = profileChanged || cloudChanged ||
                        (it.pairingServerId != null && !selectedServerStillExists)
                    it.copy(
                        isLoading = false,
                        servers = serversWithStatus,
                        localProfileId = observation.localProfileId,
                        cloudAuthState = observation.cloudAuthState,
                        pairingStatuses = pairingStatuses,
                        pairingServerId = it.pairingServerId.takeIf { serverId ->
                            !clearPairingUi && selectedServerStillExists && serverId != null
                        },
                        pairingCodeInput = if (clearPairingUi) "" else it.pairingCodeInput,
                        displayedPairingCode = if (clearPairingUi) {
                            null
                        } else {
                            it.displayedPairingCode
                        },
                        pairingActionStatus = if (clearPairingUi) {
                            null
                        } else {
                            it.pairingActionStatus
                        },
                        pairingAction = if (clearPairingUi) null else it.pairingAction,
                        pairingConflictCount = if (clearPairingUi) 0 else it.pairingConflictCount,
                        pairingUnexpectedError = if (clearPairingUi) {
                            false
                        } else {
                            it.pairingUnexpectedError
                        },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun openPairing(serverId: String) {
        val serverExists = viewState.value.servers.any { item ->
            item.server.id == serverId && item.server.type == ServerType.Audiobookshelf
        }
        if (!serverExists || viewState.value.isPairingActionInProgress) return
        updateState {
            it.copy(
                pairingServerId = serverId,
                pairingCodeInput = "",
                displayedPairingCode = null,
                pairingActionStatus = null,
                pairingAction = null,
                pairingConflictCount = 0,
                pairingUnexpectedError = false,
            )
        }
    }

    private fun dismissPairing() {
        updateState {
            it.copy(
                pairingServerId = null,
                pairingCodeInput = "",
                displayedPairingCode = null,
                pairingActionStatus = null,
                pairingAction = null,
                pairingConflictCount = 0,
                pairingUnexpectedError = false,
            )
        }
    }

    private fun updatePairingCode(code: String) {
        updateState {
            it.copy(
                pairingCodeInput = code,
                pairingActionStatus = null,
                pairingAction = null,
                pairingConflictCount = 0,
                pairingUnexpectedError = false,
            )
        }
    }

    private fun createPairingCode() {
        runPairingAction(PairingAction.CreateCode) { profileId, serverId ->
            pairingRepository.createAudiobookshelfPairingCode(profileId, serverId)
        }
    }

    private fun importPairingCode() {
        val code = viewState.value.pairingCodeInput.trim()
        if (code.isBlank()) return
        runPairingAction(PairingAction.ImportCode) { profileId, serverId ->
            pairingRepository.bindAudiobookshelfPairingCode(profileId, serverId, code)
        }
    }

    private fun showPairingCode() {
        runPairingAction(PairingAction.ShowCode) { profileId, serverId ->
            pairingRepository.createAudiobookshelfPairingCode(profileId, serverId)
        }
    }

    private fun unpair() {
        runPairingAction(PairingAction.Unpair) { profileId, serverId ->
            pairingRepository.revokeAudiobookshelfPairing(profileId, serverId)
        }
    }

    private fun runPairingAction(
        pairingAction: PairingAction,
        action: suspend (LibraryProfileId, String) -> AudiobookshelfPairingResult,
    ) {
        val currentState = viewState.value
        val serverId = currentState.pairingServerId ?: return
        if (currentState.isPairingActionInProgress) return
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val cloudAccountId = signedInCloudAccountId(cloudAccountRepository.currentAuthState())
        if (cloudAccountId == null) {
            updateState {
                it.copy(
                    pairingActionStatus = AudiobookshelfPairingActionStatus.CloudAccountRequired,
                )
            }
            return
        }
        val isServerAuthenticated = currentState.servers.any { server ->
            server.server.id == serverId && server.authState is ServerAuthState.Authenticated
        }
        if (!isServerAuthenticated) {
            updateState {
                it.copy(
                    pairingActionStatus = AudiobookshelfPairingActionStatus.NotAuthenticated,
                )
            }
            return
        }

        updateState {
            it.copy(
                isPairingActionInProgress = true,
                pairingActionStatus = null,
                pairingAction = pairingAction,
                pairingConflictCount = 0,
                pairingUnexpectedError = false,
            )
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(localProfileId)
                val result = cloudAccountRepository.withProfileSession(localProfileId) {
                    action(profileId, serverId)
                }
                if (!pairingContextStillMatches(localProfileId, cloudAccountId)) {
                    return@launch
                }
                updateState {
                    if (
                        it.localProfileId != localProfileId ||
                        signedInCloudAccountId(it.cloudAuthState) != cloudAccountId
                    ) {
                        return@updateState it.copy(
                            pairingServerId = null,
                            pairingCodeInput = "",
                            displayedPairingCode = null,
                            pairingActionStatus = null,
                            pairingAction = null,
                            pairingConflictCount = 0,
                            pairingUnexpectedError = false,
                        )
                    }
                    it.copy(
                        pairingActionStatus = result.status,
                        pairingConflictCount = if (
                            result.status == AudiobookshelfPairingActionStatus.HasSharedMemberships
                        ) {
                            result.blockingMembershipCount
                        } else {
                            result.conflictingNativeBookIds.size
                        },
                        displayedPairingCode = if (
                            result.status == AudiobookshelfPairingActionStatus.Completed
                        ) {
                            result.code
                        } else {
                            null
                        },
                        pairingCodeInput = if (
                            result.status == AudiobookshelfPairingActionStatus.Completed
                        ) {
                            ""
                        } else {
                            it.pairingCodeInput
                        },
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LibrarySourceIdentityPairingException) {
                updateState {
                    if (
                        it.localProfileId != localProfileId ||
                        signedInCloudAccountId(it.cloudAuthState) != cloudAccountId
                    ) {
                        it
                    } else {
                        it.copy(
                            pairingActionStatus = exception.reason.toActionStatus(),
                            displayedPairingCode = null,
                        )
                    }
                }
            } catch (exception: Exception) {
                updateState {
                    if (
                        it.localProfileId != localProfileId ||
                        signedInCloudAccountId(it.cloudAuthState) != cloudAccountId
                    ) {
                        it
                    } else {
                        it.copy(
                            pairingUnexpectedError = true,
                            displayedPairingCode = null,
                        )
                    }
                }
            } finally {
                updateState { it.copy(isPairingActionInProgress = false) }
            }
        }
    }

    private fun onLoginClick(serverId: String) {
        val server = viewState.value.servers.firstOrNull { item ->
            item.server.id == serverId
        }?.server ?: return
        if (server.type == ServerType.Storyteller) {
            onNavigateToLogin(serverId)
        }
    }

    private fun onLogoutClick(serverId: String) {
        viewModelScope.launch {
            val serverType = serverRegistry.getServer(serverId)?.type?.name ?: "unknown"
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerLoggedOut(serverType = serverType),
            )
            serverRegistry.clearCredentials(serverId)
        }
    }

    private fun onRemoveClick(serverId: String) {
        viewModelScope.launch {
            val serverType = serverRegistry.getServer(serverId)?.type?.name ?: "unknown"
            analytics.logEvent(
                ServerManagementAnalyticsEvent.ServerRemoved(serverType = serverType),
            )
            serverRegistry.removeServer(serverId)
        }
    }

    private data class ServerObservation(
        val configurations: List<ServerConfig>,
        val authStates: Map<String, ServerAuthState>,
        val localProfileId: String,
        val cloudAuthState: CloudAuthState,
    )

    private fun cloudAccountChanged(
        currentState: ServerManagementViewState,
        nextAuthState: CloudAuthState,
    ): Boolean {
        val currentAccountId = signedInCloudAccountId(currentState.cloudAuthState)
        val nextAccountId = signedInCloudAccountId(nextAuthState)
        return currentAccountId != nextAccountId
    }

    private fun signedInCloudAccountId(authState: CloudAuthState): String? =
        (authState as? CloudAuthState.SignedIn)?.account?.id

    private fun pairingContextStillMatches(
        localProfileId: String,
        cloudAccountId: String,
    ): Boolean = userRegistry.getActiveProfileIdOrDefault() == localProfileId &&
        signedInCloudAccountId(cloudAccountRepository.currentAuthState()) == cloudAccountId

    private fun LibrarySourceIdentityPairingFailure.toActionStatus():
        AudiobookshelfPairingActionStatus = when (this) {
            LibrarySourceIdentityPairingFailure.CloudAccountRequired -> {
                AudiobookshelfPairingActionStatus.CloudAccountRequired
            }
            LibrarySourceIdentityPairingFailure.CloudAccountMismatch -> {
                AudiobookshelfPairingActionStatus.CloudAccountMismatch
            }
            LibrarySourceIdentityPairingFailure.SourceAccountMismatch -> {
                AudiobookshelfPairingActionStatus.SourceAccountMismatch
            }
            LibrarySourceIdentityPairingFailure.SourceAccountIdUnavailable -> {
                AudiobookshelfPairingActionStatus.SourceAccountIdUnavailable
            }
            LibrarySourceIdentityPairingFailure.AlreadyPaired -> {
                AudiobookshelfPairingActionStatus.AlreadyPaired
            }
            LibrarySourceIdentityPairingFailure.InvalidCode -> {
                AudiobookshelfPairingActionStatus.InvalidCode
            }
            LibrarySourceIdentityPairingFailure.UnsupportedCodeVersion -> {
                AudiobookshelfPairingActionStatus.UnsupportedCodeVersion
            }
            LibrarySourceIdentityPairingFailure.NotAuthenticated -> {
                AudiobookshelfPairingActionStatus.NotAuthenticated
            }
            LibrarySourceIdentityPairingFailure.ServerUnavailable -> {
                AudiobookshelfPairingActionStatus.ServerUnavailable
            }
        }

}
