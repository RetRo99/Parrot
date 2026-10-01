package com.retro99.reader.domain.linked

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.books.domain.model.links.linkedCopiesOf
import com.retro99.books.domain.model.links.toLinkedCopy
import com.retro99.books.domain.usecase.GetBooksUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** A copy and the other copies it's linked to, when it's linked. */
data class LinkedCopies(
    val self: LinkedCopy,
    val others: List<LinkedCopy>,
) {
    val all: List<LinkedCopy> get() = listOf(self) + others
}

fun interface LinkedCopiesSource {
    /** The copy `(serverId, bookUuid)` and its linked copies; null when it isn't linked. */
    suspend fun linkedCopies(serverId: String, bookUuid: String): LinkedCopies?
}

/**
 * Reads links first, and only loads the book lists when a link can name this book, so opening
 * an unlinked book costs nothing extra.
 */
@Factory(binds = [LinkedCopiesSource::class])
class BookLinkedCopiesSource(
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val bookLinksRepository: BookLinksRepository,
) : LinkedCopiesSource {

    override suspend fun linkedCopies(serverId: String, bookUuid: String): LinkedCopies? {
        val links = bookLinksRepository.observeLinks().first()
            .filter { link -> link.members.any { member -> member.id == bookUuid } }
        if (links.isEmpty()) return null
        val books = getBooksUseCase(groupLinked = false).first().getOrElse { emptyList() }
        val book = books.firstOrNull { candidate ->
            candidate.serverId == serverId && candidate.uuid == bookUuid
        } ?: return null
        val others = linkedCopiesOf(book, books, links)
        if (others.isEmpty()) return null
        return LinkedCopies(self = book.toLinkedCopy(), others = others)
    }
}
