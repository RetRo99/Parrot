package com.retro99.server.implementation

import com.retro99.server.api.*
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CatalogueAccessProvider::class])
class CatalogueAccessProviderImpl(
    @Provided private val registry: ServerRegistry,
    @Provided private val users: UserRegistry,
    @Provided private val store: CatalogueAccessStore,
) : CatalogueAccessProvider {
    override fun observeAll(): Flow<Map<String, CatalogueAccessStatus>> =
        users.observeActiveProfile().flatMapLatest { profile ->
            if (profile == null) flowOf(emptyMap()) else combine(
                registry.observeAllServers(), store.observe(profile.id),
            ) { sources, states ->
                sources.filter { it.type.getCapabilities().supportsCatalogueBrowsing }.associate { source ->
                    val status = states[source.id] ?: CatalogueAccessStatus()
                    source.id to if (source.enabled) status else status.copy(access = ServerAccessState.TurnedOff)
                }
            }
        }
}
