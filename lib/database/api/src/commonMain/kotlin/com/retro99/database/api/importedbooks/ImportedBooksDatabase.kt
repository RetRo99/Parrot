package com.retro99.database.api.importedbooks

import kotlinx.coroutines.flow.Flow

/**
 * Database interface for imported books operations.
 */
interface ImportedBooksDatabase {

    suspend fun upsertImportedBook(book: ImportedBookEntity)

    fun getAllImportedBooks(): Flow<List<ImportedBookEntity>>

    suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity?

    suspend fun deleteImportedBook(uuid: String)

    suspend fun deleteAllImportedBooks()

    suspend fun getImportedBooksCount(): Int

    suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String)

    suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity>
}

