package com.retro99.server.implementation

import com.retro99.server.api.*
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CatalogueRepositoryFactory::class])
class CompositeServerCatalogueRepositoryFactory(@Provided factories: List<ServerCatalogueRepositoryFactory>) :
    CompositeRepositoryFactory<ServerCatalogueRepositoryFactory, ServerCatalogueRepository>(factories), CatalogueRepositoryFactory {
    override val factoryName = "catalogue factory"
}
