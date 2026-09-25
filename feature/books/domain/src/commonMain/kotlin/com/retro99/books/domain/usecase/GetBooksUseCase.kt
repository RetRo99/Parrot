package com.retro99.books.domain.usecase

import co.touchlab.kermit.Logger
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.toBookDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** Observes one displayed row per shared library group, plus ungrouped legacy books. */
@Factory
class GetBooksUseCase(
    @Provided private val observeUnifiedServerBooksUseCase: ObserveUnifiedServerBooksUseCase,
) {
    private val logger = Logger.withTag("čič")

    operator fun invoke(): Flow<AppResult<List<BookDomainModel>>> =
        observeUnifiedServerBooksUseCase().map { unifiedBooks ->
            val books = unifiedBooks.map { unifiedBook ->
                unifiedBook.toBookDomainModel()
            }
            logger.d { "Projected ${books.size} books from the shared library" }
            Ok(books)
        }
}
