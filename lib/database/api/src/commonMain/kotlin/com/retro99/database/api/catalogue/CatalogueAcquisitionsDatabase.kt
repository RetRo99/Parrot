package com.retro99.database.api.catalogue

/**
 * The durable catalogue download queue of the open profile database. Callers fence every
 * call with `ProfileDatabaseSession.withProfile`.
 */
interface CatalogueAcquisitionsDatabase {
    suspend fun insert(acquisition: CatalogueAcquisitionEntity)

    /** Rewrites the mutable columns of the row with the same request id. */
    suspend fun update(acquisition: CatalogueAcquisitionEntity)

    /** Byte counts only, and only while the row is still downloading. */
    suspend fun updateProgress(requestId: String, bytesSoFar: Long, expectedSizeBytes: Long?, updatedAt: Long)

    suspend fun get(requestId: String): CatalogueAcquisitionEntity?

    /** The request for this file that has not finished yet, if there is one. */
    suspend fun findUnfinished(
        sourceId: String,
        publicationKey: String,
        representationKey: String,
    ): CatalogueAcquisitionEntity?

    /** Every request, in queue order. */
    suspend fun getAll(): List<CatalogueAcquisitionEntity>

    suspend fun getBySource(sourceId: String): List<CatalogueAcquisitionEntity>

    suspend fun getByStates(states: List<String>): List<CatalogueAcquisitionEntity>

    /** Finished requests completed at or after [sinceMillis], newest first. */
    suspend fun getCompletedSince(sinceMillis: Long): List<CatalogueAcquisitionEntity>

    /** One more than the highest position in use, so a new request goes last. */
    suspend fun nextQueuePosition(): Long

    suspend fun delete(requestId: String)

    suspend fun deleteCompleted()

    suspend fun deleteCompletedBefore(beforeMillis: Long)
}
