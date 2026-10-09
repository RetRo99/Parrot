package com.retro99.database.implementation.dao.library

import com.retro99.database.api.library.LibraryImportJournalDatabase
import com.retro99.database.api.library.LibraryImportJournalEntry
import com.retro99.database.implementation.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** [database] is asked on every call, so a profile switch is never served from a stale handle. */
internal class LibraryImportJournalSqlDelightDao(
    private val database: () -> AppDatabase,
) : LibraryImportJournalDatabase {
    private val queries get() = database().libraryImportJournalQueries

    override suspend fun record(entry: LibraryImportJournalEntry) = withContext(Dispatchers.IO) {
        queries.insertLibraryImportJournalEntry(
            entry_id = entry.entryId,
            library_book_id = entry.libraryBookId,
            media_type = entry.mediaType,
            library_path = entry.libraryPath,
            cover_path = entry.coverPath,
            created_at = entry.createdAt,
        )
        Unit
    }

    override suspend fun getAll(): List<LibraryImportJournalEntry> = withContext(Dispatchers.IO) {
        queries.getLibraryImportJournalEntries().executeAsList().map { row ->
            LibraryImportJournalEntry(
                entryId = row.entry_id,
                libraryBookId = row.library_book_id,
                mediaType = row.media_type,
                libraryPath = row.library_path,
                coverPath = row.cover_path,
                createdAt = row.created_at,
            )
        }
    }

    override suspend fun clear(entryId: String) = withContext(Dispatchers.IO) {
        queries.deleteLibraryImportJournalEntry(entryId)
        Unit
    }
}
