package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
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
    @Provided private val prepareEbookUseCase: PrepareEbookUseCase,
    @Provided private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
    @Provided private val getReadingProgressWithConflictUseCase: GetReadingProgressWithConflictUseCase,
) {
    /**
     * Initializes the reader for the given book.
     *
     * @param serverId The ID of the server the book belongs to
     * @param bookUuid The UUID of the book to open in the reader
     * @param bookType The type of book to open (EBOOK, AUDIOBOOK, READALOUD, or IMPORTED)
     * @return [ReaderInitializationData] containing everything needed to open the publication,
     *         or an error if initialization fails
     */
    suspend operator fun invoke(
        serverId: String,
        bookUuid: String,
        bookType: BookType,
    ): AppResult<ReaderInitializationData> =
        coroutineScope {
            val bookResult = getBookByUuidUseCase(serverId, bookUuid).first()

            bookResult.flatMap { book ->
                when (book) {
                    is BookDomainModel.LibraryBook -> initializeLibraryBook(book, bookType)
                    is BookDomainModel.StorytellerBook -> initializeStorytellerBook(book, bookType)
                }
            }
        }

    /**
     * Initializes the reader for a Storyteller book.
     * Downloads the ebook file if needed.
     */
    private suspend fun initializeStorytellerBook(
        book: BookDomainModel.StorytellerBook,
        bookType: BookType,
    ): AppResult<ReaderInitializationData> = coroutineScope {
        val ebookFilePath = when (bookType) {
            BookType.READALOUD -> book.readaloud?.filepath
            BookType.AUDIOBOOK -> book.audiobook?.filepath
            BookType.EBOOK -> book.ebook?.filepath
        } ?: return@coroutineScope Err(
            AppError.UnknownError(Throwable("Book has no $bookType file"))
        )

        prepareEbookUseCase(book.uuid, ebookFilePath, bookType).flatMap { localPath ->
            val settingsDeferred = async {
                getReaderSettingsUseCase().first()
            }
            val progressDeferred = async {
                getReadingProgressWithConflictUseCase(book.serverId, book.uuid)
            }

            val settings = settingsDeferred.await()
            val progressResult = progressDeferred.await()
                .getOrElse { ReadingProgressResult.Resolved(null) }

            Ok(
                ReaderInitializationData(
                    serverId = book.serverId,
                    bookUuid = book.uuid,
                    bookTitle = book.title,
                    bookAuthor = book.authors.joinToString(", ") { it.name },
                    bookCoverUrl = book.coverUrl,
                    localEbookPath = localPath,
                    bookType = bookType,
                    initialSettings = settings,
                    progressResult = progressResult,
                )
            )
        }
    }

    /**
     * Initializes the reader for a book in your library. It opens the device copy of the
     * requested type, read from `device_files` (I5); the reader cache isn't involved (I6).
     */
    private suspend fun initializeLibraryBook(
        book: BookDomainModel.LibraryBook,
        bookType: BookType,
    ): AppResult<ReaderInitializationData> = coroutineScope {
        val devicePath = book.deviceFilePath(bookType)
            ?: return@coroutineScope Err(
                AppError.NotFoundError("Book has no downloaded $bookType file"),
            )

        val settingsDeferred = async {
            getReaderSettingsUseCase().first()
        }
        val progressDeferred = async {
            getReadingProgressWithConflictUseCase(book.serverId, book.uuid)
        }

        val settings = settingsDeferred.await()
        val progressResult = progressDeferred.await()
            .getOrElse { ReadingProgressResult.Resolved(null) }

        Ok(
            ReaderInitializationData(
                serverId = book.serverId,
                bookUuid = book.uuid,
                bookTitle = book.title,
                bookAuthor = book.author.orEmpty(),
                bookCoverUrl = book.coverUrl,
                localEbookPath = devicePath,
                bookType = bookType,
                initialSettings = settings,
                progressResult = progressResult,
            )
        )
    }
}
