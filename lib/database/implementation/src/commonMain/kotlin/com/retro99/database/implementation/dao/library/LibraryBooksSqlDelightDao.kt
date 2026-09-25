package com.retro99.database.implementation.dao.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Library_books
import com.retro99.database.implementation.Local_book_files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class LibraryBooksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().upsertLibraryBookRow(book)
        }
    }

    suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) {
        withContext(Dispatchers.IO) {
            val database = databaseManager.getDatabase()
            database.transaction {
                database.upsertLocalLibraryBookRow(book)
            }
        }
    }

    fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> {
        return databaseManager.getDatabase().libraryBookQueries.getAllLibraryBooks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { row -> row.toEntity() } }
    }

    fun observeDeletedCloudBookIds(): Flow<List<String>> {
        return databaseManager.getDatabase().libraryBookQueries.getDeletedCloudBookIds()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows }
    }

    suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries.getLibraryBookById(libraryBookId)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries
                .getLibraryBookByContentHash(contentHash)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries
                .getLibraryBookByContentHashAndAlgorithm(contentHashAlgorithm, contentHash)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries
                .getLibraryBookByCloudBookId(cloudBookId)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun getLibraryBookByCloudBookIdIncludingDeleted(
        cloudBookId: String,
    ): LibraryBookEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries
                .getLibraryBookByCloudBookIdIncludingDeleted(cloudBookId)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().libraryBookQueries
                .attachCloudBookId(cloudBookId, libraryBookId)
        }
    }

    suspend fun upsertLocalBookFile(file: LocalBookFileEntity) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().upsertLocalBookFileRow(file)
        }
    }

    suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().localBookFileQueries.getLocalBookFiles(libraryBookId)
                .executeAsList()
                .map { row -> row.toEntity() }
        }
    }

    suspend fun getLocalBookFileByImportedBookUuid(
        importedBookUuid: String,
    ): LocalBookFileEntity? {
        return withContext(Dispatchers.IO) {
            databaseManager.getDatabase().localBookFileQueries
                .getLocalBookFileByImportedBookUuid(importedBookUuid)
                .executeAsOneOrNull()
                ?.toEntity()
        }
    }

    suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) {
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().localBookFileQueries
                .deleteLocalBookFileByImportedBookUuid(importedBookUuid)
        }
    }

    private fun Library_books.toEntity(): LibraryBookEntity {
        return LibraryBookEntityImpl(
            libraryBookId = library_book_id,
            contentHash = content_hash,
            contentHashAlgorithm = content_hash_algorithm,
            title = title,
            author = author,
            format = format,
            remoteRevision = remote_revision,
            deletedAt = deleted_at,
            cloudBookId = cloud_book_id,
            metadataJson = metadata_json,
        )
    }

    private fun Local_book_files.toEntity(): LocalBookFileEntity {
        return LocalBookFileEntityImpl(
            libraryBookId = library_book_id,
            importedBookUuid = imported_book_uuid,
            fileAvailability = file_availability,
        )
    }
}

internal fun AppDatabase.upsertLibraryBookRow(book: LibraryBookEntity) {
    libraryBookQueries.upsertLibraryBook(
        library_book_id = book.libraryBookId,
        content_hash = book.contentHash,
        content_hash_algorithm = book.contentHashAlgorithm,
        title = book.title,
        author = book.author,
        format = book.format,
        remote_revision = book.remoteRevision,
        deleted_at = book.deletedAt,
        cloud_book_id = book.cloudBookId,
        metadata_json = book.metadataJson,
    )
}

internal fun AppDatabase.upsertLocalLibraryBookRow(book: LibraryBookEntity) {
    libraryBookQueries.insertLocalLibraryBook(
        library_book_id = book.libraryBookId,
        content_hash = book.contentHash,
        content_hash_algorithm = book.contentHashAlgorithm,
        title = book.title,
        author = book.author,
        format = book.format,
    )
    libraryBookQueries.updateLocalLibraryBook(
        content_hash = book.contentHash,
        content_hash_algorithm = book.contentHashAlgorithm,
        title = book.title,
        author = book.author,
        format = book.format,
        library_book_id = book.libraryBookId,
    )
}

internal fun AppDatabase.upsertLocalBookFileRow(file: LocalBookFileEntity) {
    localBookFileQueries.upsertLocalBookFile(
        library_book_id = file.libraryBookId,
        imported_book_uuid = file.importedBookUuid,
        file_availability = file.fileAvailability,
    )
}

internal fun AppDatabase.deleteOrphanedLibraryBookState() {
    libraryBookQueries.deleteUnreferencedUnsyncedLibraryBooks()
    syncOutboxQueries.deletePendingMutationsForMissingLibraryBooks(
        entity_type = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
    )
}

private data class LibraryBookEntityImpl(
    override val libraryBookId: String,
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val title: String,
    override val author: String?,
    override val format: String,
    override val remoteRevision: Long?,
    override val deletedAt: String?,
    override val cloudBookId: String?,
    override val metadataJson: String?,
) : LibraryBookEntity

private data class LocalBookFileEntityImpl(
    override val libraryBookId: String,
    override val importedBookUuid: String,
    override val fileAvailability: String,
) : LocalBookFileEntity
