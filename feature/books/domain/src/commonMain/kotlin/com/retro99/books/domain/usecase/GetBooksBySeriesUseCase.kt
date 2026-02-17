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
class GetBooksBySeriesUseCase(
    @Provided private val booksRepository: BooksRepository,
) {
    operator fun invoke(seriesUuid: String): Flow<AppResult<List<BookDomainModel.StorytellerBook>>> {
        return booksRepository.getBooks().map { result ->
            result.map { books ->
                books.filter { book ->
                    book.series.any { it.uuid == seriesUuid }
                }.sortedWith(
                    compareBy(
                        { book ->
                            book.series.find { it.uuid == seriesUuid }?.position ?: Int.MAX_VALUE
                        },
                        { it.title },
                    ),
                )
            }
        }
    }
}

