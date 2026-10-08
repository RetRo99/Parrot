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

    /**
     * Atomically settles a choice: replace the position, remove superseded progress writes
     * and the remote candidate, and optionally queue the replacement. A stale choice must
     * not overwrite reading saved while the prompt (or a network request) was open.
     * A null destination denotes the library's Parrot Cloud queue, including unbound writes.
     */
    suspend fun resolvePositionConflict(
        position: PositionEntity,
        mutation: SyncOutboxEntry?,
        expectedLocalGeneration: Long,
        destinationId: String?,
    ): Boolean = error("Atomic position conflict resolution is not implemented")

    suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long? = null,
    )

    suspend fun upsertRemotePosition(position: PositionEntity)

    suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity?

    suspend fun deleteRemotePosition(bookUuid: String)

    /** Atomically checks the generation and pending writes before replacing clean reading. */
    suspend fun applyRemotePositionIfClean(
        position: PositionEntity,
        expectedLocalGeneration: Long?,
        remoteAccountId: String,
        progressEntityIds: Set<String>,
    ): Boolean = error("Atomic remote position application is not implemented")

    /** Atomically protects newer reading and, when supplied, newer remote revisions. */
    suspend fun deleteRemotePositionIfGeneration(
        bookUuid: String,
        expectedLocalGeneration: Long,
        throughRemoteRevision: Long? = null,
    ): Boolean = error("Atomic remote position deletion is not implemented")

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
