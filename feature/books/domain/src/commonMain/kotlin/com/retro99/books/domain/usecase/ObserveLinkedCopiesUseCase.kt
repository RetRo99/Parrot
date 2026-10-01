package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.books.domain.model.links.linkedCopiesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** The other copies a book is linked to, for the "Also in" list on its detail screen. */
@Factory
class ObserveLinkedCopiesUseCase(
    private val getBooksUseCase: GetBooksUseCase,
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    operator fun invoke(serverId: String, uuid: String): Flow<List<LinkedCopy>> = combine(
        getBooksUseCase(groupLinked = false),
        bookLinksRepository.observeLinks(),
    ) { booksResult, links ->
        val books = booksResult.getOrElse { emptyList() }
        val book = books.firstOrNull { candidate ->
            candidate.serverId == serverId && candidate.uuid == uuid
        }
        if (book == null) emptyList() else linkedCopiesOf(book, books, links)
    }.distinctUntilChanged()
}
