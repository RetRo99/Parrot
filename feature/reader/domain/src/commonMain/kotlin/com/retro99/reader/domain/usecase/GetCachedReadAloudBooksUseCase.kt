package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.reader.domain.ReaderSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for getting all ReadAloud books that are cached locally.
 * Perfect for Android Auto browsing where only downloaded books can be played.
 */
@Factory
class GetCachedReadAloudBooksUseCase(
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
) {
    /**
     * Returns a list of ReadAloud books that are cached locally.
     * This is a suspend function because checking cache status requires disk access.
     *
     * @return List of books filtered to only ReadAloud books that are cached
     */
    suspend operator fun invoke(): AppResult<List<BookDomainModel>> {
        val allBooks = getBooksUseCase().first().getOrElse { emptyList() }

        // Filter to only ReadAloud books that are cached
        val cachedReadAloudBooks = allBooks.filter { book ->
            hasReadAloudSupport(book)
        }.filter { book ->
            isReadAloudCached(book)
        }

        return Ok(cachedReadAloudBooks)
    }

    /**
     * Checks if a book has ReadAloud support (has readaloud media files).
     */
    private fun hasReadAloudSupport(book: BookDomainModel): Boolean {
        return when (book) {
            is BookDomainModel.StorytellerBook -> book.readaloud != null
            is BookDomainModel.LibraryBook -> book.mediaResources.any { resource ->
                resource.mediaType == BookType.READALOUD.value
            }
        }
    }

    /**
     * Checks if the ReadAloud version of the book is cached locally.
     */
    private suspend fun isReadAloudCached(book: BookDomainModel): Boolean {
        return when (book) {
            is BookDomainModel.StorytellerBook -> {
                readerSettingsRepository.isEbookCached(book.uuid, BookType.READALOUD)
            }
            // Library books are on the device when they have a device copy (I6).
            is BookDomainModel.LibraryBook -> book.deviceFilePath(BookType.READALOUD) != null
        }
    }
}

