package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.map as resultMap
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map as flowMap
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for getting a specific book by UUID from a specific server.
 * Requires serverId to query the correct server directly. An unavailable authenticated
 * repository is an expected authentication state, not an unexpected missing-book exception.
 */
@Factory
class GetBookByUuidUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {
    /**
     * Get a book by UUID from a specific server.
     * @param serverId The ID of the server to query
     * @param uuid The UUID of the book
     * @return Flow emitting the book or an error
     */
    operator fun invoke(serverId: String, uuid: String): Flow<AppResult<BookDomainModel>> = flow {
        val repository = repositoryProvider.getBooksRepository(serverId)
        if (repository == null) {
            emit(Err(AppError.AuthError("Server unavailable or not authenticated")))
            return@flow
        }

        repository.getBook(uuid)
            .mapResult { serverBook -> serverBook.toBookDomainModel() }
            .collect { result -> emit(result) }
    }

    /**
     * Maps the result inside a Flow using the Result.map function.
     */
    private fun <T, R> Flow<AppResult<T>>.mapResult(
        transform: (T) -> R
    ): Flow<AppResult<R>> = this.flowMap { result ->
        result.resultMap { transform(it) }
    }
}
