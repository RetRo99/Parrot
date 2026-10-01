package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided

/**
 * Positions of books in your library, keyed by the book id. Parrot Cloud lists no books
 * of its own, so this is only reached for ids that are library book ids.
 */
class ParrotCloudReaderRepository(
    override val serverId: String,
    @Provided private val positionDatabase: PositionDatabase,
) : ServerReaderRepository {
    private val json = Json {
        encodeDefaults = true
    }

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return getLocalPosition(bookUuid)
    }

    override suspend fun saveLocalPositionWithSync(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult {
        return try {
            val stored = positionDatabase.getPositionByBookUuid(bookUuid)
            val localGeneration = (stored?.localGeneration ?: 0L) + 1L
            val normalizedPosition = position.copy(
                bookUuid = bookUuid,
                serverId = serverId,
                libraryBookId = bookUuid,
            )
            positionDatabase.upsertPositionWithMutation(
                position = normalizedPosition.toParrotCloudPositionEntity(
                    remoteRevision = stored?.remoteRevision,
                    localGeneration = localGeneration,
                ),
                mutation = SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    entityId = bookUuid,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = json.encodeToString(
                        ParrotCloudReadingPositionPayload(
                            libraryBookId = bookUuid,
                            position = normalizedPosition,
                        ),
                    ),
                    baseRevision = stored?.remoteRevision,
                    localGeneration = localGeneration,
                ),
            )
            Ok(Unit)
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
        return try {
            Ok(positionDatabase.getPositionByBookUuid(bookUuid)?.toServerPosition(bookUuid))
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult {
        return try {
            positionDatabase.upsertPosition(position.toParrotCloudPositionEntity(null))
            Ok(Unit)
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
        return try {
            val remotePosition = positionDatabase.getRemotePositionByBookUuid(bookUuid)
            Ok(remotePosition?.toServerPosition(bookUuid))
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }
}

/** The stored and pulled `reading_position` payload: `{library_book_id, position}`. */
@Serializable
internal data class ParrotCloudReadingPositionPayload(
    @kotlinx.serialization.SerialName("library_book_id")
    val libraryBookId: String,
    val position: ServerPosition,
)

private fun PositionEntity.toServerPosition(bookUuid: String): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = com.retro99.base.server.PARROT_CLOUD_SERVER_ID,
        libraryBookId = libraryBookId,
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
        remoteRevision = remoteRevision,
    )
}

internal fun ServerPosition.toParrotCloudPositionEntity(remoteRevision: Long?): PositionEntity {
    return ParrotCloudPositionEntity(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
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

internal fun ServerPosition.toParrotCloudPositionEntity(
    remoteRevision: Long?,
    localGeneration: Long,
): PositionEntity {
    return ParrotCloudPositionEntity(
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

internal fun ServerPosition.toParrotCloudPositionEntity(
    remoteRevision: Long?,
    bookUuid: String,
    libraryBookId: String,
): PositionEntity {
    return ParrotCloudPositionEntity(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
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

internal fun PositionEntity.toParrotCloudPositionEntity(
    remoteRevision: Long?,
    bookUuid: String,
    libraryBookId: String,
): PositionEntity {
    return ParrotCloudPositionEntity(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId,
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

internal data class ParrotCloudPositionEntity(
    override val bookUuid: String,
    override val libraryBookId: String?,
    override val localGeneration: Long = 0L,
    override val remoteRevision: Long?,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val cssSelector: String? = null,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
) : PositionEntity
