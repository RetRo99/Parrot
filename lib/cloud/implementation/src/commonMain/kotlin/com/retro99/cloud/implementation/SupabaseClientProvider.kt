package com.retro99.cloud.implementation

import com.retro99.user.api.UserRegistry
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

private data class ActiveClientState(
    val client: SupabaseClient? = null,
    val sessionManager: ProfileSessionManager? = null,
    val profileId: String? = null,
    val initialized: Boolean = false,
)

@Single
class SupabaseClientProvider(
    @Provided private val configuration: CloudConfiguration,
    @Provided private val cloudSessionManager: CloudSessionManager,
    @Provided private val userRegistry: UserRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileMutex = Mutex()
    private val clientState = MutableStateFlow(ActiveClientState())

    init {
        userRegistry.observeActiveProfile()
            .onEach { profile ->
                switchProfile(profile?.id ?: UserRegistry.DEFAULT_USER_ID)
            }
            .launchIn(scope)
    }

    val isConfigured: Boolean
        get() = configuration.isConfigured

    val client: SupabaseClient
        get() {
            val currentState = clientState.value
            check(currentState.profileId == currentProfileId()) {
                "Cloud client does not belong to the active profile"
            }
            return currentState.client ?: error("Cloud client was not initialized")
        }

    fun currentSessionState(): CloudSessionState {
        if (!isConfigured) return CloudSessionState(SessionStatus.NotAuthenticated())
        val state = clientState.value
        val localProfileId = currentProfileId()
        if (state.profileId != localProfileId || state.client == null) {
            return CloudSessionState(SessionStatus.Initializing)
        }
        return sessionState(localProfileId, state.client.auth.sessionStatus.value)
    }

    private fun createClient(localProfileId: String): ActiveClientState {
        require(configuration.isConfigured) {
            "Cloud account is not configured"
        }
        val profileSessionManager = cloudSessionManager.forProfile(localProfileId)
        val client = createSupabaseClient(
            supabaseUrl = configuration.supabaseUrl,
            supabaseKey = configuration.publishableKey,
        ) {
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = configuration.redirectScheme
                host = configuration.redirectHost
                defaultRedirectUrl = configuration.redirectUrl
                autoLoadFromStorage = false
                this.sessionManager = profileSessionManager
            }
        }
        return ActiveClientState(
            client = client,
            sessionManager = profileSessionManager,
            profileId = localProfileId,
        )
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun switchProfile(localProfileId: String) {
        profileMutex.withLock {
            switchProfileLocked(localProfileId)
        }
    }

    @OptIn(SupabaseInternal::class)
    private suspend fun switchProfileLocked(localProfileId: String) {
        if (!configuration.isConfigured) {
            clientState.value = ActiveClientState(
                profileId = localProfileId,
                initialized = true,
            )
            return
        }

        val currentState = clientState.value
        val profileAlreadyInitialized = currentState.profileId == localProfileId &&
            currentState.client != null &&
            currentState.initialized
        if (profileAlreadyInitialized) return

        val previousState = clientState.value
        val previousClient = previousState.let { state ->
            if (state.profileId == localProfileId) null else state.client
        }
        if (previousClient != null) {
            previousClient.auth.stopAutoRefreshForCurrentSession()
            previousClient.auth.setSessionStatus(SessionStatus.NotAuthenticated())
            previousState.sessionManager?.invalidate()
            previousClient.auth.close()
        }

        val profileClientState = clientState.value.let { state ->
            if (state.profileId == localProfileId &&
                state.client != null &&
                state.sessionManager != null
            ) {
                state.copy(initialized = false)
            } else {
                createClient(localProfileId)
            }
        }
        clientState.value = profileClientState
        val profileClient = profileClientState.client ?: error("Cloud client was not created")
        val restoredSession = profileClient.auth.sessionManager.loadSession()
        profileClient.auth.restoreCloudSession(restoredSession)
        val initializedState = clientState.value
        if (initializedState.client === profileClient && initializedState.profileId == localProfileId) {
            clientState.value = initializedState.copy(initialized = true)
        }
    }

    @OptIn(SupabaseInternal::class)
    suspend fun replaceCurrentProfileSession(
        localProfileId: String,
        session: UserSession?,
    ) {
        val currentState = clientState.value
        val currentClient = currentState.client
            ?: error("Cloud client was not created")
        val currentSessionManager = currentState.sessionManager
            ?: error("Cloud session manager was not created")
        check(currentState.profileId == localProfileId) {
            "Cloud authentication started for an inactive profile"
        }

        currentSessionManager.invalidate(clearStoredSession = true)
        currentClient.auth.clearSession()
        currentClient.auth.close()

        val replacementState = createClient(localProfileId)
        clientState.value = replacementState
        val replacementClient = replacementState.client ?: error("Cloud client was not created")
        replacementClient.auth.restoreCloudSession(session)
        clientState.value = replacementState.copy(initialized = true)
    }

    suspend fun invalidateCurrentProfileCredentials(localProfileId: String) {
        val state = clientState.value
        check(state.profileId == localProfileId) {
            "Cloud sign-out started for an inactive profile"
        }
        state.sessionManager?.invalidate(clearStoredSession = true)
    }

    suspend fun restoreSession() {
        if (!isConfigured) return
        val auth = withProfileSession(currentProfileId()) { client.auth }
        auth.awaitInitialization()
    }

    @OptIn(SupabaseInternal::class)
    suspend fun <T> withProfileSession(
        localProfileId: String,
        operation: suspend () -> T,
    ): T = profileMutex.withLock {
        val currentProfileId = userRegistry.getActiveProfileIdOrDefault()
        check(currentProfileId == localProfileId) {
            "Cloud authentication started for an inactive profile"
        }
        switchProfileLocked(localProfileId)
        check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
            "Cloud authentication started for an inactive profile"
        }
        operation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActiveSessionState(): Flow<CloudSessionState> {
        return userRegistry.observeActiveProfile()
            .map { profile -> profile?.id ?: UserRegistry.DEFAULT_USER_ID }
            .distinctUntilChanged()
            .flatMapLatest { localProfileId ->
                flow {
                    emit(CloudSessionState(SessionStatus.Initializing))
                    clientForProfile(localProfileId) ?: return@flow
                    emitAll(
                        clientState
                            .map { state ->
                                state.client.takeIf { state.profileId == localProfileId }
                            }
                            .distinctUntilChanged()
                            .flatMapLatest { client ->
                                client?.auth?.sessionStatus
                                    ?: flowOf(SessionStatus.Initializing)
                            }
                            .map { status ->
                                sessionState(localProfileId, status)
                            },
                    )
                }
            }
    }

    private suspend fun clientForProfile(localProfileId: String): SupabaseClient? {
        return profileMutex.withLock {
            if (userRegistry.getActiveProfileIdOrDefault() != localProfileId) {
                return@withLock null
            }
            switchProfileLocked(localProfileId)
            clientState.value.takeIf { state -> state.profileId == localProfileId }?.client
        }
    }

    fun currentProfileId(): String {
        return userRegistry.getActiveProfileIdOrDefault()
    }

    private fun sessionState(localProfileId: String, status: SessionStatus): CloudSessionState {
        if (localProfileId != currentProfileId()) {
            return CloudSessionState(SessionStatus.Initializing)
        }
        val accountId = when (status) {
            is SessionStatus.NotAuthenticated ->
                cloudSessionManager.reauthenticationAccountId(localProfileId)
            else -> cloudSessionManager.storedCloudAccountId(localProfileId)
        }
        return CloudSessionState(status, accountId)
    }
}
