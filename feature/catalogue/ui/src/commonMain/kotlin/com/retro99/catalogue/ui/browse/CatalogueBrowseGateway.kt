package com.retro99.catalogue.ui.browse

import com.retro99.catalogue.ui.navigation.CatalogueRouteReferences
import com.retro99.server.api.CatalogueAccessProvider
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.CatalogueSourceStatus
import com.retro99.server.api.CatalogueAccountVerifier
import com.retro99.server.api.CatalogueTarget
import com.retro99.server.api.CatalogueDocument
import com.retro99.base.result.AppResult
import com.retro99.base.result.AppError
import com.github.michaelbull.result.Err
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerAccessState
import com.retro99.server.api.ServerCatalogueRepository
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** The catalogue a browser screen shows, while it can be browsed. */
data class CatalogueBrowseSource(val profileId: String, val name: String, val address: String) {
    override fun toString() = "CatalogueBrowseSource(redacted)"
}

/** Everything the browser needs from outside the screen. */
interface CatalogueBrowseGateway {
    /**
     * The catalogue [sourceId] of the open profile; null as soon as it is turned off or removed
     * or belongs to no open profile.
     */
    fun observeSource(sourceId: String): Flow<CatalogueBrowseSource?>

    /** The catalogue's session, or null when it cannot be browsed (any more). */
    suspend fun repository(sourceId: String): ServerCatalogueRepository?
    suspend fun saveAccount(sourceId: String, details: OpdsAccountDetails)
    suspend fun checkAccount(sourceId: String, target: CatalogueTarget?, details: OpdsAccountDetails): AppResult<CatalogueDocument>
}

@Single(binds = [CatalogueBrowseGateway::class])
class RegistryCatalogueBrowseGateway(
    @Provided private val users: UserRegistry,
    @Provided private val sources: CatalogueAccessProvider,
    @Provided private val repositories: CatalogueRepositoryProvider,
    @Provided private val accountEditor: CatalogueAccountEditor,
    private val references: CatalogueRouteReferences,
) : CatalogueBrowseGateway {
    private val watching = MutableStateFlow(false)

    override fun observeSource(sourceId: String): Flow<CatalogueBrowseSource?> {
        watchReferences()
        return combine(users.observeActiveProfile(), sources.observeSources()) { profile, all ->
            val source = all.firstOrNull { it.config.id == sourceId && it.config.enabled && it.status.access != ServerAccessState.TurnedOff }
            if (profile == null || source == null) null else CatalogueBrowseSource(profile.id, source.config.name, source.config.baseUrl)
        }.distinctUntilChanged()
    }

    override suspend fun repository(sourceId: String): ServerCatalogueRepository? = repositories.getRepository(sourceId)

    override suspend fun saveAccount(sourceId: String, details: OpdsAccountDetails) = accountEditor.saveAccount(sourceId, details)

    override suspend fun checkAccount(sourceId: String, target: CatalogueTarget?, details: OpdsAccountDetails): AppResult<CatalogueDocument> =
        (repository(sourceId) as? CatalogueAccountVerifier)?.checkAccount(target, details)
            ?: Err(AppError.ApiError(400, "SignInUnsupported"))

    /**
     * Route references are made only by browser screens, and every browser screen observes its
     * catalogue through here first, so this starts before the first reference exists.
     */
    private fun watchReferences() {
        if (!watching.compareAndSet(expect = false, update = true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            forgetStaleCatalogueReferences(references, users.observeActiveProfile().distinctUntilChanged { old, new -> old?.id == new?.id }, sources.observeSources())
        }
    }
}

/**
 * Keeps [references] in step with the catalogues: a profile change forgets everything, and a
 * catalogue that is turned off, removed or moved to another address loses its references.
 */
internal suspend fun forgetStaleCatalogueReferences(
    references: CatalogueRouteReferences,
    profiles: Flow<*>,
    sources: Flow<List<CatalogueSourceStatus>>,
) {
    coroutineScope {
        // The first value is the profile that is open now; only a change after it clears.
        launch { profiles.drop(1).collect { references.clear() } }
        launch {
            var browsable = emptyMap<String, String>()
            sources.collect { all ->
                val now = all.filter { it.config.enabled && it.status.access != ServerAccessState.TurnedOff }.associate { it.config.id to it.config.baseUrl }
                browsable.filter { (id, address) -> now[id] != address }.keys.forEach(references::forget)
                browsable = now
            }
        }
    }
}
