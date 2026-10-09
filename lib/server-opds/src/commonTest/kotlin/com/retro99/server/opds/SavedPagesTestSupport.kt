package com.retro99.server.opds

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueDocumentEntity
import com.retro99.database.api.catalogue.CatalogueDocumentKey
import com.retro99.database.api.catalogue.CatalogueDocumentsDatabase

/** One saved-pages table per profile, like one database file per profile. */
internal class MemoryDocuments(private val activeProfile: () -> String?) : CatalogueDocumentsDatabase {
    private val tables = mutableMapOf<String, MutableList<CatalogueDocumentEntity>>()
    private var insideSession = false

    /** What is saved for a profile, oldest first, without going through the session. */
    fun peek(profileId: String): List<CatalogueDocumentEntity> = tables[profileId].orEmpty().sortedBy { it.storedAt }

    /** Refuses a profile that is not the open one, like the real session. */
    val session = object : ProfileDatabaseSession {
        override suspend fun <T> withProfile(localProfileId: String, operation: suspend () -> T): T {
            check(activeProfile() == localProfileId) { "Profile $localProfileId is not open" }
            insideSession = true
            try { return operation() } finally { insideSession = false }
        }
    }

    private fun table(): MutableList<CatalogueDocumentEntity> {
        check(insideSession) { "The database was used outside withProfile" }
        return tables.getOrPut(checkNotNull(activeProfile())) { mutableListOf() }
    }

    private fun CatalogueDocumentEntity.isAt(sourceId: String, accessGeneration: Long, requestUrl: String) =
        this.sourceId == sourceId && this.accessGeneration == accessGeneration && this.requestUrl == requestUrl

    override suspend fun upsert(document: CatalogueDocumentEntity) {
        delete(document.sourceId, document.accessGeneration, document.requestUrl)
        table() += document
    }
    override suspend fun get(sourceId: String, accessGeneration: Long, requestUrl: String) =
        table().firstOrNull { it.isAt(sourceId, accessGeneration, requestUrl) }
    override suspend fun totalSizeBytes() = table().sumOf { it.sizeBytes }
    override suspend fun count() = table().size.toLong()
    override suspend fun oldestKeys(limit: Long) = table().sortedWith(compareBy({ it.storedAt }, { it.requestUrl })).take(limit.toInt())
        .map { CatalogueDocumentKey(it.sourceId, it.accessGeneration, it.requestUrl, it.sizeBytes) }
    override suspend fun delete(sourceId: String, accessGeneration: Long, requestUrl: String) {
        table().removeAll { it.isAt(sourceId, accessGeneration, requestUrl) }
    }
    override suspend fun deleteForSource(sourceId: String) { table().removeAll { it.sourceId == sourceId } }
    override suspend fun deleteOtherGenerations(sourceId: String, accessGeneration: Long) {
        table().removeAll { it.sourceId == sourceId && it.accessGeneration != accessGeneration }
    }
}
