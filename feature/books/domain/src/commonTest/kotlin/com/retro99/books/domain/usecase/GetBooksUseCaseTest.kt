package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import com.retro99.base.result.AppResult
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.testLink
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetBooksUseCaseTest {

    private val links = listOf(testLink("link-1", "storyteller:s1", "audiobookshelf:a1"))

    private val classUnderTest = GetBooksUseCase(
        repositoryProvider = provider(
            repository("abs-1", ServerType.Audiobookshelf, "a1" to "Dune", "a2" to "Emma"),
            repository("st-1", ServerType.Storyteller, "s1" to "Dune"),
        ),
        bookLinksRepository = linksRepository(links),
    )

    @Test
    fun `linked copies are listed as one book`() = runTest {
        // When
        val books = classUnderTest().first().get().orEmpty()

        // Then
        assertEquals(listOf("s1", "a2"), books.map { book -> book.uuid })
        assertEquals(listOf("a1"), books.first().linkedCopies.map { copy -> copy.uuid })
    }

    @Test
    fun `every copy is listed when grouping is off`() = runTest {
        // When
        val books = classUnderTest(groupLinked = false).first().get().orEmpty()

        // Then
        assertEquals(listOf("a1", "s1", "a2"), books.map { book -> book.uuid })
        assertEquals(emptyList(), books.flatMap { book -> book.linkedCopies })
    }

    @Test
    fun `linking catalogue exposes failures and retains last successful source books`() = runTest {
        val delegate = repository("st-1", ServerType.Storyteller, "s1" to "Dune")
        val failure = AppError.NotFoundError("offline")
        val failing = object : ServerBooksRepository by delegate {
            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flow {
                emit(delegate.getBooks().first())
                delay(1)
                emit(Err(failure))
            }
        }
        val useCase = GetBooksUseCase(provider(failing), linksRepository(emptyList()))
        val snapshots = useCase.observeCatalogue().toList()
        assertEquals(listOf("s1"), snapshots.last().books.map { it.uuid })
        assertEquals(mapOf("st-1" to failure), snapshots.last().failures)
        assertEquals(emptyMap(), snapshots.first().failures)
    }

    @Test
    fun `initial catalogue failure remains distinguishable from successful empty source`() = runTest {
        val delegate = repository("st-1", ServerType.Storyteller)
        val failure = AppError.NotFoundError("offline")
        val failing = object : ServerBooksRepository by delegate {
            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Err(failure))
        }
        val snapshot = GetBooksUseCase(provider(failing), linksRepository(emptyList())).observeCatalogue().first()
        assertEquals(emptyList(), snapshot.books)
        assertEquals(mapOf("st-1" to failure), snapshot.failures)
        assertEquals(emptyMap(), GetBooksUseCase(provider(delegate), linksRepository(emptyList()))
            .observeCatalogue().first().failures)
    }

    @Test
    fun `series queries retain the server copy when its library copy is primary`() = runTest {
        // Given
        val books = linkedLibraryAndServerBooks()
        assertEquals("b1", books().first().get()!!.single().uuid)

        // When
        val seriesBooks = GetBooksBySeriesUseCase(books)("Dune").first().get().orEmpty()

        // Then
        assertEquals(listOf("s1"), seriesBooks.map { book -> book.uuid })
        assertEquals(1.0, seriesBooks.single().series.single().position)
    }

    @Test
    fun `author queries retain the server copy when its library copy is primary`() = runTest {
        // Given
        val books = linkedLibraryAndServerBooks()

        // When
        val authorBooks = GetBooksByAuthorUseCase(books)("Frank Herbert").first().get().orEmpty()

        // Then
        assertEquals(listOf("s1"), authorBooks.map { book -> book.uuid })
    }

    private fun linkedLibraryAndServerBooks() = GetBooksUseCase(
        repositoryProvider = provider(
            repository("local", ServerType.Local, "b1" to "Dune"),
            repository("st-1", ServerType.Storyteller, "s1" to "Dune", transform = { book ->
                book.copy(
                    authors = listOf("Frank Herbert"),
                    series = listOf(ServerBookSeries("series-1", "Dune", 1f)),
                )
            }),
        ),
        bookLinksRepository = linksRepository(listOf(testLink(
            "link-1", "library:b1", "storyteller:s1",
        ))),
    )
}

internal fun linksRepository(links: List<BookLink>): BookLinksRepository =
    object : BookLinksRepository {
        override fun observeLinks(): Flow<List<BookLink>> = flowOf(links)

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

internal fun provider(vararg repositories: ServerBooksRepository): AuthenticatedRepositoryProvider =
    object : AuthenticatedRepositoryProvider {
        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
            flowOf(repositories.toList())

        override suspend fun getBooksRepositories() = repositories.toList()

        override suspend fun getBooksRepository(serverId: String) =
            repositories.firstOrNull { repository -> repository.serverId == serverId }

        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null

        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
            flowOf(emptyList())

        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }

internal fun repository(
    serverId: String,
    serverType: ServerType,
    vararg books: Pair<String, String>,
    transform: (ServerBook) -> ServerBook = { book -> book },
): ServerBooksRepository = object : ServerBooksRepository {
    override val serverId: String = serverId

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(
        Ok(
            books.map { (uuid, title) ->
                transform(ServerBook(
                    uuid = uuid,
                    serverId = serverId,
                    title = title,
                    description = null,
                    coverUrl = null,
                    authors = emptyList(),
                    narrators = emptyList(),
                    series = emptyList(),
                    tags = emptyList(),
                    hasEbook = true,
                    hasAudiobook = false,
                    hasReadaloud = false,
                    isLocal = serverType == ServerType.Local,
                    serverType = serverType,
                ))
            },
        ),
    )

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = error("not used")

    override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = Ok(emptyList())
}
