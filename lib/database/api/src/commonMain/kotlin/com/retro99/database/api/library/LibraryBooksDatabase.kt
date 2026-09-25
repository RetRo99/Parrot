package com.retro99.database.api.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface LibraryBooksDatabase {
    suspend fun upsertLibraryBook(book: LibraryBookEntity)

    suspend fun upsertLocalLibraryBook(book: LibraryBookEntity)

    fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>>

    /** Cloud book IDs removed by an authoritative server tombstone. */
    fun observeDeletedCloudBookIds(): Flow<List<String>> = flowOf(emptyList())

    suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity?

    suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity?

    suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? {
        return getLibraryBookByContentHash(contentHash)
    }

    suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity?

    /** Includes soft-deleted Cloud rows so older revisions cannot resurrect them. */
    suspend fun getLibraryBookByCloudBookIdIncludingDeleted(
        cloudBookId: String,
    ): LibraryBookEntity? = getLibraryBookByCloudBookId(cloudBookId)

    suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String)

    suspend fun upsertLocalBookFile(file: LocalBookFileEntity)

    suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity>

    suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity?

    suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String)
}
