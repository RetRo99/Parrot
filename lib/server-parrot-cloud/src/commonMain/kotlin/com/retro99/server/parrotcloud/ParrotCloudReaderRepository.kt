package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided

class ParrotCloudReaderRepository(
    override val serverId: String,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
) : ServerReaderRepository {
    private val json = Json {
        encodeDefaults = true
    }

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return getLocalPosition(bookUuid)
    }

    override suspend fun savePosition(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult {
        return try {
            val libraryBookId = resolveLibraryBookId(bookUuid, position.libraryBookId)
            val stored = positionDatabase.getPositionByLibraryBookId(libraryBookId)
            val normalizedPosition = position.copy(
                bookUuid = bookUuid,
                serverId = serverId,
                libraryBookId = libraryBookId,
            )
            positionDatabase.upsertPositionWithMutation(
                position = normalizedPosition.toParrotCloudPositionEntity(stored?.remoteRevision),
                mutation = SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    entityId = bookUuid,
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = json.encodeToString(
                        ParrotCloudReadingPositionPayload(
                            cloudBookId = bookUuid,
                            libraryBookId = libraryBookId,
                            position = normalizedPosition,
                        ),
                    ),
                    baseRevision = stored?.remoteRevision,
                ),
            )
            Ok(Unit)
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
        return try {
            val libraryBookId = resolveLibraryBookId(bookUuid, null)
            Ok(
                positionDatabase.getPositionByLibraryBookId(libraryBookId)
                    ?.toServerPosition(bookUuid),
            )
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
        return Ok(null)
    }

    private suspend fun resolveLibraryBookId(bookUuid: String, fallback: String?): String {
        return fallback
            ?: libraryBooksDatabase.getLibraryBookByCloudBookId(bookUuid)?.libraryBookId
            ?: bookUuid
    }
}

@Serializable
internal data class ParrotCloudReadingPositionPayload(
    @kotlinx.serialization.SerialName("cloud_book_id")
    val cloudBookId: String,
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
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
    )
}

internal fun ServerPosition.toParrotCloudPositionEntity(remoteRevision: Long?): PositionEntity {
    return ParrotCloudPositionEntity(
        bookUuid = bookUuid,
        libraryBookId = libraryBookId ?: bookUuid,
        remoteRevision = remoteRevision,
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
    )
}

private data class ParrotCloudPositionEntity(
    override val bookUuid: String,
    override val libraryBookId: String,
    override val remoteRevision: Long?,
    override val timestamp: Long?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val locatorHref: String?,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val locatorTarget: Int?,
    override val audioTimestampMs: Long?,
    override val chapterIndex: Int?,
    override val progression: Double?,
    override val totalChapters: Int?,
    override val totalDurationMs: Long?,
    override val totalProgression: Double?,
    override val position: Int?,
) : PositionEntity
