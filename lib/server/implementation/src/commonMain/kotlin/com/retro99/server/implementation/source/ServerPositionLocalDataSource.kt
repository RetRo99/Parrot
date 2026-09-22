package com.retro99.server.implementation.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Implementation of ServerPositionLocalSource that uses PositionDatabase for storage.
 * This is shared by all server implementations (Storyteller, Local, etc.).
 */
@Single(binds = [ServerPositionLocalSource::class])
class ServerPositionLocalDataSource(
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
) : ServerPositionLocalSource {

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return databaseExecutor.executeDatabaseOperation {
            (positionDatabase.getPositionByBookUuid(bookUuid)
                ?: positionDatabase.getPositionByLibraryBookId(bookUuid))
                ?.toServerPosition()
        }
    }

    override suspend fun savePosition(position: ServerPosition): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val storedPosition = positionDatabase.getPositionByBookUuid(position.bookUuid)
                ?: position.libraryBookId?.let { libraryBookId ->
                    positionDatabase.getPositionByLibraryBookId(libraryBookId)
                }
            val localPosition = position.toPositionEntity(storedPosition?.remoteRevision)
            if (position.serverId == LOCAL_SERVER_ID) {
                val importedBook = importedBooksDatabase.getImportedBookByUuid(position.bookUuid)
                positionDatabase.upsertPositionWithMutation(
                    position = localPosition,
                    mutation = position.toSyncOutboxEntry(
                        contentHash = importedBook?.contentHash,
                        contentHashAlgorithm = importedBook?.contentHashAlgorithm,
                        baseRevision = storedPosition?.remoteRevision,
                    ),
                )
            } else {
                positionDatabase.upsertPosition(localPosition)
            }
        }
    }

    override suspend fun getAllPositions(): AppResult<List<ServerPosition>> {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.getAllPositions().map { it.toServerPosition() }
        }
    }

    override suspend fun deletePosition(bookUuid: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.deletePosition(bookUuid)
        }
    }

    override fun observePosition(bookUuid: String): Flow<ServerPosition?> {
        return positionDatabase.observePositionByBookUuid(bookUuid)
            .map { it?.toServerPosition() }
    }

    override fun observeAllPositions(): Flow<List<ServerPosition>> {
        return positionDatabase.observeAllPositions()
            .map { positions -> positions.map { it.toServerPosition() } }
    }
}

private fun ServerPosition.toSyncOutboxEntry(
    contentHash: String?,
    contentHashAlgorithm: String?,
    baseRevision: Long?,
): SyncOutboxEntry {
    return SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
        entityId = bookUuid,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = mutationJson.encodeToString(
            LocalReadingPositionMutation(
                bookUuid = bookUuid,
                contentHash = contentHash,
                contentHashAlgorithm = contentHashAlgorithm,
                position = this,
            ),
        ),
        baseRevision = baseRevision,
    )
}

private val mutationJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class LocalReadingPositionMutation(
    val bookUuid: String,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val position: ServerPosition,
)

/**
 * Converts a PositionEntity to ServerPosition.
 * Note: serverId is not stored in the database, so we use an empty string.
 * The actual serverId should be set by the caller based on context.
 */
private fun PositionEntity.toServerPosition(): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = "", // Not stored in DB - will be set by repository
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
        remoteRevision = remoteRevision,
    )
}

/**
 * Converts a ServerPosition to a PositionEntity for database storage.
 */
private fun ServerPosition.toPositionEntity(remoteRevision: Long?): PositionEntity {
    return ServerPositionEntity(
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

/**
 * Internal PositionEntity implementation for database storage.
 */
private data class ServerPositionEntity(
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
