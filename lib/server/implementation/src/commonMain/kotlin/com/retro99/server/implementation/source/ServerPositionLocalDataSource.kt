package com.retro99.server.implementation.source

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.TextAnchor
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
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
) : ServerPositionLocalSource {

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.getPositionByBookUuid(bookUuid)?.toServerPosition()
        }
    }

    override suspend fun savePosition(position: ServerPosition): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val storedPosition = positionDatabase.getPositionByBookUuid(position.bookUuid)
            if (position.serverId == LOCAL_SERVER_ID) {
                val localGeneration = (storedPosition?.localGeneration ?: 0L) + 1L
                val libraryPosition = position.withLibraryBookId()
                positionDatabase.upsertPositionWithMutation(
                    position = libraryPosition.toPositionEntity(
                        remoteRevision = storedPosition?.remoteRevision,
                        localGeneration = localGeneration,
                    ),
                    mutation = libraryPosition.toSyncOutboxEntry(
                        baseRevision = storedPosition?.remoteRevision,
                        localGeneration = localGeneration,
                    ),
                )
            } else {
                positionDatabase.upsertPosition(
                    position.keepingEbookLocationOf(storedPosition)
                        .toPositionEntity(storedPosition?.remoteRevision),
                )
            }
        }
    }

    override suspend fun savePositionWithSync(
        position: ServerPosition,
        remoteAccountId: String,
    ): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            require(remoteAccountId.isNotBlank()) { "Remote account ID must not be blank" }

            val storedPosition = positionDatabase.getPositionByBookUuid(position.bookUuid)
            val localGeneration = (storedPosition?.localGeneration ?: 0L) + 1L
            positionDatabase.upsertPositionWithMutation(
                position = position.keepingEbookLocationOf(storedPosition).toPositionEntity(
                    remoteRevision = storedPosition?.remoteRevision,
                    localGeneration = localGeneration,
                ),
                mutation = position.toSyncOutboxEntry(
                    baseRevision = storedPosition?.remoteRevision,
                    localGeneration = localGeneration,
                    cloudUserId = remoteAccountId,
                ),
            )
        }
    }

    /**
     * Reading in this app doesn't know Audiobookshelf's `ebookLocation` shape, so a save keeps
     * the stored one: the next push writes that shape back (B4).
     */
    private fun ServerPosition.keepingEbookLocationOf(stored: PositionEntity?): ServerPosition =
        if (ebookLocationRaw != null) this else copy(ebookLocationRaw = stored?.ebookLocationRaw)

    /** I4: the library book id is set only for books in your library. */
    private suspend fun ServerPosition.withLibraryBookId(): ServerPosition = copy(
        libraryBookId = bookUuid.takeIf { id -> libraryBooksDatabase.getLibraryBookById(id) != null },
    )

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
    baseRevision: Long?,
    localGeneration: Long,
    cloudUserId: String? = null,
): SyncOutboxEntry {
    return SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
        entityId = bookUuid,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = mutationJson.encodeToString(
            LocalReadingPositionMutation(
                bookUuid = bookUuid,
                position = this,
            ),
        ),
        baseRevision = baseRevision,
        cloudUserId = cloudUserId,
        localGeneration = localGeneration,
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
        cssSelector = cssSelector,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        bookTimeMs = bookTimeMs,
        ebookLocationRaw = ebookLocationRaw,
        position = position,
        remoteRevision = remoteRevision,
        origin = PositionOrigin.fromValue(origin),
        observedAt = observedAt,
        textAnchor = TextAnchor.fromJson(textAnchor),
    )
}

/**
 * Converts a ServerPosition to a PositionEntity for database storage.
 */
private fun ServerPosition.toPositionEntity(
    remoteRevision: Long?,
    localGeneration: Long = 0L,
): PositionEntity {
    return ServerPositionEntity(
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
        bookTimeMs = bookTimeMs,
        ebookLocationRaw = ebookLocationRaw,
        position = position,
        origin = origin.value,
        observedAt = observedAt,
        textAnchor = textAnchor?.toJson(),
    )
}

/**
 * Internal PositionEntity implementation for database storage.
 */
private data class ServerPositionEntity(
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
    override val origin: String = PositionEntity.ORIGIN_USER,
    override val observedAt: String? = null,
    override val textAnchor: String? = null,
    override val bookTimeMs: Long? = null,
    override val ebookLocationRaw: String? = null,
) : PositionEntity
