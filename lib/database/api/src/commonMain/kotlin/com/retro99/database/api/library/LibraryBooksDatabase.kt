package com.retro99.database.api.library

import kotlinx.coroutines.flow.Flow

interface LibraryBooksDatabase {
    suspend fun upsertLibraryBook(book: LibraryBookEntity)

    suspend fun upsertLocalLibraryBook(book: LibraryBookEntity)

    fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>>

    suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity?

    suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity?

    suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? {
        return getLibraryBookByContentHash(contentHash)
    }

    suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity?

    suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String)

    suspend fun upsertLocalBookFile(file: LocalBookFileEntity)

    suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity>

    suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity?

    suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String)
}
