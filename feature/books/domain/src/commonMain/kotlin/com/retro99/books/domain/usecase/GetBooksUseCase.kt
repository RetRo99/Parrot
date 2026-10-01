package com.retro99.books.domain.usecase

import co.touchlab.kermit.Logger
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map as resultMap
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
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

/**
 * Use case for getting all books from all authenticated servers.
 * Local books are included via LocalBooksRepository (Local server type).
 * Automatically updates when servers are added/removed or auth state changes.
 */
@Factory
class GetBooksUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {
    private val logger = Logger.withTag("čič")

    operator fun invoke(): Flow<AppResult<List<BookDomainModel>>> {
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

    private fun <T, R> Flow<AppResult<T>>.mapToFlow(
        transform: (T) -> R,
    ): Flow<AppResult<R>> = this.flowMap { result: AppResult<T> ->
        result.resultMap { value: T -> transform(value) }
    }
}
