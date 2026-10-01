package com.retro99.books.domain.usecase

import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class GetBooksBySeriesUseCase(
    @Provided private val getBooksUseCase: GetBooksUseCase,
) {
    operator fun invoke(seriesName: String): Flow<AppResult<List<BookDomainModel>>> {
        return getBooksUseCase(groupLinked = false).map { result ->
            result.map { books ->
                books.filterIsInstance<BookDomainModel.StorytellerBook>()
                    .filter { book ->
                        book.series.any { it.name.equals(seriesName, ignoreCase = true) }
                    }.sortedWith(
                        compareBy(
                            { book ->
                                book.series.find { it.name.equals(seriesName, ignoreCase = true) }?.position ?: Double.MAX_VALUE
                            },
                            { it.title },
                        ),
                    )
            }
        }
    }
}
