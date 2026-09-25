package com.retro99.database.implementation.dao.importedbooks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Imported_books
import com.retro99.database.implementation.dao.library.deleteOrphanedLibraryBookState
import com.retro99.database.implementation.dao.library.upsertLibraryBookRow
import com.retro99.database.implementation.dao.library.upsertLocalBookFileRow
import com.retro99.database.implementation.dao.library.upsertLocalLibraryBookRow
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight DAO for imported books table operations.
 */
internal class ImportedBooksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {

    suspend fun upsertImportedBook(book: ImportedBookEntity) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().upsertImportedBookRow(book)
        }
    }

    suspend fun upsertImportedBookWithLibraryMapping(
        book: ImportedBookEntity,
        mutation: LibraryBookMutation,
    ) {
        withContext(Dispatchers.IO) {
            val database = databaseManager.getDatabase()
            database.transaction {
                database.upsertImportedBookRow(book)
                if (mutation.intentionalReimport) {
                    database.upsertLibraryBookRow(mutation.libraryBook)
                } else {
                    database.upsertLocalLibraryBookRow(mutation.libraryBook)
                }
                database.upsertLocalBookFileRow(mutation.localBookFile)
                database.syncOutboxQueries.enqueue(mutation.outboxEntry)
            }
        }
    }

    suspend fun saveRestoredBookWithLibraryMapping(
        book: ImportedBookEntity,
        libraryBook: LibraryBookEntity,
        localBookFile: LocalBookFileEntity,
        transfer: CloudFileTransferEntity,
        position: PositionEntity?,
    ) {
        withContext(Dispatchers.IO) {
            val database = databaseManager.getDatabase()
            database.transaction {
                database.persistRestoredBookWithLibraryMapping(
                    book,
                    libraryBook,
                    localBookFile,
                    transfer,
                    position,
                )
            }
        }
    }

    suspend fun saveRestoredBookWithLibraryMappingIfTransferActive(
        book: ImportedBookEntity,
        libraryBook: LibraryBookEntity,
        localBookFile: LocalBookFileEntity,
        transfer: CloudFileTransferEntity,
        position: PositionEntity?,
    ): Boolean = withContext(Dispatchers.IO) {
        val database = databaseManager.getDatabase()
        database.transactionWithResult {
            val state = database.cloudFileTransferQueries
                .getCloudFileTransfer(transfer.transferId)
                .executeAsOneOrNull()
                ?.state
            if (state !in ACTIVE_TRANSFER_STATES) return@transactionWithResult false
            database.persistRestoredBookWithLibraryMapping(
                book,
                libraryBook,
                localBookFile,
                transfer,
                position,
            )
            true
        }
    }

    fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> {
        return databaseManager.getDatabase().importedBookQueries.getAllImportedBooks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toEntity() } }
    }

    suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().importedBookQueries
                .getImportedBookByUuid(uuid)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().importedBookQueries
                .getImportedBookByContentHash(contentHash)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun deleteImportedBook(uuid: String) {
        withContext(Dispatchers.IO) {
            val database = databaseManager.getDatabase()
            database.transaction {
                database.localBookFileQueries.deleteLocalBookFileByImportedBookUuid(uuid)
                database.importedBookQueries.deleteImportedBook(uuid)
                database.deleteOrphanedLibraryBookState()
            }
        }
    }

    suspend fun deleteAllImportedBooks() {
        withContext(Dispatchers.IO) {
            val database = databaseManager.getDatabase()
            database.transaction {
                database.localBookFileQueries.deleteAllLocalBookFiles()
                database.importedBookQueries.deleteAllImportedBooks()
                database.deleteOrphanedLibraryBookState()
            }
        }
    }

    suspend fun getImportedBooksCount(): Int {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().importedBookQueries
                .getImportedBooksCount()
                .executeAsOne()
                .toInt()
        }
    }

    suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().importedBookQueries.updateLastOpenedAt(lastOpenedAt, uuid)
        }
    }

    suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().importedBookQueries
                .searchImportedBooksByTitle("%$query%")
                .executeAsList()
                .map { it.toEntity() }
        }
    }

    private fun Imported_books.toEntity(): ImportedBookEntity = ImportedBookEntityImpl(
        uuid = uuid,
        title = title,
        author = author,
        description = description,
        coverPath = cover_path,
        filePath = file_path,
        fileSize = file_size,
        contentHash = content_hash,
        contentHashAlgorithm = content_hash_algorithm,
        importedAt = imported_at,
        lastOpenedAt = last_opened_at,
        bookType = book_type,
        publicationDate = publication_date,
        origin = origin,
        cloudBookFileId = cloud_book_file_id,
    )
}

private val ACTIVE_TRANSFER_STATES = listOf("pending", "transferring", "verifying", "finalizing")

private fun AppDatabase.persistRestoredBookWithLibraryMapping(
    book: ImportedBookEntity,
    libraryBook: LibraryBookEntity,
    localBookFile: LocalBookFileEntity,
    transfer: CloudFileTransferEntity,
    position: PositionEntity?,
) {
    upsertImportedBookRow(book)
    upsertLocalLibraryBookRow(libraryBook)
    libraryBook.cloudBookId?.let { cloudBookId ->
        libraryBookQueries.attachCloudBookId(cloudBookId, libraryBook.libraryBookId)
    }
    upsertLocalBookFileRow(localBookFile)
    cloudFileTransferQueries.insertCloudFileTransfer(
        transfer_id = transfer.transferId,
        server_id = transfer.serverId,
        direction = transfer.direction,
        library_book_id = transfer.libraryBookId,
        cloud_book_id = transfer.cloudBookId,
        cloud_book_file_id = transfer.cloudBookFileId,
        media_type = transfer.mediaType,
        local_source_uuid = transfer.localSourceUuid,
        staging_path = transfer.stagingPath,
        size_bytes = transfer.sizeBytes,
        bytes_transferred = transfer.bytesTransferred,
        content_hash = transfer.contentHash,
        content_hash_algorithm = transfer.contentHashAlgorithm,
        upload_id = transfer.uploadId,
        storage_path = transfer.storagePath,
        tus_upload_url = transfer.tusUploadUrl,
        tus_expires_at = transfer.tusExpiresAt,
        rights_attestation = transfer.rightsAttestation,
        state = transfer.state,
        attempt_count = transfer.attemptCount.toLong(),
        next_attempt_at = transfer.nextAttemptAt,
        last_error = transfer.lastError,
        created_at = transfer.createdAt,
        updated_at = transfer.updatedAt,
    )
    position?.let { restoredPosition ->
        positionQueries.upsertPosition(
            book_uuid = restoredPosition.bookUuid,
            library_book_id = restoredPosition.libraryBookId,
            local_generation = restoredPosition.localGeneration,
            remote_revision = restoredPosition.remoteRevision,
            timestamp = restoredPosition.timestamp,
            created_at = restoredPosition.createdAt,
            updated_at = restoredPosition.updatedAt,
            locator_href = restoredPosition.locatorHref,
            locator_type = restoredPosition.locatorType,
            locator_title = restoredPosition.locatorTitle,
            locator_target = restoredPosition.locatorTarget?.toLong(),
            css_selector = restoredPosition.cssSelector,
            audio_timestamp_ms = restoredPosition.audioTimestampMs,
            chapter_index = restoredPosition.chapterIndex?.toLong(),
            progression = restoredPosition.progression,
            total_chapters = restoredPosition.totalChapters?.toLong(),
            total_duration_ms = restoredPosition.totalDurationMs,
            total_progression = restoredPosition.totalProgression,
            position = restoredPosition.position?.toLong(),
        )
    }
}

private fun AppDatabase.upsertImportedBookRow(book: ImportedBookEntity) {
    importedBookQueries.upsertImportedBook(
        uuid = book.uuid,
        title = book.title,
        author = book.author,
        description = book.description,
        cover_path = book.coverPath,
        file_path = book.filePath,
        file_size = book.fileSize,
        content_hash = book.contentHash,
        content_hash_algorithm = book.contentHashAlgorithm,
        imported_at = book.importedAt,
        last_opened_at = book.lastOpenedAt,
        book_type = book.bookType,
        publication_date = book.publicationDate,
        origin = book.origin,
        cloud_book_file_id = book.cloudBookFileId,
    )
}

/**
 * Implementation of ImportedBookEntity for database results.
 */
private data class ImportedBookEntityImpl(
    override val uuid: String,
    override val title: String,
    override val author: String?,
    override val description: String?,
    override val coverPath: String?,
    override val filePath: String,
    override val fileSize: Long,
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val importedAt: String,
    override val lastOpenedAt: String?,
    override val bookType: String,
    override val publicationDate: String?,
    override val origin: String,
    override val cloudBookFileId: String?,
) : ImportedBookEntity
