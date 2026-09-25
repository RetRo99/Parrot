package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.retro99.base.nowMillis
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProgressAdapterRegistry
import com.retro99.server.api.library.ProgressOwnerRef
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for saving reading progress.
 * Saves the local position and durable remote-delivery intent. Network delivery
 * is handled by the shared synchronization coordinator.
 *
 * Follows the Books pattern: uses AuthenticatedRepositoryProvider directly
 * to get ServerReaderRepository, which owns both local and remote position data.
 */
@Factory
class SaveReadingProgressUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val progressAdapterRegistry: LibraryProgressAdapterRegistry,
) {
    suspend operator fun invoke(
        progress: PositionDomainModel,
        expectedAdapterId: LibraryAdapterId? = null,
        progressOwner: ProgressOwnerRef? = null,
    ): CompletableResult {
        if (progressOwner != null) {
            val ownerConnectionId = progressOwner.source.connectionId?.value
            if (
                ownerConnectionId != progress.serverId ||
                progressOwner.nativeProgressId != progress.bookUuid ||
                (expectedAdapterId != null && progressOwner.adapterId != expectedAdapterId)
            ) {
                return Err(
                    AppError.NotFoundError("Progress owner does not match the selected source"),
                )
            }
            val adapter = progressAdapterRegistry.adapter(progressOwner.adapterId)
                ?: return Err(
                    AppError.NotFoundError(
                        "No progress adapter registered for ${progressOwner.adapterId.value}",
                    ),
                )
            if (adapter.adapterId != progressOwner.adapterId) {
                return Err(
                    AppError.NotFoundError("Progress adapter does not match the selected source"),
                )
            }
            return adapter.savePositionWithSync(
                owner = progressOwner,
                position = progress.toServerPosition(),
            )
        }

        val serverRepository = repositoryProvider.getReaderRepository(progress.serverId)
            ?: return Err(AppError.NotFoundError("Server not found: ${progress.serverId}"))
        if (
            serverRepository.serverId != progress.serverId ||
            (expectedAdapterId != null && serverRepository.libraryAdapterId != expectedAdapterId)
        ) {
            return Err(AppError.NotFoundError("Progress owner does not match the selected source"))
        }

        return serverRepository.saveLocalPositionWithSync(
            bookUuid = progress.bookUuid,
            position = progress.toServerPosition(),
        )
    }
}

/**
 * Converts a PositionDomainModel to ServerPosition.
 * Always uses current timestamp to ensure the server accepts the position.
 */
private fun PositionDomainModel.toServerPosition(): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = nowMillis(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        cssSelector = cssSelector,
    )
}
