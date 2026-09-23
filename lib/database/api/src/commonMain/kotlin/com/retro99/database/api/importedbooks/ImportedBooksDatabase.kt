package com.retro99.database.api.importedbooks

import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import kotlinx.coroutines.flow.Flow

/**
 * Database interface for imported books operations.
 */
interface ImportedBooksDatabase {

    suspend fun upsertImportedBook(book: ImportedBookEntity)

    suspend fun upsertImportedBookWithLibraryMapping(
        book: ImportedBookEntity,
        mutation: LibraryBookMutation,
    )

    /** Persists a restored local replica and transfer completion without an upload outbox mutation. */
    suspend fun saveRestoredBookWithLibraryMapping(
        book: ImportedBookEntity,
        libraryBook: LibraryBookEntity,
        localBookFile: LocalBookFileEntity,
        transfer: CloudFileTransferEntity,
        position: PositionEntity?,
    )

    fun getAllImportedBooks(): Flow<List<ImportedBookEntity>>

    suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity?

    suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity?

    suspend fun deleteImportedBook(uuid: String)

    suspend fun deleteAllImportedBooks()

    suspend fun getImportedBooksCount(): Int

    suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String)

    suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity>
}
