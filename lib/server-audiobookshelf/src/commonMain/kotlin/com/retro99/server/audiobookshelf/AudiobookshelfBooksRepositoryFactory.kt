package com.retro99.server.audiobookshelf

import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerBooksRepositoryFactory
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerType
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ServerBooksRepositoryFactory::class])
class AudiobookshelfBooksRepositoryFactory(
    @Provided private val networkClientFactory: ServerNetworkClientProvider,
    @Provided private val localSource: ServerBooksLocalSource,
    @Provided private val identityBindingStore: AudiobookshelfLibraryIdentityBindingProvider,
) : ServerBooksRepositoryFactory {

    override val serverType: ServerType = ServerType.Audiobookshelf

    override fun create(serverConfig: ServerConfig): ServerBooksRepository {
        require(serverConfig.type == ServerType.Audiobookshelf) {
            "AudiobookshelfBooksRepositoryFactory can only create " +
                "repositories for Audiobookshelf servers"
        }
        val networkClient = networkClientFactory.create(serverConfig)
        return AudiobookshelfBooksRepository(
            networkClient = networkClient,
            localSource = localSource,
            identityBindingStore = identityBindingStore,
        )
    }
}
