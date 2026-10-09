package com.retro99.server.implementation

import com.retro99.base.result.AppResult
import com.retro99.server.api.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueRepositoryProviderTest {
    @Test fun enabled_registered_public_sources_do_not_need_authentication_or_library_factories() = runTest {
        val registry = registryWithOwnStores(RegistryPreferences(), RegistryUser("a"))
        registry.addServerWithId("library", "Library", ServerType.Storyteller, "https://library.example")
        val public = registry.addServerWithId("public", "Books", ServerType.Opds, "https://books.example/opds/")
        val off = registry.addServerWithId("off", "Off", ServerType.Opds, "https://books.example/other/")
        registry.updateServer(off.copy(enabled = false))
        val guarded = object : ServerRegistry by registry {
            override fun observeAuthenticatedServers() = error("Catalogue provider must not observe authentication")
        }
        val calls = mutableListOf<String>()
        val factory = object : CatalogueRepositoryFactory {
            override fun create(serverConfig: ServerConfig): ServerCatalogueRepository {
                assertEquals(ServerType.Opds, serverConfig.type)
                calls += serverConfig.id
                return StubCatalogue(serverConfig.id)
            }
        }
        val sources = object : CatalogueAccessProvider {
            override fun observeSources() = registry.observeAllServers().map { it.map { CatalogueSourceStatus(it, CatalogueAccessStatus()) } }
        }
        val provider = CatalogueRepositoryProviderImpl(guarded, factory, sources)
        assertEquals(listOf(public.id), provider.observeRepositories().first().map { it.serverId })
        assertEquals(listOf(public.id), provider.getRepositories().map { it.serverId })
        assertNull(provider.getRepository("library"))
        assertNull(provider.getRepository("off"))
        assertEquals(listOf("public", "public"), calls)
        assertTrue(registry.getAuthenticatedServers().isEmpty())
    }

    @Test fun composite_uses_only_the_catalogue_specific_factory() {
        val factory = object : ServerCatalogueRepositoryFactory {
            override val serverType = ServerType.Opds
            override fun create(serverConfig: ServerConfig) = StubCatalogue(serverConfig.id)
        }
        val source = ServerConfig("source", "Books", ServerType.Opds, "https://books.example/", 0)
        assertEquals("source", CompositeServerCatalogueRepositoryFactory(listOf(factory)).create(source).serverId)
    }
}

private class StubCatalogue(override val serverId: String) : ServerCatalogueRepository {
    override suspend fun getRoot(): AppResult<CatalogueDocument> = error("unused")
    override suspend fun getDocument(target: CatalogueTarget): AppResult<CatalogueDocument> = error("unused")
    override suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?> = error("unused")
    override suspend fun search(search: CatalogueSearch, query: CatalogueQuery): AppResult<CatalogueDocument> = error("unused")
}
