package com.retro99.sync.data

import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

fun interface LocalBookUuidResolver {
    suspend fun resolve(
        libraryBookId: String,
        cloudBookId: String,
        fallback: String,
    ): String
}

/**
 * Resolves a canonical local book identity without relying on a backend
 * payload's remote identifier.
 */
@Single(binds = [LocalBookUuidResolver::class])
class DatabaseLocalBookUuidResolver(
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
) : LocalBookUuidResolver {
    override suspend fun resolve(
        libraryBookId: String,
        cloudBookId: String,
        fallback: String,
    ): String {
        val libraryBook = libraryBooksDatabase.getLibraryBookById(libraryBookId)
            ?: libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId)
        val contentHash = libraryBook?.contentHash ?: return fallback
        return importedBooksDatabase.getAllImportedBooks()
            .first()
            .firstOrNull { book -> book.contentHash == contentHash }
            ?.uuid
            ?: fallback
    }
}
