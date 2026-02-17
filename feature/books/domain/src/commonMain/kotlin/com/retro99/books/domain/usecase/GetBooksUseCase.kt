package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.books.domain.BooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetBooksUseCase(
    @Provided private val booksRepository: BooksRepository,
) {
    operator fun invoke(): Flow<AppResult<List<BookDomainModel.StorytellerBook>>> {
        return booksRepository.getBooks()
    }
}

