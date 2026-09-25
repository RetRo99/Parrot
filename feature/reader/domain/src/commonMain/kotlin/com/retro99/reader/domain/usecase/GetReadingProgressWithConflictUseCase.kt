package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProgressAdapterRegistry
import com.retro99.server.api.library.ProgressOwnerRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for getting reading progress with conflict detection.
 *
 * This use case fetches both local and remote positions and detects
 * if there's a conflict that requires user intervention.
 *
 * Follows the Books pattern: uses AuthenticatedRepositoryProvider directly
 * to get ServerReaderRepository, which owns both local and remote position data.
 */
@Factory
class GetReadingProgressWithConflictUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val progressAdapterRegistry: LibraryProgressAdapterRegistry,
) {
    /**
     * Gets the reading progress for a book with conflict detection.
     *
     * @param serverId The ID of the server the book belongs to
     * @param bookUuid The UUID of the book
     * @return [ReadingProgressResult.Resolved] if no conflict or positions are the same,
     *         [ReadingProgressResult.Conflict] if user needs to choose between positions
     */
    suspend operator fun invoke(
        serverId: String,
        bookUuid: String,
        expectedAdapterId: LibraryAdapterId? = null,
        progressOwner: ProgressOwnerRef? = null,
    ): AppResult<ReadingProgressResult> {
        if (progressOwner != null) {
            return invokeForProgressOwner(
                serverId = serverId,
                bookUuid = bookUuid,
                expectedAdapterId = expectedAdapterId,
                progressOwner = progressOwner,
            )
        }

        val serverRepository = repositoryProvider.getReaderRepository(serverId)
            ?: return Ok(ReadingProgressResult.Resolved(null)) // Server not found
        if (
            serverRepository.serverId != serverId ||
            (expectedAdapterId != null && serverRepository.libraryAdapterId != expectedAdapterId)
        ) {
            return Err(AppError.NotFoundError("Progress owner does not match the selected source"))
        }

        return coroutineScope {
            val localDeferred = async {
                serverRepository.getLocalPosition(bookUuid)
            }
            val remoteDeferred = async {
                val reconciledBaseline = positionDatabase.getRemotePositionByBookUuid(bookUuid)
                if (reconciledBaseline != null) {
                    Ok(reconciledBaseline.toServerPosition(serverId))
                } else {
                    // Compatibility fallback for books opened before the shared
                    // refresh has populated a durable remote baseline.
                    serverRepository.getRemotePosition(bookUuid)
                }
            }

            val localPosition = localDeferred.await().getOrElse { null }?.toDomain()
            val remotePosition = remoteDeferred.await().getOrElse { null }?.toDomain()

            Ok(resolvePositionConflict(localPosition, remotePosition))
        }
    }

    private suspend fun invokeForProgressOwner(
        serverId: String,
        bookUuid: String,
        expectedAdapterId: LibraryAdapterId?,
        progressOwner: ProgressOwnerRef,
    ): AppResult<ReadingProgressResult> {
        val ownerConnectionId = progressOwner.source.connectionId?.value
        if (
            ownerConnectionId != serverId ||
            progressOwner.nativeProgressId != bookUuid ||
            (expectedAdapterId != null && progressOwner.adapterId != expectedAdapterId)
        ) {
            return Err(AppError.NotFoundError("Progress owner does not match the selected source"))
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
        adapter.validateOwner(progressOwner).getOrElse { error ->
            return if (error is AppError.NotFoundError) {
                Ok(ReadingProgressResult.Resolved(null))
            } else {
                Err(error)
            }
        }

        return coroutineScope {
            val localDeferred = async {
                adapter.readLocalPosition(progressOwner)
            }
            val remoteDeferred = async {
                val reconciledBaseline = positionDatabase.getRemotePositionByBookUuid(bookUuid)
                if (reconciledBaseline != null) {
                    Ok(reconciledBaseline.toServerPosition(serverId))
                } else {
                    // Keep the adapter's direct remote fallback for sources whose
                    // shared reconciliation baseline has not been populated yet.
                    adapter.readRemotePosition(progressOwner)
                }
            }

            val localPosition = localDeferred.await().getOrElse { null }?.toDomain()
            val remotePosition = remoteDeferred.await().getOrElse { null }?.toDomain()
            Ok(resolvePositionConflict(localPosition, remotePosition))
        }
    }

    /**
     * Resolves positions after shared reconciliation has applied clean remote
     * state or preserved a dirty remote baseline.
     */
    private fun resolvePositionConflict(
        localPosition: PositionDomainModel?,
        remotePosition: PositionDomainModel?,
    ): ReadingProgressResult {
        return when {
            // Both null - no position exists
            localPosition == null && remotePosition == null -> {
                ReadingProgressResult.Resolved(null)
            }
            // Only remote exists
            localPosition == null -> {
                ReadingProgressResult.Resolved(remotePosition)
            }
            // Only local exists
            remotePosition == null -> {
                ReadingProgressResult.Resolved(localPosition)
            }
            // Both exist - preserve both candidates when their semantic
            // positions differ. The shared engine, not a percentage threshold,
            // determines whether the remote candidate is a baseline or a local
            // replacement before this use case runs.
            !localPosition.isSemanticallyEqualTo(remotePosition) -> {
                ReadingProgressResult.Conflict(
                    localPosition = localPosition,
                    remotePosition = remotePosition,
                )
            }
            // Same semantic position - reconcile metadata without prompting.
            else -> {
                ReadingProgressResult.Resolved(localPosition)
            }
        }
    }
}

/**
 * Maps a ServerPosition to PositionDomainModel.
 */
private fun ServerPosition.toDomain(): PositionDomainModel {
    return PositionDomainModel(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
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

private fun PositionEntity.toServerPosition(serverId: String): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
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

private fun PositionDomainModel.isSemanticallyEqualTo(other: PositionDomainModel): Boolean {
    return locatorHref == other.locatorHref &&
        locatorType == other.locatorType &&
        locatorTarget == other.locatorTarget &&
        cssSelector == other.cssSelector &&
        audioTimestampMs == other.audioTimestampMs &&
        chapterIndex == other.chapterIndex &&
        progression == other.progression &&
        totalChapters == other.totalChapters &&
        totalDurationMs == other.totalDurationMs &&
        totalProgression == other.totalProgression &&
        position == other.position
}
