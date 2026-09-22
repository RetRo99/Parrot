package com.retro99.database.api.books

import com.retro99.database.api.DataClearable
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow

/**
 * Database interface for reading position operations.
 */
interface PositionDatabase : DataClearable {

    suspend fun upsertPosition(position: PositionEntity)

    suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    )

    suspend fun updateRemoteRevision(bookUuid: String, remoteRevision: Long)

    suspend fun upsertRemotePosition(position: PositionEntity)

    suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity?

    suspend fun deleteRemotePosition(bookUuid: String)

    suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity?

    suspend fun getPositionByLibraryBookId(libraryBookId: String): PositionEntity? {
        return getAllPositions()
            .filter { position -> position.libraryBookId == libraryBookId }
            .maxWithOrNull(compareBy({ position -> position.updatedAt }, { position -> position.remoteRevision }))
    }

    suspend fun getAllPositions(): List<PositionEntity>

    suspend fun deletePosition(bookUuid: String)

    /**
     * Observes position changes for a specific book.
     * Emits whenever the position for this book is updated in the database.
     */
    fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?>

    fun observePositionByLibraryBookId(libraryBookId: String): Flow<PositionEntity?> {
        return observePositionByBookUuid(libraryBookId)
    }

    /**
     * Observes all position changes.
     * Emits whenever any position is updated in the database.
     */
    fun observeAllPositions(): Flow<List<PositionEntity>>
}
