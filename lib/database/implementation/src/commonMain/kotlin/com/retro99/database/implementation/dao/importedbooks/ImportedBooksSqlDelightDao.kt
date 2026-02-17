package com.retro99.database.implementation.dao.importedbooks

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Imported_books
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight DAO for imported books table operations.
 */
internal class ImportedBooksSqlDelightDao(
    private val database: AppDatabase,
) {
    private val queries = database.importedBookQueries

    suspend fun upsertImportedBook(book: ImportedBookEntity) {
        withContext(Dispatchers.IO) {
            queries.upsertImportedBook(
                uuid = book.uuid,
                title = book.title,
                author = book.author,
                description = book.description,
                cover_path = book.coverPath,
                file_path = book.filePath,
                file_size = book.fileSize,
                imported_at = book.importedAt,
                last_opened_at = book.lastOpenedAt,
            )
        }
    }

    fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> {
        return queries.getAllImportedBooks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toEntity() } }
    }

    suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? {
        return withContext(Dispatchers.IO) {
            queries.getImportedBookByUuid(uuid).executeAsOneOrNull()?.toEntity()
        }
    }

    suspend fun deleteImportedBook(uuid: String) {
        withContext(Dispatchers.IO) {
            queries.deleteImportedBook(uuid)
        }
    }

    suspend fun deleteAllImportedBooks() {
        withContext(Dispatchers.IO) {
            queries.deleteAllImportedBooks()
        }
    }

    suspend fun getImportedBooksCount(): Int {
        return withContext(Dispatchers.IO) {
            queries.getImportedBooksCount().executeAsOne().toInt()
        }
    }

    suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String) {
        withContext(Dispatchers.IO) {
            queries.updateLastOpenedAt(lastOpenedAt, uuid)
        }
    }

    suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> {
        return withContext(Dispatchers.IO) {
            queries.searchImportedBooksByTitle("%$query%").executeAsList().map { it.toEntity() }
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
        importedAt = imported_at,
        lastOpenedAt = last_opened_at,
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
    override val importedAt: String,
    override val lastOpenedAt: String?,
) : ImportedBookEntity

