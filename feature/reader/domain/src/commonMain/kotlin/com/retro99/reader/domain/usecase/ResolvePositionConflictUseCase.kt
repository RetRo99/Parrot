package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.nowMillis
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for resolving a position conflict by choosing either local or remote position.
 *
 * When choosing local: syncs local position to remote server.
 * When choosing remote: overwrites local position with remote position.
 */
@Factory
class ResolvePositionConflictUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {
    /**
     * Resolves the conflict by using the local position.
     * This syncs the local position to the remote server.
     *
     * @param serverId The server ID
     * @param bookUuid The book UUID
     * @return Success if the position was synced, error otherwise
     */
    suspend fun useLocal(serverId: String, bookUuid: String): CompletableResult {
        val serverRepository = repositoryProvider.getReaderRepository(serverId)
            ?: return Err(AppError.NotFoundError("Server not found: $serverId"))

        val localPosition = serverRepository.getLocalPosition(bookUuid)
            .getOrElse { return Err(it) }
            ?: return Err(AppError.NotFoundError("No local position found"))

        // Update timestamp to current time - the server rejects positions with older timestamps
        val positionWithCurrentTimestamp = localPosition.copy(
            timestamp = nowMillis()
        )

        return serverRepository.saveLocalPositionWithSync(bookUuid, positionWithCurrentTimestamp)
    }

    /**
     * Resolves the conflict by using the remote position.
     * This fetches the remote position and saves it to local storage only.
     *
     * @param serverId The server ID
     * @param bookUuid The book UUID
     * @return Success if the position was saved locally, error otherwise
     */
    suspend fun useRemote(serverId: String, bookUuid: String): CompletableResult {
        val serverRepository = repositoryProvider.getReaderRepository(serverId)
            ?: return Err(AppError.NotFoundError("Server not found: $serverId"))

        val remotePosition = serverRepository.getRemotePosition(bookUuid)
            .getOrElse { return Err(it) }
            ?: return Err(AppError.NotFoundError("No remote position found"))

        // Save remote position to local only - don't sync back to server
        // (the server already has this position, re-posting would cause timestamp conflicts)
        return serverRepository.saveLocalPosition(
            remotePosition.copy(
                origin = PositionOrigin.Remote,
                observedAt = remotePosition.observedAt
                    ?: remotePosition.updatedAt
                    ?: remotePosition.createdAt,
            ),
        )
    }
}
