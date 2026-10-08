package com.retro99.database.api.library

import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow

interface LibraryBooksDatabase {
    suspend fun upsertLibraryBook(book: LibraryBookEntity)

    fun observeLibraryBooks(): Flow<List<LibraryBookEntity>>

    suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity?

    suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String): LibraryBookEntity?

    suspend fun countLibraryBooksWithDeviceFiles(): Int

    suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String)

    /** Inserts a new imported book, its first device file and its outbox upsert together. */
    suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    )

    /**
     * Deletes the book's device files, its Parrot file mirror, its finished transfers,
     * its unsent outbox mutations and its library row, in one transaction.
     */
    suspend fun deleteBookFromDevice(libraryBookId: String)
}
