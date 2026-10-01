package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveCopyUseCaseTest {

    @Test
    fun `a library copy resolves to your library when it lists the book`() = runTest {
        // Given
        val classUnderTest = ResolveCopyUseCase(
            provider(repository(LOCAL_SERVER_ID, ServerType.Local, "book-1", isLocal = true)),
        )

        // When
        val listed = classUnderTest(CopyKey(CopySource.Library, "book-1"))
        val missing = classUnderTest(CopyKey(CopySource.Library, "book-2"))

        // Then
        assertEquals(LOCAL_SERVER_ID to "book-1", listed)
        assertNull(missing)
    }

    @Test
    fun `a server copy resolves to the first server of its type that has the book`() = runTest {
        // Given
        val classUnderTest = ResolveCopyUseCase(
            provider(
                repository("abs-1", ServerType.Audiobookshelf, "shared-id"),
                repository("st-1", ServerType.Storyteller, "other"),
                repository("st-2", ServerType.Storyteller, "shared-id"),
                repository("st-3", ServerType.Storyteller, "shared-id"),
            ),
        )

        // When
        val storyteller = classUnderTest(CopyKey(CopySource.Storyteller, "shared-id"))
        val audiobookshelf = classUnderTest(CopyKey(CopySource.Audiobookshelf, "shared-id"))

        // Then
        assertEquals("st-2" to "shared-id", storyteller)
        assertEquals("abs-1" to "shared-id", audiobookshelf)
    }

    @Test
    fun `resolution returns null when no configured server has the book`() = runTest {
        // Given
        val classUnderTest = ResolveCopyUseCase(
            provider(
                repository("st-1", ServerType.Storyteller, "other"),
                failingRepository("st-2"),
            ),
        )

        // When
        val resolved = classUnderTest(CopyKey(CopySource.Storyteller, "missing"))

        // Then
        assertNull(resolved)
    }

    private fun provider(vararg repositories: ServerBooksRepository) =
        object : AuthenticatedRepositoryProvider {
            override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
                flowOf(repositories.toList())

            override suspend fun getBooksRepositories() = repositories.toList()

            override suspend fun getBooksRepository(serverId: String) =
                repositories.firstOrNull { repository -> repository.serverId == serverId }

            override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? =
                null

            override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
                flowOf(emptyList())

            override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> =
                emptyList()
        }

    private fun repository(
        serverId: String,
        serverType: ServerType,
        vararg uuids: String,
        isLocal: Boolean = false,
    ) = FakeBooksRepository(
        serverId = serverId,
        result = Ok(
            uuids.map { uuid ->
                ServerBook(
                    uuid = uuid,
                    serverId = serverId,
                    title = "Book $uuid",
                    description = null,
                    coverUrl = null,
                    authors = emptyList(),
                    narrators = emptyList(),
                    series = emptyList(),
                    tags = emptyList(),
                    hasEbook = true,
                    hasAudiobook = false,
                    hasReadaloud = false,
                    isLocal = isLocal,
                    serverType = serverType,
                )
            },
        ),
    )

    private fun failingRepository(serverId: String) = FakeBooksRepository(
        serverId = serverId,
        result = Err(AppError.NotFoundError("offline")),
    )

    private class FakeBooksRepository(
        override val serverId: String,
        private val result: AppResult<List<ServerBook>>,
    ) : ServerBooksRepository {
        override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(result)

        override fun getBook(uuid: String): Flow<AppResult<ServerBook>> =
            flowOf(Err(AppError.NotFoundError(uuid)))

        override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)

        override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> =
            Ok(emptyList())
    }
}
