package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
import com.retro99.books.domain.usecase.GetImportedBookByUuidUseCase
import com.retro99.reader.domain.model.ReaderInitializationData
import com.retro99.reader.domain.model.ReadingProgressResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case that orchestrates the initialization of the reader.
 *
 * This use case handles:
 * 1. Fetching the book metadata to get the ebook file path
 * 2. Preparing/downloading the ebook file locally
 * 3. Loading initial reader settings
 * 4. Fetching reading progress with conflict detection
 *
 * For imported books (BookType.IMPORTED), it skips the download step since
 * the file is already stored locally.
 *
 * This consolidates logic that was previously spread across the ViewModel,
 * making it more testable and keeping the ViewModel focused on UI state.
 */
@Factory
class InitializeReaderUseCase(
    @Provided private val getBookByUuidUseCase: GetBookByUuidUseCase,
    @Provided private val getImportedBookByUuidUseCase: GetImportedBookByUuidUseCase,
    @Provided private val prepareEbookUseCase: PrepareEbookUseCase,
    @Provided private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
    @Provided private val getReadingProgressWithConflictUseCase: GetReadingProgressWithConflictUseCase,
) {
    /**
     * Initializes the reader for the given book.
     *
     * @param bookUuid The UUID of the book to open in the reader
     * @param bookType The type of book to open (EBOOK, AUDIOBOOK, READALOUD, or IMPORTED)
     * @return [ReaderInitializationData] containing everything needed to open the publication,
     *         or an error if initialization fails
     */
    suspend operator fun invoke(
        bookUuid: String,
        bookType: BookType,
    ): AppResult<ReaderInitializationData> =
        coroutineScope {
            // Handle imported books separately - they don't need download
            if (bookType == BookType.IMPORTED) {
                return@coroutineScope initializeImportedBook(bookUuid)
            }

            val bookResult = getBookByUuidUseCase(bookUuid).first()

            bookResult.flatMap { book ->
                val ebookFilePath = when (bookType) {
                    BookType.READALOUD -> book.readaloud?.filepath
                    BookType.AUDIOBOOK -> book.audiobook?.filepath
                    BookType.EBOOK -> book.ebook?.filepath
                    BookType.IMPORTED -> null // Handled above
                } ?: return@coroutineScope Err(
                    AppError.UnknownError(Throwable("Book has no $bookType file"))
                )
                prepareEbookUseCase(bookUuid, ebookFilePath, bookType).flatMap { localPath ->
                    val settingsDeferred = async {
                        getReaderSettingsUseCase().first()
                    }
                    val progressDeferred = async {
                        getReadingProgressWithConflictUseCase(bookUuid)
                    }

                    val settings = settingsDeferred.await()
                    val progressResult = progressDeferred.await()
                        .getOrElse { ReadingProgressResult.Resolved(null) }

                    Ok(
                        ReaderInitializationData(
                            bookUuid = bookUuid,
                            bookTitle = book.title,
                            bookCoverUrl = book.coverUrl,
                            localEbookPath = localPath,
                            bookType = bookType,
                            initialSettings = settings,
                            progressResult = progressResult,
                        )
                    )
                }
            }
        }

    /**
     * Initializes the reader for an imported book.
     * Imported books are already stored locally, so no download is needed.
     */
    private suspend fun initializeImportedBook(bookUuid: String): AppResult<ReaderInitializationData> {
        return getImportedBookByUuidUseCase(bookUuid).flatMap { book ->
            val settings = getReaderSettingsUseCase().first()

            Ok(
                ReaderInitializationData(
                    bookUuid = book.uuid,
                    bookTitle = book.title,
                    bookCoverUrl = book.coverUrl,
                    localEbookPath = book.filePath,
                    bookType = BookType.IMPORTED,
                    initialSettings = settings,
                    // TODO: Add progress tracking for imported books
                    progressResult = ReadingProgressResult.Resolved(null),
                )
            )
        }
    }
}
