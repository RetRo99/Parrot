package com.retro99.server.implementation.library

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressValue
import com.retro99.server.api.library.ProgressOwnerRef

/** Bridges the existing server reader repository contract into grouped reader ownership. */
internal class ServerReaderLibraryProgressAdapter(
    override val adapterId: LibraryAdapterId,
    private val repositoryProvider: AuthenticatedRepositoryProvider,
) : LibraryProgressAdapter {

    override suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue? =
        readLocalPosition(owner).getOrElse { null }?.let { position ->
            LibraryProgressValue.ReaderPosition(position)
        }

    override suspend fun validateOwner(owner: ProgressOwnerRef): AppResult<Unit> =
        resolveRepository(owner).map { Unit }

    override suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue) {
        val position = (value as? LibraryProgressValue.ReaderPosition)?.position
            ?: throw IllegalArgumentException(
                "Server reader progress requires a lossless reader position",
            )
        savePositionWithSync(owner, position).getOrElse { error ->
            throw IllegalStateException("Could not save reader progress: $error")
        }
    }

    override suspend fun readLocalPosition(
        owner: ProgressOwnerRef,
    ): AppResult<ServerPosition?> = resolveRepository(owner).flatMap { repository ->
        repository.getLocalPosition(owner.nativeProgressId).map { position ->
            position?.withOwner(owner)
        }
    }

    override suspend fun readRemotePosition(
        owner: ProgressOwnerRef,
    ): AppResult<ServerPosition?> = resolveRepository(owner).flatMap { repository ->
        repository.getRemotePosition(owner.nativeProgressId).map { position ->
            position?.withOwner(owner)
        }
    }

    override suspend fun savePositionWithSync(
        owner: ProgressOwnerRef,
        position: ServerPosition,
    ): CompletableResult = resolveRepository(owner).flatMap { repository ->
        repository.saveLocalPositionWithSync(
            bookUuid = owner.nativeProgressId,
            position = position.withOwner(owner),
        )
    }

    private suspend fun resolveRepository(
        owner: ProgressOwnerRef,
    ): AppResult<ServerReaderRepository> {
        if (owner.adapterId != adapterId || owner.source.key.adapterId != adapterId) {
            return Err(AppError.NotFoundError("Progress owner does not match this adapter"))
        }
        val connectionId = owner.source.connectionId?.value
            ?: return Err(AppError.NotFoundError("Progress source is disconnected"))
        val repository = repositoryProvider.getReaderRepository(connectionId)
            ?: return Err(AppError.NotFoundError("Progress source is disconnected"))
        if (
            repository.serverId != connectionId ||
            repository.libraryAdapterId != adapterId
        ) {
            return Err(AppError.NotFoundError("Progress owner does not match the connected source"))
        }
        return Ok(repository)
    }

    private fun ServerPosition.withOwner(owner: ProgressOwnerRef): ServerPosition = copy(
        bookUuid = owner.nativeProgressId,
        serverId = requireNotNull(owner.source.connectionId).value,
    )
}
