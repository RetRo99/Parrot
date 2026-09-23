package com.retro99.server.local

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository

/**
 * Local implementation of ServerReaderRepository.
 * Local books don't have remote position sync - positions are only stored locally.
 * All operations use the local source directly.
 */
class LocalReaderRepository(
    override val serverId: String,
    private val localSource: ServerPositionLocalSource,
) : ServerReaderRepository {

    // ==================== Combined Operations ====================

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        // Local books only have local storage - no remote
        return getLocalPosition(bookUuid)
    }

    override suspend fun saveLocalPositionWithSync(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult {
        // Local books only save to local storage - no remote sync
        return localSource.savePosition(position)
    }

    // ==================== Local-only Operations ====================

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
        return localSource.getPosition(bookUuid).map { position ->
            // Set the serverId since it's not stored in the database
            position?.copy(serverId = serverId)
        }
    }

    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult {
        return localSource.savePosition(position)
    }

    // ==================== Remote-only Operations ====================

    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
        // Local books don't have remote position sync
        return Ok(null)
    }
}
