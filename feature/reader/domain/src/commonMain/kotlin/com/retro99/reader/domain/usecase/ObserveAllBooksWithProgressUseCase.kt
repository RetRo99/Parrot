package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Combined use case that observes all books with their progress information.
 * 
 * Combines book fetching from all servers with reactive progress observation.
 * Returns a list of [BookWithProgressDomainModel] that updates when:
 * - Books are added/removed from servers
 * - Local reading progress changes
 */
@Factory
class ObserveAllBooksWithProgressUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val positionLocalSource: ServerPositionLocalSource,
) {
    // Cache of remote progressions - fetched once per refresh
    private val remoteProgressionCache = mutableMapOf<String, Double?>()

    // Trigger to force re-evaluation when remote progress is fetched
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Observes all books with their progress information.
     *
     * @return Flow of books with progress, sorted by title
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<List<BookWithProgressDomainModel>>> {
        return repositoryProvider.observeBooksRepositories()
            .flatMapLatest { repositories ->
                val bookFlows = repositories.map { repo ->
                    repo.getBooks()
                }

                if (bookFlows.isEmpty()) {
                    flowOf(Ok(emptyList()))
                } else {
                    // Combine all book flows with position observation and refresh trigger
                    combine(
                        combine(bookFlows) { results ->
                            results.flatMap { result -> result.getOrElse { emptyList() } }
                        },
                        positionLocalSource.observeAllPositions(),
                        refreshTrigger
                    ) { books, localPositions, _ ->
                        buildBooksWithProgress(books, localPositions)
                    }
                }
            }
    }

    /**
     * Fetches remote progress for all books.
     * Should be called once when the book list is loaded or refreshed.
     * Triggers a re-emission of the flow after fetching.
     */
    suspend fun fetchRemoteProgress(books: List<BookWithProgressDomainModel>) {
        if (books.isEmpty()) return

        coroutineScope {
            books.map { bookWithProgress ->
                async {
                    val book = bookWithProgress.book
                    val readerRepo = repositoryProvider.getReaderRepository(book.serverId)
                    val remotePosition = readerRepo?.getRemotePosition(book.uuid)
                        ?.getOrElse { null }
                    remoteProgressionCache[book.uuid] = remotePosition?.totalProgression
                }
            }.awaitAll()
        }

        // Trigger re-emission of the flow with updated remote progress
        refreshTrigger.value++
    }

    /**
     * Clears the remote progress cache.
     */
    fun clearCache() {
        remoteProgressionCache.clear()
    }

    private suspend fun buildBooksWithProgress(
        books: List<ServerBook>,
        localPositions: List<ServerPosition>,
    ): AppResult<List<BookWithProgressDomainModel>> {
        // Every book's position is keyed by its uuid; for your library that is the book id.
        val localPositionMap = localPositions.associateBy { position -> position.bookUuid }

        val booksWithProgress = books.map { serverBook ->
            val bookUuid = serverBook.uuid
            val remoteProgression = remoteProgressionCache[bookUuid]

            val progressInfo = createProgressInfo(
                serverBook = serverBook,
                localPosition = localPositionMap[bookUuid],
                remoteProgression = remoteProgression,
            )

            BookWithProgressDomainModel(
                book = serverBook.toBookDomainModel(),
                progressInfo = progressInfo,
            )
        }

        return Ok(booksWithProgress.sortedBy { book -> book.book.title.lowercase() })
    }

    private suspend fun createProgressInfo(
        serverBook: ServerBook,
        localPosition: ServerPosition?,
        remoteProgression: Double?,
    ): BookProgressInfoDomainModel? {
        val cached = if (serverBook.isLocal) {
            serverBook.deviceCopies()
        } else {
            readerSettingsRepository.cachedTypes(serverBook.uuid)
        }
        val isEbookCached = BookType.EBOOK in cached
        val isAudiobookCached = BookType.AUDIOBOOK in cached
        val isReadaloudCached = BookType.READALOUD in cached

        val localProgression = localPosition?.totalProgression
        val hasLocalProgress = localProgression != null && localProgression > 0.0
        val hasRemoteProgress = remoteProgression != null && remoteProgression > 0.0
        val hasCached = cached.isNotEmpty()

        return if (hasLocalProgress || hasRemoteProgress || hasCached) {
            BookProgressInfoDomainModel(
                bookUuid = serverBook.uuid,
                localProgression = localProgression,
                remoteProgression = remoteProgression,
                isEbookCached = isEbookCached,
                isAudiobookCached = isAudiobookCached,
                isReadaloudCached = isReadaloudCached,
            )
        } else {
            null
        }
    }
}

/** Media types a library book has on this device, from its device files (I6). */
internal fun ServerBook.deviceCopies(): Set<BookType> = mediaResources
    .filter { resource -> resource.localPath != null }
    .mapNotNull { resource -> BookType.entries.firstOrNull { type -> type.value == resource.mediaType } }
    .toSet()

/** Media types of a server book that are in the reader cache. */
internal suspend fun ReaderSettingsRepository.cachedTypes(bookUuid: String): Set<BookType> =
    BookType.entries.filterTo(mutableSetOf()) { type -> isEbookCached(bookUuid, type) }
