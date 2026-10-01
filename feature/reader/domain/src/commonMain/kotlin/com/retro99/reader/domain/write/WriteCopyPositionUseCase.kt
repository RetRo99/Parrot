package com.retro99.reader.domain.write

import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.links.LinkedCopyWriteEntity
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toServerPosition
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import com.retro99.sync.domain.ObservedTime
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** A position to write into another linked copy, already translated into its terms. */
data class CopyWrite(
    val source: LinkedCopy,
    /** When the source reading happened; null for a manual choice. */
    val sourceObservedAt: String?,
    val target: LinkedCopy,
    val position: PositionDomainModel,
    /** [PositionOrigin.Manual] from the positions panel, [PositionOrigin.LinkedCopy] otherwise. */
    val origin: PositionOrigin,
)

sealed interface CopyWriteResult {
    data object Written : CopyWriteResult

    /** Not written because a guard rules it out. */
    data class Refused(val reason: Reason) : CopyWriteResult

    data class Failed(val error: AppError) : CopyWriteResult

    enum class Reason {
        /** Guard 11: the target's server can't read this kind of position yet. */
        NotSupported,

        /** A position is never written into the copy it came from. */
        SameCopy,
    }
}

/**
 * The only way a position is written into another copy (§1.3b, slice 4). It saves the target's
 * own local position, which that copy's normal sync pushes to its server, and replaces the
 * target's write-log row. It never records a reading session (guard 12).
 *
 * Storyteller only accepts a newer `timestamp` (guard 13): automatic writes send the source's
 * reading time, so newer reading elsewhere wins with a 409; manual writes send now. The value
 * sent is the echo marker, recorded exactly in the write log.
 */
@Factory
class WriteCopyPositionUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val linkedCopyWritesDatabase: LinkedCopyWritesDatabase,
) {
    suspend operator fun invoke(write: CopyWrite): CopyWriteResult {
        if (write.target.key == write.source.key) {
            return CopyWriteResult.Refused(CopyWriteResult.Reason.SameCopy)
        }
        if (!CopyWriteGuards.isWritable(write.target, write.position)) {
            return CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported)
        }
        val repository = repositoryProvider.getReaderRepository(write.target.serverId)
            ?: return CopyWriteResult.Failed(
                AppError.NotFoundError("Server not found: ${write.target.serverId}"),
            )

        val now = Clock.System.now()
        val isManual = write.origin == PositionOrigin.Manual
        val sourceMillis = ObservedTime.toEpochMillis(write.sourceObservedAt)
        // Manual writes, and sources without a readable time, are observed now.
        val readingMillis = sourceMillis.takeIf { _ -> !isManual } ?: now.toEpochMilliseconds()
        val timestamp = readingMillis
        val observedAt = ObservedTime.fromEpochMillis(readingMillis)
        val position = write.position.toServerPosition().copy(
            bookUuid = write.target.uuid,
            serverId = write.target.serverId,
            timestamp = timestamp,
            createdAt = write.position.createdAt ?: now.toString(),
            updatedAt = now.toString(),
            remoteRevision = null,
            origin = write.origin,
            observedAt = observedAt,
        )
        repository.saveLocalPositionWithSync(write.target.uuid, position)
            .getOrElse { error -> return CopyWriteResult.Failed(error) }

        linkedCopyWritesDatabase.replace(
            write = LinkedCopyWriteEntity(
                targetKey = write.target.key.value,
                targetBookUuid = write.target.uuid,
                sourceKey = write.source.key.value,
                sourceObservedAt = write.sourceObservedAt,
                writtenAt = now.toString(),
                // Parrot Cloud's revision is recorded when the push is acknowledged.
                marker = timestamp.toString()
                    .takeIf { _ -> write.target.key.source == CopySource.Storyteller },
                locatorHref = position.locatorHref,
                progression = position.progression,
                totalProgression = position.totalProgression,
                audioMs = position.audioTimestampMs,
            ),
            deleteWrittenBefore = now.minus(WRITE_LOG_DAYS.days).toString(),
        )
        return CopyWriteResult.Written
    }

    private companion object {
        const val WRITE_LOG_DAYS = 7
    }
}
