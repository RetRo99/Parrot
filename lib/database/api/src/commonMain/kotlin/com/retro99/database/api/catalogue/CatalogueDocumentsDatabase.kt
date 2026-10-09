package com.retro99.database.api.catalogue

/** Persisted copies of catalogue pages you opened. Eviction policy belongs to the caller. */
interface CatalogueDocumentsDatabase {
    suspend fun upsert(document: CatalogueDocumentEntity)

    suspend fun get(sourceId: String, accessGeneration: Long, requestUrl: String): CatalogueDocumentEntity?

    suspend fun totalSizeBytes(): Long

    suspend fun count(): Long

    /** Keys of the [limit] documents stored longest ago, oldest first. */
    suspend fun oldestKeys(limit: Long): List<CatalogueDocumentKey>

    suspend fun delete(sourceId: String, accessGeneration: Long, requestUrl: String)

    suspend fun deleteForSource(sourceId: String)

    /** Drops what was saved under earlier sign-in details of this source. */
    suspend fun deleteOtherGenerations(sourceId: String, accessGeneration: Long)
}
