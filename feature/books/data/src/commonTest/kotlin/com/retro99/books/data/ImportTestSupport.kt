package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.CancellationException

/** Runs the operation inline and turns an exception into an error, like the real executor. */
internal object DirectDatabaseExecutor : DatabaseExecutor {
    override suspend fun <T> executeDatabaseOperation(
        reportException: Boolean,
        operation: suspend () -> T,
    ): AppResult<T> = try {
        Ok(operation())
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Err(AppError.UnknownError(exception))
    }
}

/** Returns canned metadata, or the error set in [failure]. Records the paths it was asked for. */
internal class FakeEpubMetadataExtractor(
    var metadata: EpubMetadata = testEpubMetadata(),
    var failure: AppError? = null,
) : EpubMetadataExtractor {
    val requestedPaths = mutableListOf<String>()

    override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> {
        requestedPaths += filePath
        return failure?.let { error -> Err(error) } ?: Ok(metadata)
    }
}

/** A library database whose imported-book insert fails while [failInserts] is set. */
internal class FailingInsertLibraryBooksDatabase(
    private val delegate: LibraryBooksDatabase,
    var failInserts: Boolean = true,
) : LibraryBooksDatabase by delegate {
    override suspend fun insertImportedBook(
        book: LibraryBookEntity,
        file: DeviceFileEntity,
        outboxEntry: SyncOutboxEntry,
    ) {
        if (failInserts) error("Injected database failure")
        delegate.insertImportedBook(book, file, outboxEntry)
    }
}

internal fun testEpubMetadata(
    title: String = "Title",
    author: String? = "Author",
    description: String? = "Description",
    coverBytes: ByteArray? = null,
    hasMediaOverlays: Boolean = false,
    publicationDate: String? = "2001-02-03",
    isbn: String? = null,
) = EpubMetadata(
    title = title,
    author = author,
    description = description,
    coverBytes = coverBytes,
    hasMediaOverlays = hasMediaOverlays,
    publicationDate = publicationDate,
    isbn = isbn,
)
