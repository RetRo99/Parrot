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
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.ProgressOwnerRef
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
        selectedLocalFilePath: String? = null,
        progressBookUuid: String? = null,
        progressAdapterId: LibraryAdapterId? = null,
        progressOwner: ProgressOwnerRef? = null,
    ): AppResult<ReaderInitializationData> =
        coroutineScope {
            val bookResult = getBookByUuidUseCase(serverId, bookUuid).first()

            bookResult.flatMap { book ->
                when (book) {
                    is BookDomainModel.LocalBook -> initializeImportedBook(
                        book,
                        bookType,
                        selectedLocalFilePath,
                        progressBookUuid ?: bookUuid,
                        progressAdapterId,
                        progressOwner,
                    )
                    is BookDomainModel.StorytellerBook -> initializeStorytellerBook(
                        book,
                        bookType,
                        selectedLocalFilePath,
                        progressBookUuid ?: bookUuid,
                        progressAdapterId,
                        progressOwner,
                    )
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
        selectedLocalFilePath: String?,
        progressBookUuid: String,
        progressAdapterId: LibraryAdapterId?,
        progressOwner: ProgressOwnerRef?,
    ): AppResult<ReaderInitializationData> = coroutineScope {
        val ebookFilePath = when (bookType) {
            BookType.READALOUD -> book.readaloud?.filepath
            BookType.AUDIOBOOK -> book.audiobook?.filepath
            BookType.EBOOK -> book.ebook?.filepath
        } ?: return@coroutineScope Err(
            AppError.UnknownError(Throwable("Book has no $bookType file"))
        )

        val preparedPath = selectedLocalFilePath?.let(::Ok)
            ?: prepareEbookUseCase(book.uuid, ebookFilePath, bookType)
        preparedPath.flatMap { localPath ->
            val settingsDeferred = async {
                getReaderSettingsUseCase().first()
            }
            val progressDeferred = async {
                getReadingProgressWithConflictUseCase(
                    serverId = book.serverId,
                    bookUuid = progressBookUuid,
                    expectedAdapterId = progressAdapterId,
                    progressOwner = progressOwner,
                )
            }

            val settings = settingsDeferred.await()
            val progressResult = progressDeferred.await()
                .getOrElse { ReadingProgressResult.Resolved(null) }

            Ok(
                ReaderInitializationData(
                    serverId = book.serverId,
                    bookUuid = book.uuid,
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

    /**
     * Initializes the reader for an imported book.
     * Imported books are already stored locally, so no download is needed.
     *
     * @param book The imported book to initialize
     * @param requestedBookType The book type requested by the caller (should match book.bookType)
     */
    private suspend fun initializeImportedBook(
        book: BookDomainModel.LocalBook,
        requestedBookType: BookType,
        selectedLocalFilePath: String?,
        progressBookUuid: String,
        progressAdapterId: LibraryAdapterId?,
        progressOwner: ProgressOwnerRef?,
    ): AppResult<ReaderInitializationData> = coroutineScope {
        // Validate that the requested book type matches the stored book type
        if (requestedBookType != book.bookType) {
            return@coroutineScope Err(
                AppError.UnknownError(
                    Throwable(
                        "Book type mismatch: requested $requestedBookType but book is ${book.bookType}"
                    )
                )
            )
        }

        val settingsDeferred = async {
            getReaderSettingsUseCase().first()
        }
        val progressDeferred = async {
            getReadingProgressWithConflictUseCase(
                serverId = book.serverId,
                bookUuid = progressBookUuid,
                expectedAdapterId = progressAdapterId,
                progressOwner = progressOwner,
            )
        }

        val settings = settingsDeferred.await()
        val progressResult = progressDeferred.await()
            .getOrElse { ReadingProgressResult.Resolved(null) }

        Ok(
            ReaderInitializationData(
                serverId = book.serverId,
                bookUuid = book.uuid,
                bookTitle = book.title,
                bookCoverUrl = book.coverUrl,
                localEbookPath = selectedLocalFilePath ?: book.filePath,
                bookType = book.bookType,
                initialSettings = settings,
                progressResult = progressResult,
            )
        )
    }
}
