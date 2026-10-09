package com.retro99.server.implementation

import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.BooksRepositoryFactory
import com.retro99.server.api.ReaderRepositoryFactory
import com.retro99.server.api.SeriesRepositoryFactory
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.getCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [AuthenticatedRepositoryProvider::class])
class AuthenticatedRepositoryProviderImpl(
    private val serverRegistry: ServerRegistry,
    private val booksRepositoryFactory: BooksRepositoryFactory,
    private val readerRepositoryFactory: ReaderRepositoryFactory,
    private val seriesRepositoryFactory: SeriesRepositoryFactory,
) : AuthenticatedRepositoryProvider {

    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> {
        return serverRegistry.observeAuthenticatedServers()
            .map { servers ->
                servers.filter { it.type.getCapabilities().contributesToLibrary }.map { server ->
                    booksRepositoryFactory.create(server)
                }
            }
    }

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> {
        val servers = serverRegistry.getAuthenticatedServers()
        return servers.filter { it.type.getCapabilities().contributesToLibrary }.map { booksRepositoryFactory.create(it) }
    }

    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? {
        if (!serverRegistry.isAuthenticated(serverId)) {
            return null
        }
        val server = serverRegistry.getServer(serverId) ?: return null
        if (!server.type.getCapabilities().contributesToLibrary) return null
        return booksRepositoryFactory.create(server)
    }

    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? {
        if (!serverRegistry.isAuthenticated(serverId)) {
            return null
        }
        val server = serverRegistry.getServer(serverId) ?: return null
        if (!server.type.getCapabilities().supportsReaderRepository) return null
        return readerRepositoryFactory.create(server)
    }

    // ==================== Series Repositories ====================

    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> {
        return serverRegistry.observeAuthenticatedServers()
            .map { servers ->
                servers.filter { server -> server.type.getCapabilities().supportsSeries }
                    .map { server -> seriesRepositoryFactory.create(server) }
            }
    }

    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> {
        val servers = serverRegistry.getAuthenticatedServers()
        return servers.filter { server -> server.type.getCapabilities().supportsSeries }
            .map { server -> seriesRepositoryFactory.create(server) }
    }
}
