package com.retro99.sync.data

import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Repairs duplicate position rows before a sync pass reads or writes
 * progress. The newest remote revision and then updated timestamp wins.
 */
@Single
class DuplicatePositionRepair(
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val localBookUuidResolver: LocalBookUuidResolver,
) {
    suspend fun repair() {
        val positionsByLibraryBookId = positionDatabase.getAllPositions()
            .filter { position -> position.libraryBookId.isNotBlank() }
            .groupBy { position -> position.libraryBookId }

        positionsByLibraryBookId.forEach { (libraryBookId, positions) ->
            if (positions.size <= 1) return@forEach

            val winner = positions.maxWithOrNull(
                compareBy<PositionEntity>(
                    { position -> position.remoteRevision },
                    { position -> position.updatedAt },
                ),
            ) ?: return@forEach
            val localBookUuid = localBookUuidResolver.resolve(
                libraryBookId = libraryBookId,
                cloudBookId = "",
                fallback = winner.bookUuid,
            )
            val repaired = winner.toRepairedPositionEntity(
                bookUuid = localBookUuid,
                libraryBookId = libraryBookId,
            )
            positions
                .filter { position -> position.bookUuid != localBookUuid }
                .forEach { position -> positionDatabase.deletePosition(position.bookUuid) }
            positionDatabase.upsertPosition(repaired)
        }
    }
}

private data class RepairedPositionEntity(
    override val bookUuid: String,
    override val libraryBookId: String,
    override val localGeneration: Long,
    override val remoteRevision: Long?,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val cssSelector: String?,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
) : PositionEntity

private fun PositionEntity.toRepairedPositionEntity(
    bookUuid: String,
    libraryBookId: String,
): PositionEntity {
    return RepairedPositionEntity(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
        localGeneration = localGeneration,
        remoteRevision = remoteRevision,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        locatorTarget = locatorTarget,
        cssSelector = cssSelector,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
    )
}
