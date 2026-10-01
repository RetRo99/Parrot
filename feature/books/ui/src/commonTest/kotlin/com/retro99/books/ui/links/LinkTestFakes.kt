package com.retro99.books.ui.links

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal class FakeBookLinksRepository(
    initialLinks: List<BookLink> = emptyList(),
    initialDecisions: Map<String, LinkDecision> = emptyMap(),
) : BookLinksRepository {
    val links = MutableStateFlow(initialLinks)
    val decisions = MutableStateFlow(initialDecisions)
    val linked = mutableListOf<Pair<CopyKey, CopyKey>>()
    val unlinked = mutableListOf<CopyKey>()
    val decided = mutableListOf<Triple<CopyKey, CopyKey, LinkDecisionType>>()
    var linkError: AppError? = null
    var failingLinks: Set<Pair<CopyKey, CopyKey>> = emptySet()

    override fun observeLinks(): Flow<List<BookLink>> = links

    override fun observeDecisions(): Flow<Map<String, LinkDecision>> = decisions

    override suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink> {
        linked += first to second
        linkError?.let { error -> return Err(error) }
        if ((first to second) in failingLinks) {
            return Err(AppError.NotFoundError("link failed"))
        }
        return Ok(BookLink("link-${linked.size}", setOf(first, second)))
    }

    override suspend fun unlink(copy: CopyKey): CompletableResult {
        unlinked += copy
        return Ok(Unit)
    }

    override suspend fun decide(
        first: CopyKey,
        second: CopyKey,
        decision: LinkDecisionType,
    ): CompletableResult {
        decided += Triple(first, second, decision)
        return Ok(Unit)
    }
}

internal fun fakeRepositoryProvider(
    vararg books: ServerBook,
): AuthenticatedRepositoryProvider = object : AuthenticatedRepositoryProvider {
    private val repositories = books.groupBy { book -> book.serverId }.map { (serverId, list) ->
        object : ServerBooksRepository {
            override val serverId: String = serverId

            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(list))

            override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = error("not used")

            override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)

            override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> =
                Ok(emptyList())
        }
    }

    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
        flowOf(repositories)

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = repositories

    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
        repositories.firstOrNull { repository -> repository.serverId == serverId }

    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null

    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
        flowOf(emptyList())

    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

internal fun fakeServerBook(
    uuid: String,
    serverType: ServerType,
    serverId: String = serverType.identifier,
    title: String = "Book $uuid",
    author: String? = null,
    isbn: String? = null,
) = ServerBook(
    uuid = uuid,
    serverId = serverId,
    title = title,
    description = null,
    coverUrl = null,
    authors = listOfNotNull(author),
    narrators = emptyList(),
    series = emptyList(),
    tags = emptyList(),
    hasEbook = true,
    hasAudiobook = false,
    hasReadaloud = false,
    isLocal = serverType == ServerType.Local,
    serverType = serverType,
    isbn = isbn,
)
