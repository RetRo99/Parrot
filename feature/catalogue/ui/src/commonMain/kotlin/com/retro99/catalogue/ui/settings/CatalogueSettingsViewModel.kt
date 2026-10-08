package com.retro99.catalogue.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retro99.catalogue.ui.add.CatalogueAddressValidator
import com.retro99.catalogue.ui.add.CatalogueHttpPolicy
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.server.api.*
import com.retro99.user.api.UserRegistry
import com.retro99.user.api.ProfileWorkRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.koin.core.annotation.*

@Single(binds = [CatalogueSettingsGateway::class])
class RegistryCatalogueSettingsGateway(
    @Provided private val registry: ServerRegistry,
    @Provided private val users: UserRegistry,
    @Provided private val access: CatalogueAccessProvider,
    @Provided private val accounts: CatalogueAccountEditor,
    @Provided private val credentials: OpdsCredentialStore,
    @Provided private val books: CatalogueBookSourcesDatabase,
    @Provided private val session: ProfileDatabaseSession,
    @Provided private val repositories: CatalogueRepositoryProvider,
    @Provided private val profileWork: ProfileWorkRegistry,
    @Provided private val statusStore: CatalogueAccessStore,
) : CatalogueSettingsGateway {
    override fun observeSource(sourceId: String) = combine(users.observeActiveProfile(), access.observeSources()) { profile, sources ->
        val source = sources.firstOrNull { it.config.id == sourceId }
        if (profile == null || source == null) null else CatalogueSettingsSource(
            profile.id, source.config, source.status, credentials.get(profile.id, sourceId)?.username,
        )
    }.distinctUntilChanged()

    private suspend fun <T> fenced(source: CatalogueSettingsSource, block: suspend (ServerConfig) -> T): T {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val key = Any()
        check(profileWork.register(source.profileId, key) { job.cancel() })
        try {
            currentCoroutineContext().ensureActive()
            check(users.getActiveProfileId() == source.profileId)
            val config = checkNotNull(registry.getServer(source.config.id))
            currentCoroutineContext().ensureActive()
            check(users.getActiveProfileId() == source.profileId && config.type == ServerType.Opds && config.baseUrl == source.config.baseUrl)
            // Registry cleanup itself uses the database session: never nest its lock here.
            return block(config)
        } finally {
            profileWork.unregister(source.profileId, key)
        }
    }

    override suspend fun existingAddresses(profileId: String, sourceId: String): Set<String> {
        check(users.getActiveProfileId() == profileId)
        val all = registry.getAllServers()
        check(users.getActiveProfileId() == profileId)
        return all.filter { it.type == ServerType.Opds && it.id != sourceId }.mapTo(mutableSetOf()) { it.baseUrl }
    }

    override suspend fun updateAddress(source: CatalogueSettingsSource, address: String, account: OpdsAccountDetails?, validation: com.retro99.catalogue.ui.add.CatalogueValidation) = fenced(source) { config ->
        // The registry owns origin-change credential clearing and cache/download cancellation.
        registry.updateServer(config.copy(baseUrl = address))
        check(users.getActiveProfileId() == source.profileId)
        if (account != null) accounts.saveAccount(config.id, account)
        currentCoroutineContext().ensureActive()
        check(users.getActiveProfileId() == source.profileId)
        val at = Clock.System.now().toEpochMilliseconds()
        when (validation) {
            is com.retro99.catalogue.ui.add.CatalogueValidation.Accepted -> statusStore.recordSuccess(source.profileId, config.id, account?.username, at, isRoot = true)
            is com.retro99.catalogue.ui.add.CatalogueValidation.Unsupported -> statusStore.recordFailure(source.profileId, config.id, CatalogueErrorKind.SignInUnsupported, at, validation.rootAnswered401)
            else -> error("Only a validated catalogue address can be saved")
        }
    }
    override suspend fun saveAccount(source: CatalogueSettingsSource, account: OpdsAccountDetails) = fenced(source) { saveValidatedAccount(source, account) }
    private suspend fun saveValidatedAccount(source: CatalogueSettingsSource, account: OpdsAccountDetails) {
        accounts.saveAccount(source.config.id, account)
        currentCoroutineContext().ensureActive()
        check(users.getActiveProfileId() == source.profileId)
        statusStore.recordSuccess(source.profileId, source.config.id, account.username, Clock.System.now().toEpochMilliseconds(), isRoot = true)
    }
    override suspend fun removeAccount(source: CatalogueSettingsSource) = fenced(source) { registry.clearCredentials(it.id) }
    override suspend fun setEnabled(source: CatalogueSettingsSource, enabled: Boolean) = fenced(source) { registry.updateServer(it.copy(enabled = enabled)) }
    override suspend fun countBooks(source: CatalogueSettingsSource) = fenced(source) { config ->
        session.withProfile(source.profileId) { books.countBooksForSource(config.id) }
    }
    override suspend fun removeCatalogue(source: CatalogueSettingsSource) = fenced(source) { registry.removeServer(it.id) }
    override suspend fun retry(source: CatalogueSettingsSource) {
        fenced(source) {
            check(it.enabled)
            repositories.getRepository(source.config.id)?.getRoot()
        }
    }
}

@KoinViewModel
class CatalogueSettingsViewModel(
    @InjectedParam sourceId: String,
    @Provided gateway: CatalogueSettingsGateway,
    @Provided validator: CatalogueAddressValidator,
) : ViewModel() {
    val controller = CatalogueSettingsController(sourceId, gateway, validator, CatalogueHttpPolicy.allowHttp)
    init { viewModelScope.launch { controller.observe() } }
    override fun onCleared() { controller.close() }
}
