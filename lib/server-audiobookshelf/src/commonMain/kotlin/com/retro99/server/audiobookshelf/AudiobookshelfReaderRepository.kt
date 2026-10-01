package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.map
import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaProgressApiModel
import com.retro99.server.audiobookshelf.model.toServerPosition
import retro99.network.api.get

/** @param trackDurationsMs each audio file's length cached for a library item, or null. */
class AudiobookshelfReaderRepository(
    private val networkClient: ServerNetworkClient,
    private val localSource: ServerPositionLocalSource,
    private val trackDurationsMs: suspend (bookUuid: String) -> List<Long>? = { _ -> null },
) : ServerReaderRepository, BaseRepository {

    override val serverId: String = networkClient.serverId

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return remoteWithCacheFallback(
            remoteSource = { getRemotePosition(bookUuid) },
            cacheSource = { getLocalPosition(bookUuid) },
            saveToCache = { position -> localSource.savePosition(position) },
        )
    }

    override suspend fun saveLocalPositionWithSync(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult {
        return localSource.savePositionWithSync(
            position = position.copy(
                bookUuid = bookUuid,
                serverId = serverId,
            ),
            remoteAccountId = serverId,
        )
    }

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
        return localSource.getPosition(bookUuid).map { position ->
            position?.copy(serverId = serverId)
        }
    }

    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult {
        return localSource.savePosition(position)
    }

    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
        val durations = trackDurationsMs(bookUuid)
        return networkClient.get<AudiobookshelfMediaProgressApiModel?>(
            path = "/api/me/progress/$bookUuid",
        ).map { apiModel ->
            apiModel?.toServerPosition(bookUuid, serverId, durations)
        }
    }
}
