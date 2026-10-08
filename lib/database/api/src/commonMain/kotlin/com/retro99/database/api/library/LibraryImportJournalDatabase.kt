package com.retro99.database.api.library

/**
 * An import that is moving a file into the library. [coverPath] is set only when the import
 * also writes a cover, which is only for a new book. [createdAt] is epoch milliseconds.
 */
data class LibraryImportJournalEntry(
    val entryId: String,
    val libraryBookId: String,
    val mediaType: String,
    val libraryPath: String,
    val coverPath: String?,
    val createdAt: Long,
)

/**
 * Written before an import moves its file and cleared after the library rows are committed.
 * An entry found later belongs to an import that never finished.
 */
interface LibraryImportJournalDatabase {
    suspend fun record(entry: LibraryImportJournalEntry)

    suspend fun getAll(): List<LibraryImportJournalEntry>

    suspend fun clear(entryId: String)
}
