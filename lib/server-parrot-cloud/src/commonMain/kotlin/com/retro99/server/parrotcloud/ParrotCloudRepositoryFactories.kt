package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Ok
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerBooksRepositoryFactory
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerReaderRepositoryFactory
import com.retro99.server.api.ServerSeries
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerSeriesRepositoryFactory
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerBooksRepositoryFactory::class])
class ParrotCloudBooksRepositoryFactory(
    @Provided private val booksRepository: ParrotCloudBooksRepositoryDependencies,
) : ServerBooksRepositoryFactory {
    override val serverType: ServerType = ServerType.ParrotCloud

    override fun create(serverConfig: ServerConfig): ServerBooksRepository {
        require(serverConfig.type == serverType)
        return ParrotCloudBooksRepository(
            serverConfig = serverConfig,
            libraryBooksDatabase = booksRepository.libraryBooksDatabase,
            syncOutboxDatabase = booksRepository.syncOutboxDatabase,
            cloudFilesDatabase = booksRepository.cloudFilesDatabase,
            importedBooksDatabase = booksRepository.importedBooksDatabase,
            cloudProfileLinkRepository = booksRepository.cloudProfileLinkRepository,
            userRegistry = booksRepository.userRegistry,
        )
    }
}

@Single
class ParrotCloudBooksRepositoryDependencies(
    @Provided val libraryBooksDatabase: com.retro99.database.api.library.LibraryBooksDatabase,
    @Provided val syncOutboxDatabase: com.retro99.database.api.sync.SyncOutboxDatabase,
    @Provided val cloudFilesDatabase: com.retro99.database.api.cloudfiles.CloudFilesDatabase,
    @Provided val importedBooksDatabase: com.retro99.database.api.importedbooks.ImportedBooksDatabase,
    @Provided val cloudProfileLinkRepository: CloudProfileLinkRepository,
    @Provided val userRegistry: com.retro99.user.api.UserRegistry,
)

@Single(binds = [ServerReaderRepositoryFactory::class])
class ParrotCloudReaderRepositoryFactory(
    @Provided private val positionDatabase: com.retro99.database.api.books.PositionDatabase,
    @Provided private val libraryBooksDatabase: com.retro99.database.api.library.LibraryBooksDatabase,
    @Provided private val syncOutboxDatabase: com.retro99.database.api.sync.SyncOutboxDatabase,
) : ServerReaderRepositoryFactory {
    override val serverType: ServerType = ServerType.ParrotCloud

    override fun create(serverConfig: ServerConfig): ServerReaderRepository {
        require(serverConfig.type == serverType)
        return ParrotCloudReaderRepository(
            serverId = serverConfig.id,
            positionDatabase = positionDatabase,
            libraryBooksDatabase = libraryBooksDatabase,
            syncOutboxDatabase = syncOutboxDatabase,
        )
    }
}

@Single(binds = [ServerSeriesRepositoryFactory::class])
class ParrotCloudSeriesRepositoryFactory : ServerSeriesRepositoryFactory {
    override val serverType: ServerType = ServerType.ParrotCloud

    override fun create(serverConfig: ServerConfig): ServerSeriesRepository {
        require(serverConfig.type == serverType)
        return EmptyParrotCloudSeriesRepository(serverConfig.id)
    }
}

private class EmptyParrotCloudSeriesRepository(
    override val serverId: String,
) : ServerSeriesRepository {
    override fun getSeries(): Flow<com.retro99.base.result.AppResult<List<ServerSeries>>> {
        return flowOf(Ok(emptyList()))
    }
}
