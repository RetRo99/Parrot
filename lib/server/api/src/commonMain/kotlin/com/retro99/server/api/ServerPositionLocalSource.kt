package com.retro99.server.api

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import kotlinx.coroutines.flow.Flow

/**
 * Local source interface for position storage.
 * Used by ServerReaderRepository implementations to cache positions locally.
 */
interface ServerPositionLocalSource {

    /**
     * Gets the locally cached position for a book.
     *
     * @param bookUuid The UUID of the book
     * @return The cached position or null if not found
     */
    suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?>

    /**
     * Saves a position to the local cache.
     *
     * @param position The position to save
     */
    suspend fun savePosition(position: ServerPosition): CompletableResult

    /**
     * Saves a position and durably queues it for one remote destination in the
     * same database transaction.
     *
     * @param position The position to save
     * @param remoteAccountId The destination/account key used by sync routing
     */
    suspend fun savePositionWithSync(
        position: ServerPosition,
        remoteAccountId: String,
    ): CompletableResult

    /**
     * Gets all locally cached positions.
     *
     * @return List of all cached positions
     */
    suspend fun getAllPositions(): AppResult<List<ServerPosition>>

    /**
     * Deletes a position from the local cache.
     *
     * @param bookUuid The UUID of the book
     */
    suspend fun deletePosition(bookUuid: String): CompletableResult

    /**
     * Observes position changes for a specific book.
     * Emits whenever the position for this book is updated in the database.
     *
     * @param bookUuid The UUID of the book
     * @return Flow of position updates (null if no position exists)
     */
    fun observePosition(bookUuid: String): Flow<ServerPosition?>

    /**
     * Observes all position changes.
     * Emits whenever any position is updated in the database.
     *
     * @return Flow of all positions
     */
    fun observeAllPositions(): Flow<List<ServerPosition>>
}
