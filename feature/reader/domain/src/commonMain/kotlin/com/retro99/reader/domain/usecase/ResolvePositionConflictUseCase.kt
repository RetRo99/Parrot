package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.toServerPosition
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.SourceDeviceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock

/** Both dialogs settle the exact candidates they displayed, without another network fetch. */
@Factory
class ResolvePositionConflictUseCase(
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val installationDeviceIdentity: InstallationDeviceIdentity,
) {
    suspend fun useLocal(conflict: ReadingProgressResult.Conflict): CompletableResult =
        resolve(conflict, useLocal = true)

    suspend fun useRemote(conflict: ReadingProgressResult.Conflict): CompletableResult =
        resolve(conflict, useLocal = false)

    private suspend fun resolve(
        conflict: ReadingProgressResult.Conflict,
        useLocal: Boolean,
    ): CompletableResult {
        val local = conflict.localPosition
        val remote = conflict.remotePosition
        if (local.bookUuid != remote.bookUuid || local.serverId != remote.serverId) {
            return Err(AppError.NotFoundError("Conflict candidates belong to different copies"))
        }
        return try {
            val stored = positionDatabase.getPositionByBookUuid(local.bookUuid)
                ?: return Err(AppError.NotFoundError("No local position found"))
            val now = Clock.System.now()
            val destination = local.serverId.takeUnless {
                it == LOCAL_SERVER_ID || it == PARROT_CLOUD_SERVER_ID
            }
            val selected = if (useLocal) local else remote
            // A choice is not new reading. Keep its observation time and provenance; only
            // the delivery timestamp is refreshed for Storyteller's conditional write.
            val serverPosition = selected.toServerPosition().copy(
                timestamp = if (useLocal) now.toEpochMilliseconds() else selected.timestamp,
                remoteRevision = remote.remoteRevision,
                libraryBookId = stored.libraryBookId,
                origin = if (!useLocal) PositionOrigin.Remote
                    else if (selected.origin == PositionOrigin.LinkedCopy) PositionOrigin.Manual
                    else selected.origin,
            )
            val generation = local.localGeneration + 1L
            val mutation = if (useLocal) SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                entityId = local.bookUuid,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = json.encodeToString(
                    ConflictPositionPayload(
                        bookUuid = local.bookUuid,
                        position = serverPosition,
                        dismissedRemotePosition = remote.toServerPosition(),
                        sourceDevice = selected.sourceDeviceId?.let {
                            SourceDeviceIdentity(it, selected.deviceName)
                        } ?: installationDeviceIdentity.getOrCreate(),
                    ),
                ),
                baseRevision = remote.remoteRevision,
                localGeneration = generation,
                cloudUserId = destination,
            ) else null
            val applied = positionDatabase.resolvePositionConflict(
                position = ChosenPosition(serverPosition, generation),
                mutation = mutation,
                expectedLocalGeneration = local.localGeneration,
                destinationId = destination,
            )
            if (applied) Ok(Unit) else Err(
                AppError.NotFoundError("Reading position changed while choosing; reopen the conflict"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    private val json = Json { encodeDefaults = true }
}

/** Same durable payload as ordinary local reading; each destination already decodes it. */
@Serializable
private data class ConflictPositionPayload(
    val bookUuid: String,
    val position: ServerPosition,
    val sourceDevice: SourceDeviceIdentity?,
    val dismissedRemotePosition: ServerPosition,
)

private class ChosenPosition(
    private val selected: ServerPosition,
    override val localGeneration: Long,
) : PositionEntity {
    override val bookUuid = selected.bookUuid
    override val libraryBookId = selected.libraryBookId
    override val remoteRevision = selected.remoteRevision
    override val timestamp = selected.timestamp
    override val createdAt = selected.createdAt
    override val updatedAt = selected.updatedAt
    override val locatorHref = selected.locatorHref
    override val locatorType = selected.locatorType
    override val locatorTitle = selected.locatorTitle
    override val locatorTarget = selected.locatorTarget
    override val cssSelector = selected.cssSelector
    override val audioTimestampMs = selected.audioTimestampMs
    override val chapterIndex = selected.chapterIndex
    override val progression = selected.progression
    override val totalChapters = selected.totalChapters
    override val totalDurationMs = selected.totalDurationMs
    override val totalProgression = selected.totalProgression
    override val bookTimeMs = selected.bookTimeMs
    override val ebookLocationRaw = selected.ebookLocationRaw
    override val position = selected.position
    override val origin = selected.origin.value
    override val observedAt = selected.observedAt
    override val textAnchor = selected.textAnchor?.toJson()
    override val sourceDeviceId = selected.sourceDeviceId
    override val deviceName = selected.deviceName
}
