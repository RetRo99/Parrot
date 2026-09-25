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

    /**
     * Saves a restored replica only while its transfer remains active. The
     * implementation must check and persist in the same transaction so a late
     * finalizer cannot resurrect a cancelled download.
     */
    suspend fun saveRestoredBookWithLibraryMappingIfTransferActive(
        book: ImportedBookEntity,
        libraryBook: LibraryBookEntity,
        localBookFile: LocalBookFileEntity,
        transfer: CloudFileTransferEntity,
        position: PositionEntity?,
    ): Boolean {
        saveRestoredBookWithLibraryMapping(book, libraryBook, localBookFile, transfer, position)
        return true
    }

    fun getAllImportedBooks(): Flow<List<ImportedBookEntity>>

    suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity?

    suspend fun getImportedBookByContentHash(contentHash: String): ImportedBookEntity?

    suspend fun deleteImportedBook(uuid: String)

    suspend fun deleteAllImportedBooks()

    suspend fun getImportedBooksCount(): Int

    suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String)

    suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity>
}
