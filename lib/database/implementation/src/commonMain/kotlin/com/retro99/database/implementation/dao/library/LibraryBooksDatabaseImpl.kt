package com.retro99.database.implementation.dao.library

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LocalBookFileEntity
import kotlinx.coroutines.flow.Flow

internal class LibraryBooksDatabaseImpl(
    private val sqlDelightDao: LibraryBooksSqlDelightDao,
) : LibraryBooksDatabase {
    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        sqlDelightDao.upsertLibraryBook(book)
    }

    override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) {
        sqlDelightDao.upsertLocalLibraryBook(book)
    }

    override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> {
        return sqlDelightDao.getAllLibraryBooks()
    }

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? {
        return sqlDelightDao.getLibraryBookById(libraryBookId)
    }

    override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? {
        return sqlDelightDao.getLibraryBookByContentHash(contentHash)
    }

    override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) {
        sqlDelightDao.upsertLocalBookFile(file)
    }

    override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> {
        return sqlDelightDao.getLocalBookFiles(libraryBookId)
    }

    override suspend fun getLocalBookFileByImportedBookUuid(
        importedBookUuid: String,
    ): LocalBookFileEntity? {
        return sqlDelightDao.getLocalBookFileByImportedBookUuid(importedBookUuid)
    }

    override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) {
        sqlDelightDao.deleteLocalBookFileByImportedBookUuid(importedBookUuid)
    }
}
