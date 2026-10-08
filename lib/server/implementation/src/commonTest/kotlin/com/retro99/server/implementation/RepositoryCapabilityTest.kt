package com.retro99.server.implementation

import com.retro99.base.result.*
import com.retro99.server.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RepositoryCapabilityTest {
    @Test fun catalogue_network_lookup_does_not_dispatch_to_bearer_factory() = runTest {
        val registry = ServerRegistryImpl(RegistryPreferences(), RegistryUser("profile-a"), emptyList())
        registry.addServerWithId("opds", "Catalogue", ServerType.Opds, "https://example.org/opds/")
        val clients = CompositeNetworkClientFactory(emptyList(), registry)
        assertNull(clients.createForServerId("opds"))
    }
    @Test fun catalogue_never_reaches_factories_even_when_reported_authenticated() = runTest {
        val registry = ServerRegistryImpl(RegistryPreferences(), RegistryUser("profile-a"), emptyList())
        val types = listOf(ServerType.Local, ServerType.Storyteller, ServerType.Audiobookshelf, ServerType.ParrotCloud, ServerType.Opds)
        val servers = types.map { registry.addServerWithId(it.identifier, it.displayName, it, "https://example.org") }
        val authenticated = object : ServerRegistry by registry {
            override fun observeAuthenticatedServers() = flowOf(servers)
            override suspend fun getAuthenticatedServers() = servers
            override suspend fun isAuthenticated(serverId: String) = true
        }
        val booksCalls = mutableListOf<String>()
        val readerCalls = mutableListOf<String>()
        val seriesCalls = mutableListOf<String>()
        val provider = AuthenticatedRepositoryProviderImpl(authenticated,
            object : BooksRepositoryFactory {
                override fun create(serverConfig: ServerConfig): ServerBooksRepository {
                    assertNotEquals(ServerType.Opds, serverConfig.type)
                    booksCalls += serverConfig.id
                    return TestBooks(serverConfig.id)
                }
            },
            object : ReaderRepositoryFactory {
                override fun create(serverConfig: ServerConfig): ServerReaderRepository {
                    assertNotEquals(ServerType.Opds, serverConfig.type)
                    readerCalls += serverConfig.id
                    return TestReader(serverConfig.id)
                }
            },
            object : SeriesRepositoryFactory {
                override fun create(serverConfig: ServerConfig): ServerSeriesRepository {
                    assertNotEquals(ServerType.Opds, serverConfig.type)
                    seriesCalls += serverConfig.id
                    return object : ServerSeriesRepository {
                        override val serverId = serverConfig.id
                        override fun getSeries(): Flow<AppResult<List<ServerSeries>>> = error("unused")
                    }
                }
            })
        val libraryIds = servers.filter { it.type != ServerType.Opds }.map { it.id }
        assertEquals(libraryIds, provider.getBooksRepositories().map { it.serverId })
        assertEquals(libraryIds, provider.observeBooksRepositories().first().map { it.serverId })
        for (server in servers.filter { it.type != ServerType.Opds }) {
            assertEquals(server.id, provider.getBooksRepository(server.id)?.serverId)
            assertEquals(server.id, provider.getReaderRepository(server.id)?.serverId)
        }
        assertNull(provider.getBooksRepository("opds"))
        assertNull(provider.getReaderRepository("opds"))
        val seriesIds = listOf("storyteller", "audiobookshelf")
        assertEquals(seriesIds, provider.getSeriesRepositories().map { it.serverId })
        assertEquals(seriesIds, provider.observeSeriesRepositories().first().map { it.serverId })
        assertEquals(libraryIds + libraryIds + libraryIds, booksCalls)
        assertEquals(libraryIds, readerCalls)
        assertEquals(seriesIds + seriesIds, seriesCalls)
    }
}

private class TestBooks(override val serverId: String) : ServerBooksRepository {
    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = error("unused")
    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = error("unused")
    override suspend fun saveBook(book: ServerBook): CompletableResult = error("unused")
    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = error("unused")
}
private class TestReader(override val serverId: String) : ServerReaderRepository {
    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> = error("unused")
    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> = error("unused")
    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult = error("unused")
    override suspend fun saveLocalPositionWithSync(bookUuid: String, position: ServerPosition): CompletableResult = error("unused")
    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> = error("unused")
}
