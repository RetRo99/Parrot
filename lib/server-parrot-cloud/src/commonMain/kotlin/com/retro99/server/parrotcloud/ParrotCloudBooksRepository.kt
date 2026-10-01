package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Parrot Cloud has no book list of its own. Books in Parrot Cloud are books in your
 * library, listed once by the `local` library repository with their Parrot copies.
 */
class ParrotCloudBooksRepository(
    private val serverConfig: ServerConfig,
) : ServerBooksRepository {

    override val serverId: String = serverConfig.id

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(emptyList()))

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> =
        flowOf(Err(AppError.NotFoundError("Parrot Cloud books are in your library: $uuid")))

    override suspend fun saveBook(book: ServerBook): CompletableResult =
        Err(AppError.UnknownError(UnsupportedOperationException("Books are added by import")))

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = Ok(emptyList())
}
