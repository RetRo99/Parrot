package com.retro99.database.implementation.dao.importedbooks

import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Implementation of ImportedBooksDatabase using SQLDelight.
 */
internal class ImportedBooksDatabaseImpl(
    private val sqlDelightDao: ImportedBooksSqlDelightDao,
) : ImportedBooksDatabase {

    override suspend fun upsertImportedBook(book: ImportedBookEntity) {
        sqlDelightDao.upsertImportedBook(book)
    }

    override fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> {
        return sqlDelightDao.getAllImportedBooks()
    }

    override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? {
        return sqlDelightDao.getImportedBookByUuid(uuid)
    }

    override suspend fun deleteImportedBook(uuid: String) {
        sqlDelightDao.deleteImportedBook(uuid)
    }

    override suspend fun deleteAllImportedBooks() {
        sqlDelightDao.deleteAllImportedBooks()
    }

    override suspend fun getImportedBooksCount(): Int {
        return sqlDelightDao.getImportedBooksCount()
    }

    override suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String) {
        sqlDelightDao.updateLastOpenedAt(uuid, lastOpenedAt)
    }

    override suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> {
        return sqlDelightDao.searchImportedBooksByTitle(query)
    }
}

