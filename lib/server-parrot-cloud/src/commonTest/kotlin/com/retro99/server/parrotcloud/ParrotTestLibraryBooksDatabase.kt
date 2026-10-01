package com.retro99.server.parrotcloud

import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class ParrotTestLibraryBooksDatabase(
    vararg initialBooks: LibraryBookEntity,
) : LibraryBooksDatabase {
    val books = initialBooks.associateBy(LibraryBookEntity::libraryBookId).toMutableMap()
    val upserted = mutableListOf<LibraryBookEntity>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        upserted += book
        books[book.libraryBookId] = book
    }

    override fun observeLibraryBooks(): Flow<List<LibraryBookEntity>> = flowOf(books.values.toList())

    override suspend fun getLibraryBookById(libraryBookId: String) = books[libraryBookId]

    override suspend fun findLibraryBookBySourceHash(algorithm: String, hash: String) =
        books.values.firstOrNull { book -> book.sourceContentHash == hash }

    override suspend fun countLibraryBooksWithDeviceFiles(): Int = 0

    override suspend fun updateLastOpenedAt(libraryBookId: String, lastOpenedAt: String) = Unit

    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) = error("Unused")

    override suspend fun deleteBookFromDevice(libraryBookId: String) {
        books.remove(libraryBookId)
    }
}

internal fun parrotTestBook(libraryBookId: String) = LibraryBookEntity(
    libraryBookId = libraryBookId,
    title = "Test book",
    addedAt = "2026-10-01T00:00:00Z",
)
