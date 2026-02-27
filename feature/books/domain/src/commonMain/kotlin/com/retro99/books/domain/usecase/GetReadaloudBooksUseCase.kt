package com.retro99.books.domain.usecase

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for getting all read-aloud books from all authenticated servers.
 * Filters books that have read-aloud capability.
 */
@Factory
class GetReadaloudBooksUseCase(
    @Provided private val getBooksUseCase: GetBooksUseCase,
) {
    operator fun invoke(): Flow<AppResult<List<BookDomainModel>>> {
        return getBooksUseCase().map { result ->
            result.map { books ->
                books.filter { book ->
                    when (book) {
                        is BookDomainModel.StorytellerBook -> book.readaloud != null
                        is BookDomainModel.LocalBook -> book.bookType == BookType.READALOUD
                    }
                }
            }
        }
    }
}

