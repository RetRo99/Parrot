package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.books.domain.model.links.linkedCopiesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** The other copies a book is linked to, for the "Also in" list on its detail screen. */
@Factory
class ObserveLinkedCopiesUseCase(
    private val getBooksUseCase: GetBooksUseCase,
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(serverId: String, uuid: String): Flow<List<LinkedCopy>> =
        bookLinksRepository.observeLinks()
            // Only links that could name this book matter. Most books aren't linked, and
            // then every source's book list stays unloaded.
            .map { links ->
                links.filter { link -> link.members.any { member -> member.id == uuid } }
            }
            .distinctUntilChanged()
            .flatMapLatest { links ->
                if (links.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    getBooksUseCase(groupLinked = false).map { booksResult ->
                        val books = booksResult.getOrElse { emptyList() }
                        val book = books.firstOrNull { candidate ->
                            candidate.serverId == serverId && candidate.uuid == uuid
                        }
                        if (book == null) emptyList() else linkedCopiesOf(book, books, links)
                    }
                }
            }
            .distinctUntilChanged()
}
