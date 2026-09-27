package com.retro99.books.domain.usecase

import com.github.michaelbull.result.fold
import com.retro99.base.result.AppError
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GetBookByUuidUseCaseTest {
    @Test
    fun unavailableAuthenticatedRepositoryReturnsBoundedExpectedAuthError() = runTest {
        val serverId = "local-test-server-id"
        val result = GetBookByUuidUseCase(UnavailableRepositoryProvider())(
            serverId = serverId,
            uuid = "local-test-book-id",
        ).first()
        val failure = result.fold(
            success = { error("Expected the unavailable repository to fail") },
            failure = { it },
        )
        val authError = assertIs<AppError.AuthError>(failure)

        assertEquals("Server unavailable or not authenticated", authError.message)
        assertFalse(authError.message.orEmpty().contains(serverId))
        assertFalse(authError.shouldReportException)
    }

    private class UnavailableRepositoryProvider : AuthenticatedRepositoryProvider {
        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> = flowOf(emptyList())

        override suspend fun getBooksRepositories(): List<ServerBooksRepository> = emptyList()

        override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = null

        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null

        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = flowOf(emptyList())

        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }
}
