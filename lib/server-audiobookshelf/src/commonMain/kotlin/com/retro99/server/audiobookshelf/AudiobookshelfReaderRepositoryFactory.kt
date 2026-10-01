package com.retro99.server.audiobookshelf

import com.retro99.database.api.books.BooksDatabase
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerReaderRepositoryFactory
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerType
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerReaderRepositoryFactory::class])
class AudiobookshelfReaderRepositoryFactory(
    @Provided private val networkClientFactory: ServerNetworkClientProvider,
    @Provided private val localSource: ServerPositionLocalSource,
    @Provided private val booksDatabase: BooksDatabase,
) : ServerReaderRepositoryFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerReaderRepository {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfReaderRepositoryFactory can only create repositories for Audiobookshelf servers"
        }
        val networkClient = networkClientFactory.create(serverConfig)
        return AudiobookshelfReaderRepository(networkClient, localSource) { bookUuid ->
            booksDatabase.getBookByServerAndUuid(networkClient.serverId, bookUuid)
                ?.audioTrackDurationsMs
        }
    }
}
