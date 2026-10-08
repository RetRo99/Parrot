package com.retro99.server.implementation

import co.touchlab.kermit.Logger
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.putObject
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerAuthStateProvider
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueWorkController
import io.ktor.http.Url
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Single(binds = [ServerRegistry::class, CatalogueAccountEditor::class])
class ServerRegistryImpl(
    private val preferences: Preferences,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val authStateProviders: List<ServerAuthStateProvider>,
    // No default values on these: the generated Koin module leaves a parameter that has one
    // alone, and the registry would then clear stores and cancel work nobody else uses.
    @Provided private val opdsCredentials: OpdsCredentialStore,
    @Provided private val catalogueAccess: CatalogueAccessStore,
    @Provided private val catalogueWork: List<CatalogueWorkController>,
) : ServerRegistry, CatalogueAccountEditor {

    private val logger = Logger.withTag("ServerRegistry")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()

    // In-memory cache backed by preferences (scoped to active user)
    private val _servers = MutableStateFlow<Map<String, ServerConfig>>(emptyMap())
    private val _credentials = MutableStateFlow<Map<String, ServerCredentials>>(emptyMap())

    // Track current user to detect switches
    private var currentUserId: String? = null

    init {
        // Load data for initial active user
        loadForActiveUser()

        // React to user profile changes
        // Use getActiveProfileId() directly to avoid null emission when profile not yet in map
        userRegistry.observeActiveProfile()
            .map { it?.id }
            .distinctUntilChanged()
            .onEach { userId ->
                reloadForUserIfChanged(userId)
            }
            .launchIn(scope)
    }

    private fun loadForActiveUser() {
        // Called during init - no other coroutines running yet, safe without mutex
        val userId = userRegistry.getActiveProfileId()
        reloadForUserInternal(userId)
    }

    private suspend fun reloadForUserIfChanged(userId: String?) = mutex.withLock {
        if (userId != currentUserId) {
            reloadForUserInternal(userId)
        }
    }

    /**
     * Internal reload - must be called either:
     * 1. During init (no concurrency)
     * 2. While holding the mutex
     */
    private fun reloadForUserInternal(userId: String?) {
        currentUserId = userId

        if (userId == null) {
            // No active user, clear state
            _servers.value = emptyMap()
            _credentials.value = emptyMap()
            return
        }

        loadFromPreferences(userId)
    }

    private fun loadFromPreferences(userId: String) {
        // Load servers for this user
        val serversKey = PreferencesKey.UserScoped(userId, PreferencesKey.RegisteredServers.name)
        val catalogueKey = PreferencesKey.UserScoped(userId, PreferencesKey.CatalogueSources.name)
        val servers = preferences.getObject<List<ServerConfig>>(serversKey).orEmpty()
            .filter { it.type != ServerType.Opds } +
            preferences.getObject<List<ServerConfig>>(catalogueKey).orEmpty().filter { it.type == ServerType.Opds }
        _servers.value = servers.associateBy { it.id }

        // Load credentials for this user
        val credentialsKey = PreferencesKey.UserScoped(userId, PreferencesKey.ServerCredentials.name)
        val credentials = preferences.getObject<List<ServerCredentials>>(credentialsKey)
        if (credentials != null) {
            _credentials.value = credentials.filter { _servers.value[it.serverId]?.type != ServerType.Opds }.associateBy { it.serverId }
        } else {
            _credentials.value = emptyMap()
        }
    }

    // ==================== Server Management ====================

    override fun observeAllServers(): Flow<List<ServerConfig>> {
        return _servers.map { it.values.toList().sortedBy { server -> server.name } }
    }

    override suspend fun getAllServers(): List<ServerConfig> = mutex.withLock {
        ensureCurrentUser()
        _servers.value.values.toList()
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = addServerWithId(
        id = Uuid.random().toString(),
        name = name,
        type = type,
        baseUrl = baseUrl,
    )

    override suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = mutex.withLock {
        ensureCurrentUser()
        val previousType = _servers.value[id]?.type
        check(type != ServerType.Opds || previousType == null) { "Catalogue source id already exists" }
        check(previousType == null || (previousType == ServerType.Opds) == (type == ServerType.Opds)) {
            "Cannot change between library server and catalogue source"
        }
        val config = ServerConfig(
            id = id,
            name = name,
            type = type,
            baseUrl = if (type == ServerType.Opds) baseUrl else baseUrl.trimEnd('/'),
            addedAt = Clock.System.now().toEpochMilliseconds(),
        )

        val previousServers = _servers.value
        persistStateMutation(
            previousValue = previousServers,
            updatedValue = previousServers + (config.id to config),
            update = { _servers.value = it },
            persist = { persistServersFor(type) },
        )

        config
    }

    override suspend fun updateServer(config: ServerConfig) = mutex.withLock {
        ensureCurrentUser()
        if (config.type == ServerType.Opds) {
            val previous = _servers.value[config.id]
            check(previous?.type == ServerType.Opds) { "Catalogue source must already exist" }
            val profileId = currentUserId ?: error("No active profile")
            if (previous.baseUrl != config.baseUrl || previous.enabled && !config.enabled) {
                // Fail closed before publishing a retargeted address. Losing optional
                // credentials on a failed config write is safer than sending them elsewhere.
                cancelCatalogueWork(profileId, config.id)
                if (previous.baseUrl != config.baseUrl && !sameOrigin(previous.baseUrl, config.baseUrl)) opdsCredentials.remove(profileId, config.id)
            }
            persistStateMutation(
                previousValue = _servers.value,
                updatedValue = _servers.value + (config.id to config),
                update = { _servers.value = it },
                persist = ::persistCatalogueSources,
            )
            return@withLock
        }
        check(_servers.value[config.id]?.type != ServerType.Opds) { "Cannot change catalogue source type" }
        _servers.update { it + (config.id to config) }
        persistServers()
    }

    override suspend fun removeServer(serverId: String) = mutex.withLock {
        ensureCurrentUser()
        val server = _servers.value[serverId]
        if (server?.type == ServerType.Opds) {
            val profileId = currentUserId ?: error("No active profile")
            catalogueWork.forEach { it.forget(profileId, serverId) }
            persistStateMutation(
                previousValue = _servers.value,
                updatedValue = _servers.value - serverId,
                update = { _servers.value = it },
                persist = ::persistCatalogueSources,
            )
            opdsCredentials.remove(profileId, serverId)
            catalogueAccess.remove(profileId, serverId)
            return@withLock
        }
        authStateProviders.firstOrNull { provider -> provider.serverType == server?.type }
            ?.clearAuthentication(server ?: return@withLock)
        _servers.update { it - serverId }
        _credentials.update { it - serverId }

        persistServers()
        persistCredentials()
    }

    override suspend fun getServer(serverId: String): ServerConfig? = mutex.withLock {
        ensureCurrentUser()
        _servers.value[serverId]
    }

    // ==================== Authentication State ====================

    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> {
        return _servers.flatMapLatest { servers ->
            if (servers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val states = servers.values.map { server -> observeAuthState(server.id) }
                combine(states) { authStates ->
                    authStates.map { state -> state as ServerAuthState }
                        .associateBy { state -> state.serverId }
                }
            }
        }
    }

    override fun observeAuthState(serverId: String): Flow<ServerAuthState> {
        return _servers.map { servers -> servers[serverId] }
            .flatMapLatest { server ->
                if (server == null || server.type == ServerType.Opds) {
                    flowOf(ServerAuthState.NotAuthenticated(serverId))
                } else {
                    val provider = authStateProviders.firstOrNull { authProvider ->
                        authProvider.serverType == server.type
                    }
                    provider?.observeAuthState(server)
                        ?: _credentials.map { credentials ->
                            getAuthStateForServer(serverId, credentials[serverId])
                        }
                }
            }
    }

    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> {
        return combine(_servers, observeAllAuthStates()) { servers, authStates ->
            servers.values.filter { server ->
                authStates[server.id] is ServerAuthState.Authenticated
            }
        }
    }

    override suspend fun isAuthenticated(serverId: String): Boolean {
        val server = _servers.value[serverId] ?: return false
        if (server.type == ServerType.Opds) return false
        val provider = authStateProviders.firstOrNull { authProvider ->
            authProvider.serverType == server.type
        }
        return provider?.isAuthenticated(server)
            ?: _credentials.value.containsKey(serverId)
    }

    override suspend fun getAuthenticatedServers(): List<ServerConfig> {
        return _servers.value.values.filter { server -> isAuthenticated(server.id) }
    }

    private fun getAuthStateForServer(
        serverId: String,
        credential: ServerCredentials?
    ): ServerAuthState {
        if (credential == null) {
            return ServerAuthState.NotAuthenticated(serverId)
        }

        val expiresAt = credential.expiresAt
        val now = Clock.System.now().toEpochMilliseconds()
        if (expiresAt != null && expiresAt < now) {
            return ServerAuthState.TokenExpired(serverId, expiresAt)
        }

        return ServerAuthState.Authenticated(
            serverId = serverId,
            username = credential.username,
            authenticatedAt = now,
        )
    }

    // ==================== Credentials Management ====================

    override suspend fun saveCredentials(credentials: ServerCredentials) = mutex.withLock {
        ensureCurrentUser()
        val server = _servers.value[credentials.serverId]
        check(server?.type != ServerType.Opds) { "Catalogue credentials must use OpdsCredentialStore" }
        check(authStateProviders.none { provider -> provider.serverType == server?.type }) {
            "Managed servers do not store ServerCredentials"
        }
        val previousCredentials = _credentials.value
        persistStateMutation(
            previousValue = previousCredentials,
            updatedValue = previousCredentials + (credentials.serverId to credentials),
            update = { _credentials.value = it },
            persist = ::persistCredentials,
        )
    }

    override suspend fun getCredentials(serverId: String): ServerCredentials? {
        if (_servers.value[serverId]?.type == ServerType.Opds) return null
        return _credentials.value[serverId]
    }

    override suspend fun clearCredentials(serverId: String) = mutex.withLock {
        ensureCurrentUser()
        val server = _servers.value[serverId]
        if (server?.type == ServerType.Opds) {
            val profileId = currentUserId ?: return@withLock
            forgetCatalogueAccount(profileId, serverId)
            return@withLock
        }
        val provider = authStateProviders.firstOrNull { authProvider ->
            authProvider.serverType == server?.type
        }
        if (server != null && provider != null) {
            provider.clearAuthentication(server)
        }
        _credentials.update { it - serverId }
        persistCredentials()
    }

    override suspend fun saveAccount(sourceId: String, details: OpdsAccountDetails) = mutex.withLock {
        ensureCurrentUser()
        check(_servers.value[sourceId]?.type == ServerType.Opds) { "Account details belong to a catalogue source" }
        val profileId = currentUserId ?: error("No active profile")
        if (opdsCredentials.get(profileId, sourceId) == details) return@withLock
        // Stop first: nothing started under the old details may finish under the new ones.
        cancelCatalogueWork(profileId, sourceId)
        opdsCredentials.save(profileId, sourceId, details)
    }

    override suspend fun clearAllCredentials() = mutex.withLock {
        ensureCurrentUser()
        val servers = _servers.value.values.toList()
        servers.forEach { server ->
            if (server.type == ServerType.Opds) {
                currentUserId?.let { profileId -> forgetCatalogueAccount(profileId, server.id) }
            }
            authStateProviders.firstOrNull { provider -> provider.serverType == server.type }
                ?.clearAuthentication(server)
        }
        _credentials.value = emptyMap()
        persistCredentials()
    }

    override suspend fun deactivateServer(serverId: String) {
        val server = getServer(serverId)
        if (server?.type == ServerType.Opds) {
            updateServer(server.copy(enabled = false))
            return
        }
        clearCredentials(serverId)
    }

    // ==================== Persistence ====================

    private fun ensureCurrentUser() {
        val profileId = userRegistry.getActiveProfileId()
        if (profileId != currentUserId) reloadForUserInternal(profileId)
    }

    private suspend fun cancelCatalogueWork(profileId: String, sourceId: String) {
        catalogueWork.forEach { it.cancel(profileId, sourceId) }
    }

    /**
     * Signing out of a catalogue. One that never had account details has nothing private to
     * lose, so its downloads and saved pages are left alone.
     */
    private suspend fun forgetCatalogueAccount(profileId: String, sourceId: String) {
        if (opdsCredentials.get(profileId, sourceId) == null) return
        catalogueWork.forEach { it.forget(profileId, sourceId) }
        opdsCredentials.remove(profileId, sourceId)
    }

    private fun sameOrigin(first: String, second: String): Boolean = try {
        val a = Url(first)
        val b = Url(second)
        a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun persistServers() {
        val userId = currentUserId ?: return
        val serversList = _servers.value.values.filter { it.type != ServerType.Opds }
        val key = PreferencesKey.UserScoped(userId, PreferencesKey.RegisteredServers.name)
        preferences.putObject(key, serversList)
    }

    private fun persistServersFor(type: ServerType) {
        if (type == ServerType.Opds) persistCatalogueSources() else persistServers()
    }

    private fun persistCatalogueSources() {
        val userId = currentUserId ?: return
        val key = PreferencesKey.UserScoped(userId, PreferencesKey.CatalogueSources.name)
        preferences.putObject(key, _servers.value.values.filter { it.type == ServerType.Opds })
    }

    private fun persistCredentials() {
        val userId = currentUserId ?: return
        val credentialsList = _credentials.value.values.toList()
        val key = PreferencesKey.UserScoped(userId, PreferencesKey.ServerCredentials.name)
        preferences.putObject(key, credentialsList)
    }
}
