package com.retro99.database.implementation.dao.catalogue

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.database.api.catalogue.CatalogueBookSourceEntity
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.catalogue.CatalogueDocumentEntity
import com.retro99.database.api.catalogue.CatalogueDocumentKey
import com.retro99.database.api.catalogue.CatalogueDocumentsDatabase
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Catalogue_acquisitions
import com.retro99.database.implementation.Catalogue_book_sources
import com.retro99.database.implementation.Catalogue_documents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [database] is asked on every call, so a profile switch is never served from a stale handle. */
internal class CatalogueAcquisitionsSqlDelightDao(
    private val database: () -> AppDatabase,
) : CatalogueAcquisitionsDatabase {
    private val queries get() = database().catalogueAcquisitionQueries

    override suspend fun insert(acquisition: CatalogueAcquisitionEntity) = withContext(Dispatchers.IO) {
        queries.insertCatalogueAcquisition(
            request_id = acquisition.requestId,
            source_id = acquisition.sourceId,
            publication_key = acquisition.publicationKey,
            detail_identity = acquisition.detailIdentity,
            detail_url = acquisition.detailUrl,
            representation_key = acquisition.representationKey,
            title = acquisition.title,
            author = acquisition.author,
            cover_reference = acquisition.coverReference,
            catalogue_name = acquisition.catalogueName,
            state = acquisition.state,
            queue_position = acquisition.queuePosition,
            staging_path = acquisition.stagingPath,
            expected_size_bytes = acquisition.expectedSizeBytes,
            bytes_so_far = acquisition.bytesSoFar,
            local_hash = acquisition.localHash,
            library_book_id = acquisition.libraryBookId,
            failure_reason = acquisition.failureReason,
            created_at = acquisition.createdAt,
            updated_at = acquisition.updatedAt,
            completed_at = acquisition.completedAt,
            attempts = acquisition.attempts.toLong(),
        )
        Unit
    }

    override suspend fun update(acquisition: CatalogueAcquisitionEntity) = withContext(Dispatchers.IO) {
        queries.updateCatalogueAcquisition(
            detail_url = acquisition.detailUrl,
            state = acquisition.state,
            queue_position = acquisition.queuePosition,
            staging_path = acquisition.stagingPath,
            expected_size_bytes = acquisition.expectedSizeBytes,
            bytes_so_far = acquisition.bytesSoFar,
            local_hash = acquisition.localHash,
            library_book_id = acquisition.libraryBookId,
            failure_reason = acquisition.failureReason,
            updated_at = acquisition.updatedAt,
            completed_at = acquisition.completedAt,
            attempts = acquisition.attempts.toLong(),
            request_id = acquisition.requestId,
        )
        Unit
    }

    override suspend fun updateProgress(
        requestId: String,
        bytesSoFar: Long,
        expectedSizeBytes: Long?,
        updatedAt: Long,
    ) = withContext(Dispatchers.IO) {
        queries.updateCatalogueAcquisitionProgress(bytesSoFar, expectedSizeBytes, updatedAt, requestId)
        Unit
    }

    override suspend fun get(requestId: String): CatalogueAcquisitionEntity? = withContext(Dispatchers.IO) {
        queries.getCatalogueAcquisition(requestId).executeAsOneOrNull()?.toEntity()
    }

    override suspend fun findUnfinished(
        sourceId: String,
        publicationKey: String,
        representationKey: String,
    ): CatalogueAcquisitionEntity? = withContext(Dispatchers.IO) {
        queries.findUnfinishedCatalogueAcquisition(sourceId, publicationKey, representationKey)
            .executeAsOneOrNull()
            ?.toEntity()
    }

    override suspend fun getAll(): List<CatalogueAcquisitionEntity> = withContext(Dispatchers.IO) {
        queries.getAllCatalogueAcquisitions().executeAsList().map(Catalogue_acquisitions::toEntity)
    }

    override suspend fun getBySource(sourceId: String): List<CatalogueAcquisitionEntity> =
        withContext(Dispatchers.IO) {
            queries.getCatalogueAcquisitionsBySource(sourceId).executeAsList()
                .map(Catalogue_acquisitions::toEntity)
        }

    override suspend fun getByStates(states: List<String>): List<CatalogueAcquisitionEntity> =
        withContext(Dispatchers.IO) {
            if (states.isEmpty()) return@withContext emptyList()
            queries.getCatalogueAcquisitionsByStates(states).executeAsList()
                .map(Catalogue_acquisitions::toEntity)
        }

    override suspend fun getCompletedSince(sinceMillis: Long): List<CatalogueAcquisitionEntity> =
        withContext(Dispatchers.IO) {
            queries.getCatalogueAcquisitionsCompletedSince(sinceMillis).executeAsList()
                .map(Catalogue_acquisitions::toEntity)
        }

    override suspend fun nextQueuePosition(): Long = withContext(Dispatchers.IO) {
        (queries.maxCatalogueAcquisitionQueuePosition().executeAsOne().MAX ?: 0L) + 1L
    }

    override suspend fun interruptRunning(updatedAt: Long): Int = withContext(Dispatchers.IO) {
        val database = database()
        database.transactionWithResult {
            val running = database.catalogueAcquisitionQueries.getCatalogueAcquisitionsByStates(
                listOf(
                    CatalogueAcquisitionEntity.STATE_DOWNLOADING,
                    CatalogueAcquisitionEntity.STATE_CHECKING,
                    CatalogueAcquisitionEntity.STATE_ADDING,
                ),
            ).executeAsList().size
            database.catalogueAcquisitionQueries.interruptRunningCatalogueAcquisitions(updatedAt)
            running
        }
    }

    override suspend fun delete(requestId: String) = withContext(Dispatchers.IO) {
        queries.deleteCatalogueAcquisition(requestId)
        Unit
    }

    override suspend fun deleteCompleted() = withContext(Dispatchers.IO) {
        queries.deleteCompletedCatalogueAcquisitions()
        Unit
    }

    override suspend fun deleteCompletedBefore(beforeMillis: Long) = withContext(Dispatchers.IO) {
        queries.deleteCatalogueAcquisitionsCompletedBefore(beforeMillis)
        Unit
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAllCatalogueAcquisitions()
        Unit
    }

    override fun observeAll(): Flow<List<CatalogueAcquisitionEntity>> =
        queries.getAllCatalogueAcquisitions()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(Catalogue_acquisitions::toEntity) }
}

internal class CatalogueBookSourcesSqlDelightDao(
    private val database: () -> AppDatabase,
) : CatalogueBookSourcesDatabase {
    private val queries get() = database().catalogueBookSourceQueries

    override suspend fun insert(source: CatalogueBookSourceEntity) = withContext(Dispatchers.IO) {
        queries.insertCatalogueBookSource(
            id = source.id,
            library_book_id = source.libraryBookId,
            source_id = source.sourceId,
            catalogue_name = source.catalogueName,
            catalogue_origin = source.catalogueOrigin,
            publication_key = source.publicationKey,
            detail_identity = source.detailIdentity,
            selected_format = source.selectedFormat,
            rights_text = source.rightsText,
            catalogue_updated = source.catalogueUpdated,
            content_hash = source.contentHash,
            acquired_at = source.acquiredAt,
        )
        Unit
    }

    override suspend fun getForBook(libraryBookId: String): List<CatalogueBookSourceEntity> =
        withContext(Dispatchers.IO) {
            queries.getCatalogueBookSourcesForBook(libraryBookId).executeAsList()
                .map(Catalogue_book_sources::toEntity)
        }

    override suspend fun getForPublication(
        sourceId: String,
        publicationKey: String,
    ): List<CatalogueBookSourceEntity> = withContext(Dispatchers.IO) {
        queries.getCatalogueBookSourcesForPublication(sourceId, publicationKey).executeAsList()
            .map(Catalogue_book_sources::toEntity)
    }

    override suspend fun getForSource(sourceId: String): List<CatalogueBookSourceEntity> =
        withContext(Dispatchers.IO) {
            queries.getCatalogueBookSourcesForSource(sourceId).executeAsList()
                .map(Catalogue_book_sources::toEntity)
        }

    override fun observeForSource(sourceId: String): Flow<List<CatalogueBookSourceEntity>> =
        queries.observeCatalogueBookSourcesForSource(sourceId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(Catalogue_book_sources::toEntity) }

    override suspend fun countBooksForSource(sourceId: String): Long = withContext(Dispatchers.IO) {
        queries.countCatalogueBooksForSource(sourceId).executeAsOne()
    }

    override suspend fun moveToBook(fromLibraryBookId: String, toLibraryBookId: String) =
        withContext(Dispatchers.IO) {
            queries.moveCatalogueBookSources(toLibraryBookId, fromLibraryBookId)
            Unit
        }

    override suspend fun deleteForBook(libraryBookId: String) = withContext(Dispatchers.IO) {
        queries.deleteCatalogueBookSourcesForBook(libraryBookId)
        Unit
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAllCatalogueBookSources()
        Unit
    }
}

internal class CatalogueDocumentsSqlDelightDao(
    private val database: () -> AppDatabase,
) : CatalogueDocumentsDatabase {
    private val queries get() = database().catalogueDocumentQueries

    override suspend fun upsert(document: CatalogueDocumentEntity) = withContext(Dispatchers.IO) {
        queries.upsertCatalogueDocument(
            source_id = document.sourceId,
            access_generation = document.accessGeneration,
            request_url = document.requestUrl,
            content_type = document.contentType,
            etag = document.eTag,
            last_modified = document.lastModified,
            stored_at = document.storedAt,
            payload = document.payload,
            size_bytes = document.sizeBytes,
        )
        Unit
    }

    override suspend fun get(
        sourceId: String,
        accessGeneration: Long,
        requestUrl: String,
    ): CatalogueDocumentEntity? = withContext(Dispatchers.IO) {
        queries.getCatalogueDocument(sourceId, accessGeneration, requestUrl).executeAsOneOrNull()?.toEntity()
    }

    override suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        queries.catalogueDocumentsTotalSize().executeAsOne()
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        queries.countCatalogueDocuments().executeAsOne()
    }

    override suspend fun oldestKeys(limit: Long): List<CatalogueDocumentKey> = withContext(Dispatchers.IO) {
        queries.getOldestCatalogueDocumentKeys(limit).executeAsList().map { row ->
            CatalogueDocumentKey(row.source_id, row.access_generation, row.request_url, row.size_bytes)
        }
    }

    override suspend fun delete(sourceId: String, accessGeneration: Long, requestUrl: String) =
        withContext(Dispatchers.IO) {
            queries.deleteCatalogueDocument(sourceId, accessGeneration, requestUrl)
            Unit
        }

    override suspend fun deleteForSource(sourceId: String) = withContext(Dispatchers.IO) {
        queries.deleteCatalogueDocumentsForSource(sourceId)
        Unit
    }

    override suspend fun deleteOtherGenerations(sourceId: String, accessGeneration: Long) =
        withContext(Dispatchers.IO) {
            queries.deleteCatalogueDocumentsOfOtherGenerations(sourceId, accessGeneration)
            Unit
        }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        queries.deleteAllCatalogueDocuments()
        Unit
    }
}

private fun Catalogue_acquisitions.toEntity() = CatalogueAcquisitionEntity(
    requestId = request_id,
    sourceId = source_id,
    publicationKey = publication_key,
    detailIdentity = detail_identity,
    detailUrl = detail_url,
    representationKey = representation_key,
    title = title,
    author = author,
    coverReference = cover_reference,
    catalogueName = catalogue_name,
    state = state,
    queuePosition = queue_position,
    stagingPath = staging_path,
    expectedSizeBytes = expected_size_bytes,
    bytesSoFar = bytes_so_far,
    localHash = local_hash,
    libraryBookId = library_book_id,
    failureReason = failure_reason,
    createdAt = created_at,
    updatedAt = updated_at,
    completedAt = completed_at,
    attempts = attempts.toInt(),
)

private fun Catalogue_book_sources.toEntity() = CatalogueBookSourceEntity(
    id = id,
    libraryBookId = library_book_id,
    sourceId = source_id,
    catalogueName = catalogue_name,
    catalogueOrigin = catalogue_origin,
    publicationKey = publication_key,
    detailIdentity = detail_identity,
    selectedFormat = selected_format,
    rightsText = rights_text,
    catalogueUpdated = catalogue_updated,
    contentHash = content_hash,
    acquiredAt = acquired_at,
)

private fun Catalogue_documents.toEntity() = CatalogueDocumentEntity(
    sourceId = source_id,
    accessGeneration = access_generation,
    requestUrl = request_url,
    contentType = content_type,
    eTag = etag,
    lastModified = last_modified,
    storedAt = stored_at,
    payload = payload,
    sizeBytes = size_bytes,
)
