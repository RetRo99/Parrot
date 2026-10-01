package com.retro99.books.domain.usecase

import co.touchlab.kermit.Logger
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map as resultMap
import com.retro99.base.result.AppResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.model.links.groupLinkedBooks
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map as flowMap
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Instant

/**
 * Use case for getting all books from all authenticated servers.
 * Local books are included via LocalBooksRepository (Local server type).
 * Automatically updates when servers are added/removed or auth state changes.
 */
@Factory
class GetBooksUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    private val logger = Logger.withTag("čič")

    /**
     * @param groupLinked when true, the copies of a linked book are listed as one book: its
     * primary copy, carrying the others as `linkedCopies`. Pass false to get every copy.
     */
    operator fun invoke(groupLinked: Boolean = true): Flow<AppResult<List<BookDomainModel>>> {
        val books = observeAllCopies()
        if (!groupLinked) return books
        return combine(books, bookLinksRepository.observeLinks()) { result, links ->
            result.resultMap { copies ->
                groupLinkedBooks(
                    books = copies,
                    links = links,
                    lastOpened = copies.libraryLastOpened(),
                )
            }
        }
    }

    private fun observeAllCopies(): Flow<AppResult<List<BookDomainModel>>> {
        return repositoryProvider.observeBooksRepositories()
            .onEach { repositories ->
                logger.d { "Received ${repositories.size} repositories" }
            }
            .flatMapLatest { repositories ->
                val flows = repositories.map { repo ->
                    logger.d { "Getting books from repo: ${repo.serverId}" }
                    repo.getBooks().onEach { result ->
                        logger.d { "Repo ${repo.serverId} books result: $result" }
                    }.mapToFlow { books ->
                        logger.d { "Repo ${repo.serverId} returned ${books.size} books" }
                        books
                    }
                }

                if (flows.isEmpty()) {
                    logger.d { "No repositories, returning empty list" }
                    flowOf(Ok(emptyList()))
                } else {
                    combine(flows) { results ->
                        val aggregatedBooks = results
                            .flatMap { result -> result.getOrElse { emptyList() } }
                            .map { book -> book.toBookDomainModel() }
                            .sortedBy { book -> book.title.lowercase() }
                        logger.d {
                            "Combined ${aggregatedBooks.size} total books from ${results.size} sources"
                        }
                        Ok(aggregatedBooks)
                    }
                }
            }
    }

    private fun List<BookDomainModel>.libraryLastOpened(): Map<CopyKey, Long> =
        filterIsInstance<BookDomainModel.LibraryBook>().mapNotNull { book ->
            val openedAt = book.lastOpenedAt?.let { value -> Instant.parseOrNull(value) }
                ?: return@mapNotNull null
            book.copyKey() to openedAt.toEpochMilliseconds()
        }.toMap()

    private fun <T, R> Flow<AppResult<T>>.mapToFlow(
        transform: (T) -> R,
    ): Flow<AppResult<R>> = this.flowMap { result: AppResult<T> ->
        result.resultMap { value: T -> transform(value) }
    }
}
