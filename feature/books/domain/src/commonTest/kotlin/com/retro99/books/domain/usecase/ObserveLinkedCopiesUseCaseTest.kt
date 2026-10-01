package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.testLink
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveLinkedCopiesUseCaseTest {

    @Test
    fun `linked copies follow the links`() = runTest {
        // Given
        val links = MutableStateFlow(emptyList<BookLink>())
        val classUnderTest = ObserveLinkedCopiesUseCase(
            getBooksUseCase = GetBooksUseCase(
                provider(
                    repository("st-1", ServerType.Storyteller, "s1"),
                    repository("abs-1", ServerType.Audiobookshelf, "a1"),
                ),
            ),
            bookLinksRepository = FakeLinksRepository(links),
        )

        // When
        val before = classUnderTest("st-1", "s1").first()
        links.value = listOf(testLink("link-1", "storyteller:s1", "audiobookshelf:a1"))
        val after = classUnderTest("st-1", "s1").first()

        // Then
        assertEquals(emptyList(), before)
        assertEquals(listOf("abs-1" to "a1"), after.map { copy -> copy.serverId to copy.uuid })
    }

    private class FakeLinksRepository(
        private val links: Flow<List<BookLink>>,
    ) : BookLinksRepository {
        override fun observeLinks(): Flow<List<BookLink>> = links

        override fun observeDecisions(): Flow<Map<String, LinkDecision>> = flowOf(emptyMap())

        override suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink> =
            error("not used")

        override suspend fun unlink(copy: CopyKey): CompletableResult = error("not used")

        override suspend fun decide(
            first: CopyKey,
            second: CopyKey,
            decision: LinkDecisionType,
        ): CompletableResult = error("not used")
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

    private fun repository(serverId: String, serverType: ServerType, uuid: String) =
        object : ServerBooksRepository {
            override val serverId: String = serverId

            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(
                Ok(
                    listOf(
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
                            serverType = serverType,
                        ),
                    ),
                ),
            )

            override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = error("not used")

            override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)

            override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> =
                Ok(emptyList())
        }
}
