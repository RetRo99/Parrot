package com.retro99.server.implementation

import com.retro99.server.api.*
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CatalogueRepositoryProvider::class])
class CatalogueRepositoryProviderImpl(
    @Provided private val registry: ServerRegistry,
    @Provided private val factory: CatalogueRepositoryFactory,
    @Provided private val sources: CatalogueAccessProvider,
) : CatalogueRepositoryProvider {
    private fun supports(source: ServerConfig) = source.enabled && source.type.getCapabilities().supportsCatalogueBrowsing
    override fun observeRepositories() = sources.observeSources().map { sources -> sources.map { it.config }.filter(::supports).map(factory::create) }
    override suspend fun getRepositories() = registry.getAllServers().filter(::supports).map(factory::create)
    override suspend fun getRepository(sourceId: String) = registry.getServer(sourceId)?.takeIf(::supports)?.let(factory::create)
}
