package com.retro99.server.opds

import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.preferences.api.*
import com.retro99.server.api.*
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.HttpClientEngineFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerCatalogueRepositoryFactory::class, CatalogueWorkController::class])
class OpdsCatalogueRepositoryFactory(
    @Provided private val engines: HttpClientEngineFactory<*>,
    @Provided private val users: UserRegistry,
    @Provided private val credentials: OpdsCredentialStore,
    @Provided private val access: CatalogueAccessStore,
    private val preferences: Preferences,
) : ServerCatalogueRepositoryFactory, CatalogueWorkController {
    override val serverType = ServerType.Opds
    private data class Key(val profileId: String, val sourceId: String)
    private data class Session(val repository: OpdsCatalogueRepository, val account: OpdsAccountDetails?)
    private val sessions = MutableStateFlow<Map<Key, Session>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeProfileId = users.getActiveProfileId()

    init {
        users.observeActiveProfile().map { it?.id }.distinctUntilChanged().onEach { profileId ->
            if (profileId != activeProfileId) {
                activeProfileId = profileId
                val old = sessions.getAndUpdate { it.filterKeys { it.profileId == profileId } }
                old.filterKeys { it.profileId != profileId }.values.forEach { it.repository.dispose() }
            }
        }.launchIn(scope)
    }

    private fun registered(profileId: String, config: ServerConfig): Boolean =
        preferences.getObject<List<ServerConfig>>(PreferencesKey.UserScoped(profileId, PreferencesKey.CatalogueSources.name))
            .orEmpty().any { it == config && it.enabled && it.type == ServerType.Opds }

    override fun create(serverConfig: ServerConfig): ServerCatalogueRepository {
        require(serverConfig.type == ServerType.Opds && serverConfig.enabled) { "Unsupported catalogue source" }
        val profileId = users.getActiveProfileId() ?: error("No active profile")
        check(registered(profileId, serverConfig)) { "Catalogue source is not registered for this profile" }
        val account = credentials.get(profileId, serverConfig.id)
        val key = Key(profileId, serverConfig.id)
        while (true) {
            val current = sessions.value
            val previous = current[key]
            if (previous?.repository?.config == serverConfig && previous.account == account) return previous.repository
            val repository = OpdsCatalogueRepository(profileId, serverConfig,
                KtorOpdsTransport(engines.create(), serverConfig.baseUrl), credentials, access,
                { users.getActiveProfileId() == profileId && registered(profileId, serverConfig) && credentials.get(profileId, serverConfig.id) == account })
            if (sessions.compareAndSet(current, current + (key to Session(repository, account)))) {
                previous?.repository?.stop()
                if (previous != null) scope.launch { previous.repository.dispose() }
                return repository
            }
            repository.stop()
        }
    }

    override suspend fun cancel(profileId: String, sourceId: String) {
        val key = Key(profileId, sourceId)
        val previous = sessions.getAndUpdate { it - key }[key]
        previous?.repository?.dispose()
    }
}
