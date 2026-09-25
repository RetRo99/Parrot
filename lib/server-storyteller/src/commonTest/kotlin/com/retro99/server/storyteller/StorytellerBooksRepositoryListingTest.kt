package com.retro99.server.storyteller

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.storyteller.model.StorytellerBookApiModel
import com.retro99.server.storyteller.model.toDomain
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorytellerBooksRepositoryListingTest {
    @Test
    fun cachedEmissionIsPartialAndSuccessfulRemoteFullListingIsComplete() = runTest {
        // Given
        val cachedBook = StorytellerBookApiModel("cached-book", "Cached").toDomain(
            serverId = "storyteller-1",
            baseUrl = null,
        )
        val remoteBook = StorytellerBookApiModel("remote-book", "Remote").toDomain(
            serverId = "storyteller-1",
            baseUrl = "https://storyteller.example",
        )
        val networkClient = RecordingNetworkClient(
            getResult = Ok(listOf(StorytellerBookApiModel("remote-book", "Remote"))),
        )
        val localSource = FakeServerBooksLocalSource(listOf(cachedBook))
        val repository = StorytellerBooksRepository(networkClient, localSource)

        // When
        val listings = repository.getLibraryListing().toList()

        // Then
        assertEquals(2, listings.size)
        assertEquals(
            ServerBookListingCompleteness.Partial,
            assertIsOk(listings[0]).completeness,
        )
        assertEquals(
            ServerBookListingCompleteness.Complete,
            assertIsOk(listings[1]).completeness,
        )
        assertEquals(
            listOf(
                cachedBook.copy(
                    coverUrl = "https://storyteller.example/api/v2/books/cached-book/cover",
                ),
            ),
            assertIsOk(listings[0]).books,
        )
        assertEquals(listOf(remoteBook), assertIsOk(listings[1]).books)
        assertEquals(listOf(remoteBook), localSource.savedBooks)
        assertEquals(listOf("GET:/api/v2/books"), networkClient.calls)
    }

    @Test
    fun cachedBooksStayPartialAndRemoteListingFailureIsEmitted() = runTest {
        // Given
        val cachedBook = StorytellerBookApiModel("cached-book", "Cached").toDomain(
            serverId = "storyteller-1",
            baseUrl = null,
        )
        val networkClient = RecordingNetworkClient(
            getResult = Err(AppError.NetworkError(IllegalStateException("offline"))),
        )
        val repository = StorytellerBooksRepository(
            networkClient,
            FakeServerBooksLocalSource(listOf(cachedBook)),
        )

        // When
        val listings = repository.getLibraryListing().toList()

        // Then
        assertEquals(2, listings.size)
        assertEquals(
            ServerBookListingCompleteness.Partial,
            assertIsOk(listings.first()).completeness,
        )
        assertTrue(listings.last().isErr)
        listings.last().onFailure { error -> assertEquals("offline", error.message) }
    }

    private fun <T> assertIsOk(result: AppResult<T>): T = result.getOrElse { failure ->
        error("Expected success, got $failure")
    }
}

private class FakeServerBooksLocalSource(
    private val cachedBooks: List<ServerBook>?,
) : ServerBooksLocalSource {
    var savedBooks: List<ServerBook> = emptyList()
        private set

    override suspend fun getBooks(serverId: String): AppResult<List<ServerBook>?> =
        Ok(cachedBooks)

    override suspend fun getBook(serverId: String, uuid: String): AppResult<ServerBook?> =
        Ok(cachedBooks?.firstOrNull { book -> book.uuid == uuid })

    override suspend fun saveBooks(
        serverId: String,
        books: List<ServerBook>,
    ): CompletableResult {
        savedBooks = books
        return Ok(Unit)
    }

    override suspend fun saveBook(serverId: String, book: ServerBook): CompletableResult =
        Ok(Unit)

    override suspend fun clearCache(serverId: String): CompletableResult = Ok(Unit)
}
