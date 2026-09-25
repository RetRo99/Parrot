package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.audiobookshelf.model.AudiobookshelfAudioFileApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfBookMetadataApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfEbookFileApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemsResponse
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryListApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaApiModel
import com.retro99.server.audiobookshelf.model.toDomain
import com.retro99.server.storyteller.source.ServerBooksLocalSource
import io.ktor.http.HeadersBuilder
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import retro99.network.api.QueryParamsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookshelfBooksRepositoryListingTest {
    @Test
    fun cacheIsPartialAndAllLibrariesAreCompleteOnlyWhenCountsMatch() = runTest {
        // Given
        val cachedBook = AudiobookshelfLibraryItemApiModel(id = "cached-book").toDomain(
            serverId = "audiobookshelf-1",
            baseUrl = null,
        )
        val firstRemoteItem = AudiobookshelfLibraryItemApiModel(id = "book-a")
        val secondRemoteItem = AudiobookshelfLibraryItemApiModel(id = "book-b")
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(
                    AudiobookshelfLibraryListApiModel(
                        libraries = listOf(
                            AudiobookshelfLibraryApiModel("library-a", "A"),
                            AudiobookshelfLibraryApiModel("library-b", "B"),
                        ),
                    ),
                ),
                Ok(AudiobookshelfLibraryItemsResponse(listOf(firstRemoteItem), total = 1)),
                Ok(AudiobookshelfLibraryItemsResponse(listOf(secondRemoteItem), total = 1)),
            ),
        )
        val localSource = FakeServerBooksLocalSource(listOf(cachedBook))
        val repository = AudiobookshelfBooksRepository(
            networkClient,
            localSource,
        )

        // When
        val listings = repository.getLibraryListing().toList()

        // Then
        assertEquals(2, listings.size)
        assertEquals(
            ServerBookListingCompleteness.Partial,
            listings[0].getOrElse { failure -> error("Expected cached listing, got $failure") }
                .completeness,
        )
        val completeListing = listings[1].getOrElse { failure ->
            error("Expected successful listing, got $failure")
        }
        assertEquals(ServerBookListingCompleteness.Complete, completeListing.completeness)
        assertEquals(listOf("book-a", "book-b"), completeListing.books.map { book -> book.uuid })
        assertEquals(
            listOf(
                "GET:/api/libraries",
                "GET:/api/libraries/library-a/items",
                "GET:/api/libraries/library-b/items",
            ),
            networkClient.calls,
        )
        assertEquals(completeListing.books, localSource.savedBooks)
    }

    @Test
    fun listingHydratesEpubFileDetailsForUnifiedSourceResources() = runTest {
        // Given
        val summary = AudiobookshelfLibraryItemApiModel(
            id = "ebook-1",
            media = AudiobookshelfMediaApiModel(
                metadata = AudiobookshelfBookMetadataApiModel(title = "EPUB fixture"),
                ebookFileFormat = "epub",
            ),
        )
        val details = summary.copy(
            media = summary.media?.copy(
                ebookFile = AudiobookshelfEbookFileApiModel(
                    ino = "ebook-inode-1",
                    ebookFormat = "epub",
                    size = 512,
                ),
            ),
        )
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(AudiobookshelfLibraryListApiModel(listOf(library("library-a")))),
                Ok(AudiobookshelfLibraryItemsResponse(listOf(summary), total = 1)),
                Ok(details),
            ),
        )
        val localSource = FakeServerBooksLocalSource(cachedBooks = null)
        val repository = AudiobookshelfBooksRepository(networkClient, localSource)

        // When
        val listing = repository.getLibraryListing().toList().single()
            .getOrElse { failure -> error("Expected hydrated listing, got $failure") }

        // Then
        val book = listing.books.single()
        assertEquals(ServerBookListingCompleteness.Complete, listing.completeness)
        assertEquals("/api/items/ebook-1/file/ebook-inode-1", book.ebookFilepath)
        assertEquals("ebook-inode-1", book.mediaResources.single().nativeResourceId)
        assertEquals(listing.books, localSource.savedBooks)
        assertEquals(
            listOf(
                "GET:/api/libraries",
                "GET:/api/libraries/library-a/items",
                "GET:/api/items/ebook-1",
            ),
            networkClient.calls,
        )
    }

    @Test
    fun listingHydratesEveryAudioFileAndPreservesTrackOrder() = runTest {
        // Given
        val summary = AudiobookshelfLibraryItemApiModel(
            id = "audio-1",
            media = AudiobookshelfMediaApiModel(
                metadata = AudiobookshelfBookMetadataApiModel(title = "Multi-file fixture"),
                numAudioFiles = 2,
            ),
        )
        val details = summary.copy(
            media = summary.media?.copy(
                audioFiles = listOf(
                    AudiobookshelfAudioFileApiModel(
                        index = 2,
                        ino = "audio-inode-2",
                        mimeType = "audio/mpeg",
                    ),
                    AudiobookshelfAudioFileApiModel(
                        index = 1,
                        ino = "audio-inode-1",
                        mimeType = "audio/mpeg",
                    ),
                ),
            ),
        )
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(AudiobookshelfLibraryListApiModel(listOf(library("library-a")))),
                Ok(AudiobookshelfLibraryItemsResponse(listOf(summary), total = 1)),
                Ok(details),
            ),
        )
        val repository = AudiobookshelfBooksRepository(
            networkClient,
            FakeServerBooksLocalSource(cachedBooks = null),
        )

        // When
        val listing = repository.getLibraryListing().toList().single()
            .getOrElse { failure -> error("Expected hydrated listing, got $failure") }

        // Then
        val book = listing.books.single()
        assertEquals(ServerBookListingCompleteness.Complete, listing.completeness)
        assertEquals(
            listOf("audio-inode-1", "audio-inode-2"),
            book.mediaResources.map { resource -> resource.nativeResourceId },
        )
        assertEquals(
            "/api/items/audio-1/file/audio-inode-1|/api/items/audio-1/file/audio-inode-2",
            book.audiobookFilepath,
        )
    }

    @Test
    fun failedItemDetailRequestRetainsSummaryAndMarksListingPartial() = runTest {
        // Given
        val summary = AudiobookshelfLibraryItemApiModel(
            id = "ebook-1",
            media = AudiobookshelfMediaApiModel(
                metadata = AudiobookshelfBookMetadataApiModel(title = "EPUB fixture"),
                ebookFileFormat = "epub",
            ),
        )
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(AudiobookshelfLibraryListApiModel(listOf(library("library-a")))),
                Ok(AudiobookshelfLibraryItemsResponse(listOf(summary), total = 1)),
                Err(AppError.NetworkError(IllegalStateException("item details unavailable"))),
            ),
        )
        val localSource = FakeServerBooksLocalSource(cachedBooks = null)
        val repository = AudiobookshelfBooksRepository(networkClient, localSource)

        // When
        val listing = repository.getLibraryListing().toList().single()
            .getOrElse { failure -> error("Expected partial listing, got $failure") }

        // Then
        val book = listing.books.single()
        assertEquals(ServerBookListingCompleteness.Partial, listing.completeness)
        assertEquals("EPUB fixture", book.title)
        assertTrue(book.hasEbook)
        assertTrue(book.mediaResources.isEmpty())
        assertEquals(listing.books, localSource.savedBooks)
        assertEquals("GET:/api/items/ebook-1", networkClient.calls.last())
    }

    @Test
    fun countMismatchStaysPartialAndLibraryRequestFailureNeverEmitsComplete() = runTest {
        // Given
        val cachedBook = AudiobookshelfLibraryItemApiModel(id = "cached-book").toDomain(
            serverId = "audiobookshelf-1",
            baseUrl = null,
        )
        val partialClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(
                    AudiobookshelfLibraryListApiModel(
                        libraries = listOf(AudiobookshelfLibraryApiModel("library-a", "A")),
                    ),
                ),
                Ok(
                    AudiobookshelfLibraryItemsResponse(
                        results = listOf(AudiobookshelfLibraryItemApiModel(id = "book-a")),
                        total = 2,
                    ),
                ),
            ),
        )

        // When
        val partialListings = AudiobookshelfBooksRepository(
            partialClient,
            FakeServerBooksLocalSource(listOf(cachedBook)),
        ).getLibraryListing().toList()

        // Then
        assertEquals(2, partialListings.size)
        assertEquals(
            ServerBookListingCompleteness.Partial,
            partialListings.last().getOrElse { failure -> error("Unexpected error: $failure") }
                .completeness,
        )

        // Given
        val failedClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(
                    AudiobookshelfLibraryListApiModel(
                        libraries = listOf(AudiobookshelfLibraryApiModel("library-a", "A")),
                    ),
                ),
                Err(AppError.NetworkError(IllegalStateException("library request failed"))),
            ),
        )

        // When
        val failedListings = AudiobookshelfBooksRepository(
            failedClient,
            FakeServerBooksLocalSource(cachedBooks = null),
        ).getLibraryListing().toList()

        // Then
        assertEquals(1, failedListings.size)
        val failure = failedListings.single()
        assertTrue(failure.isErr)
        failure.onFailure { appError ->
            assertEquals("library request failed", appError.message)
        }
    }

    @Test
    fun listingSkipsMissingItemsWithoutMarkingACompleteCatalogPartial() = runTest {
        // Given
        val missingItem = AudiobookshelfLibraryItemApiModel(
            id = "missing-book",
            isMissing = true,
        )
        val currentItem = AudiobookshelfLibraryItemApiModel(id = "current-book")
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(AudiobookshelfLibraryListApiModel(listOf(library("library-a")))),
                Ok(
                    AudiobookshelfLibraryItemsResponse(
                        results = listOf(missingItem, currentItem),
                        total = 2,
                    ),
                ),
            ),
        )
        val localSource = FakeServerBooksLocalSource(cachedBooks = null)
        val repository = AudiobookshelfBooksRepository(networkClient, localSource)

        // When
        val listing = repository.getLibraryListing().toList().single()
            .getOrElse { failure -> error("Expected complete listing, got $failure") }

        // Then
        assertEquals(ServerBookListingCompleteness.Complete, listing.completeness)
        assertEquals(listOf("current-book"), listing.books.map { book -> book.uuid })
        assertEquals(listing.books, localSource.savedBooks)
    }

    @Test
    fun searchSkipsMissingAudiobookshelfItems() = runTest {
        // Given
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(AudiobookshelfLibraryListApiModel(listOf(library("library-a")))),
                Ok(
                    AudiobookshelfLibraryItemsResponse(
                        results = listOf(
                            AudiobookshelfLibraryItemApiModel(
                                id = "missing-book",
                                isMissing = true,
                            ),
                            AudiobookshelfLibraryItemApiModel(id = "current-book"),
                        ),
                        total = 2,
                    ),
                ),
            ),
        )
        val repository = AudiobookshelfBooksRepository(
            networkClient,
            FakeServerBooksLocalSource(cachedBooks = null),
        )

        // When
        val books = repository.searchBooks("fixture")
            .getOrElse { failure -> error("Expected search results, got $failure") }

        // Then
        assertEquals(listOf("current-book"), books.map { book -> book.uuid })
    }

    @Test
    fun cachedBooksStayPartialAndRemoteListingFailureIsEmitted() = runTest {
        // Given
        val cachedBook = AudiobookshelfLibraryItemApiModel(id = "cached-book").toDomain(
            serverId = "audiobookshelf-1",
            baseUrl = null,
        )
        val networkClient = QueueNetworkClient(
            results = mutableListOf(
                Ok(
                    AudiobookshelfLibraryListApiModel(
                        libraries = listOf(AudiobookshelfLibraryApiModel("library-a", "A")),
                    ),
                ),
                Err(AppError.NetworkError(IllegalStateException("library request failed"))),
            ),
        )
        val repository = AudiobookshelfBooksRepository(
            networkClient,
            FakeServerBooksLocalSource(listOf(cachedBook)),
        )

        // When
        val listings = repository.getLibraryListing().toList()

        // Then
        assertEquals(2, listings.size)
        assertEquals(
            ServerBookListingCompleteness.Partial,
            listings.first().getOrElse { failure ->
                error("Expected cached listing, got $failure")
            }.completeness,
        )
        assertTrue(listings.last().isErr)
        listings.last().onFailure { appError ->
            assertEquals("library request failed", appError.message)
        }
    }

    @Test
    fun factoryLeavesIdentityUnresolvedWithoutDurableServerInstanceId() = runTest {
        // Given
        val networkClient = QueueNetworkClient(results = mutableListOf())
        val factory = AudiobookshelfBooksRepositoryFactory(
            networkClientFactory = FakeServerNetworkClientProvider(networkClient),
            localSource = FakeServerBooksLocalSource(cachedBooks = null),
            identityBindingStore = UnpairedIdentityBindingProvider,
        )
        val serverConfig = ServerConfig(
            id = "audiobookshelf-1",
            name = "Audiobookshelf",
            type = ServerType.Audiobookshelf,
            baseUrl = "https://audiobookshelf.example",
            addedAt = 1L,
        )

        // When
        val repository = factory.create(serverConfig)

        // Then
        assertNull(repository.libraryAccountIdentity())
    }

    @Test
    fun accountIdentityRemainsUnresolvedForAnAudiobookshelfConnection() = runTest {
        // Given
        val networkClient = QueueNetworkClient(results = mutableListOf())
        val repository = AudiobookshelfBooksRepository(
            networkClient,
            FakeServerBooksLocalSource(cachedBooks = null),
        )

        // When
        val identity = repository.libraryAccountIdentity()

        // Then
        assertNull(identity)
    }

    private fun library(id: String) = AudiobookshelfLibraryApiModel(id = id, name = id)
}

private object UnpairedIdentityBindingProvider : AudiobookshelfLibraryIdentityBindingProvider {
    override suspend fun currentIdentity(serverId: String): SourceAccountIdentity.Portable? = null

    override fun observePairingStatus(serverId: String): Flow<LibrarySourceIdentityPairingStatus> =
        flowOf(LibrarySourceIdentityPairingStatus.Unpaired)

    override suspend fun createPairingCode(serverId: String): String =
        error("Pairing is not used by repository listing tests")

    override suspend fun importPairingCode(serverId: String, code: String): Unit =
        error("Pairing is not used by repository listing tests")

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

private class QueueNetworkClient(
    private val results: MutableList<AppResult<Any?>>,
) : ServerNetworkClient {
    override val serverId: String = "audiobookshelf-1"
    override val baseUrl: String = "https://audiobookshelf.example"
    val calls = mutableListOf<String>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> getWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> {
        calls += "GET:$path"
        return results.removeAt(0) as AppResult<T>
    }

    override suspend fun <T> postWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("POST is not used by listing tests")

    override suspend fun <T> patchWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("PATCH is not used by listing tests")

    override suspend fun <T> deleteWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("DELETE is not used by listing tests")

    override suspend fun <T> postFormWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        formData: Map<String, String>,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("POST form is not used by listing tests")

    override suspend fun downloadFile(
        path: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<ByteArray> = error("Downloads are not used by listing tests")

    override suspend fun downloadFileToPath(
        path: String,
        destinationPath: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by listing tests")

    override suspend fun downloadFileToPathWithProgress(
        path: String,
        destinationPath: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Downloads are not used by listing tests")

    override fun close() = Unit
}

private class FakeServerNetworkClientProvider(
    private val networkClient: ServerNetworkClient,
) : ServerNetworkClientProvider {
    override fun create(serverConfig: ServerConfig): ServerNetworkClient = networkClient

    override suspend fun createForServerId(serverId: String): ServerNetworkClient? = null
}
