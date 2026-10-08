package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.conflictDecision
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.reader.domain.model.isSameReadingPlaceAs
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import com.retro99.sync.domain.ProgressAccountResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
    @Provided private val progressAccountResolver: ProgressAccountResolver,
) {
    /**
     * Gets the reading progress for a book with conflict detection.
     *
     * @param serverId The ID of the server the book belongs to
     * @param bookUuid The UUID of the book
     * @return [ReadingProgressResult.Resolved] if no conflict or positions are the same,
     *         [ReadingProgressResult.Conflict] if user needs to choose between positions
     */
    suspend operator fun invoke(serverId: String, bookUuid: String): AppResult<ReadingProgressResult> {
        val serverRepository = repositoryProvider.getReaderRepository(serverId)
            ?: return Ok(ReadingProgressResult.Resolved(null)) // Server not found

        return coroutineScope {
            val localDeferred = async {
                serverRepository.getLocalPosition(bookUuid)
            }
            val remoteDeferred = async {
                val reconciledBaseline = positionDatabase.getRemotePositionByBookUuid(bookUuid)
                if (reconciledBaseline != null) {
                    reconciledBaseline.toPositionDomainModel(serverId).copy(origin = PositionOrigin.Remote)
                } else {
                    // Compatibility fallback for books opened before the shared
                    // refresh has populated a durable remote baseline.
                    serverRepository.getRemotePosition(bookUuid).getOrElse { null }?.toPositionDomainModel()
                }
            }

            val localPosition = localDeferred.await().getOrElse { null }?.toPositionDomainModel()
            val remotePosition = remoteDeferred.await()

            // A local choice is durable even before delivery. Only suppress the exact
            // server candidate it rejected; new remote reading must still be offered.
            if (localPosition != null && remotePosition != null &&
                hasDismissedRemote(serverId, bookUuid, remotePosition)
            ) {
                return@coroutineScope Ok(ReadingProgressResult.Resolved(localPosition))
            }
            Ok(resolvePositionConflict(localPosition, remotePosition))
        }
    }

    internal suspend fun hasDismissedRemote(
        serverId: String,
        bookUuid: String,
        remote: PositionDomainModel,
    ): Boolean = syncOutboxDatabase.getPendingIncludingUnassigned(
        progressAccountResolver.accountId(serverId) ?: "",
    ).any { entry ->
        if (entry.entityType != SyncOutboxEntry.ENTITY_TYPE_READING_POSITION ||
            entry.entityId != bookUuid || entry.state == SyncOutboxEntry.STATE_CONFLICT_PRESERVED
        ) return@any false
        val dismissed = runCatching {
            val value = json.parseToJsonElement(entry.payload).jsonObject["dismissedRemotePosition"]
                ?: return@any false
            json.decodeFromJsonElement(ServerPosition.serializer(), value).toPositionDomainModel()
        }.getOrNull() ?: return@any false
        dismissed.serverId == serverId && dismissed.isSameReadingPlaceAs(remote) && dismissed.timestamp == remote.timestamp &&
            dismissed.remoteRevision == remote.remoteRevision
    }

    private val json = Json { ignoreUnknownKeys = true }

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
            // positions differ. The decision only weighs newer/further and
            // triggers the dialog when the answer is not obvious.
            !localPosition.isSameReadingPlaceAs(remotePosition) -> {
                ReadingProgressResult.Conflict(
                    localPosition = localPosition,
                    remotePosition = remotePosition,
                    decision = conflictDecision(localPosition, remotePosition),
                )
            }
            // Same semantic position - reconcile metadata without prompting.
            else -> {
                ReadingProgressResult.Resolved(localPosition)
            }
        }
    }
}
