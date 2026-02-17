package com.retro99.books.domain.usecase

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.books.domain.BooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetBooksByAuthorUseCase(
    @Provided private val booksRepository: BooksRepository,
) {
    operator fun invoke(authorUuid: String): Flow<AppResult<List<BookDomainModel.StorytellerBook>>> {
        return booksRepository.getBooks().map { result ->
            result.map { books ->
                books.filter { book ->
                    book.authors.any { it.uuid == authorUuid } ||
                            book.creators.any { it.uuid == authorUuid }
                }.sortedBy { it.title.lowercase() }
            }
        }
    }
}

