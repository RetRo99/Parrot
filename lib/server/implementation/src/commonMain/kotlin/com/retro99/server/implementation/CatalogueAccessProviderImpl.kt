package com.retro99.server.implementation

import com.retro99.server.api.*
import com.retro99.user.api.UserRegistry
import com.retro99.preferences.api.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CatalogueAccessProvider::class])
class CatalogueAccessProviderImpl(
    private val preferences: Preferences,
    @Provided private val users: UserRegistry,
    @Provided private val store: CatalogueAccessStore,
) : CatalogueAccessProvider {
    override fun observeSources(): Flow<List<CatalogueSourceStatus>> =
        users.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList()) else combine(
                preferences.observeObject<List<ServerConfig>>(PreferencesKey.UserScoped(profile.id, PreferencesKey.CatalogueSources.name)), store.observe(profile.id),
            ) { sources, states ->
                sources.orEmpty().filter { it.type.getCapabilities().supportsCatalogueBrowsing }.map { source ->
                    val status = states[source.id] ?: CatalogueAccessStatus()
                    CatalogueSourceStatus(source, if (source.enabled) status else status.copy(access = ServerAccessState.TurnedOff))
                }
            }
        }
}
