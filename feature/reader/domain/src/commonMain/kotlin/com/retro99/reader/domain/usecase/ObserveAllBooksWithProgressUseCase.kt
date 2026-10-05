package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.base.result.AppResult
import com.retro99.base.result.AppError
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.model.SeriesSourceFailure
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.model.links.groupLinkedBooks
import com.retro99.books.domain.model.toBookDomainModel
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.progress.RemotePositionStore
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Instant

data class BooksProgressSnapshot(
    val books: List<BookWithProgressDomainModel>,
    val failures: List<SeriesSourceFailure>,
)

/**
 * Combined use case that observes all books with their progress information.
 * 
 * Combines book fetching from all servers with reactive progress observation.
 * Returns a list of [BookWithProgressDomainModel] that updates when:
 * - Books are added/removed from servers
 * - Local reading progress changes
 * - Books are linked or unlinked
 *
 * The copies of a linked book are one entry: its primary copy, with that copy's progress,
 * carrying the other copies as `linkedCopies`.
 *
 * Remote progress comes from the shared [RemotePositionStore], the same value book details
 * and the continue-reading card read, so no surface can show a staler number than another.
 */
@Factory
class ObserveAllBooksWithProgressUseCase(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val positionLocalSource: ServerPositionLocalSource,
    @Provided private val bookLinksRepository: BookLinksRepository,
    @Provided private val remotePositionStore: RemotePositionStore,
) {
    private val lastKnownBooks = mutableMapOf<String, List<ServerBook>>()
    /**
     * Observes all books with their progress information.
     *
     * @return Flow of books with progress, sorted by title
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(groupLinked: Boolean = true): Flow<AppResult<List<BookWithProgressDomainModel>>> =
        observeSnapshot(groupLinked).map { Ok(it.books) }

    fun observeSnapshot(groupLinked: Boolean = true): Flow<BooksProgressSnapshot> {
        return repositoryProvider.observeBooksRepositories()
            .flatMapLatest { repositories ->
                val bookFlows = repositories.map { repo ->
                    repo.getBooks().catch { emit(Err(AppError.UnknownError(it))) }
                }

                if (bookFlows.isEmpty()) {
                    flowOf(BooksProgressSnapshot(emptyList(), emptyList()))
                } else {
                    // Combine all book flows with position observation and the shared remote
                    // progress store
                    combine(
                        combine(bookFlows) { results ->
                            val failures = mutableListOf<SeriesSourceFailure>()
                            results.forEachIndexed { index, result ->
                                result.onFailure { failures += SeriesSourceFailure(repositories[index].serverId, it) }
                            }
                            val books = results.flatMapIndexed { index, result ->
                                val serverId = repositories[index].serverId
                                val keepLastKnown = !groupLinked && serverId != LOCAL_SERVER_ID && serverId != PARROT_CLOUD_SERVER_ID
                                if (keepLastKnown) result.onSuccess { lastKnownBooks[serverId] = it }
                                result.getOrElse { if (keepLastKnown) lastKnownBooks[serverId].orEmpty() else emptyList() }
                            }
                            books to failures.toList()
                        },
                        positionLocalSource.observeAllPositions(),
                        remotePositionStore.observe(),
                        bookLinksRepository.observeLinks(),
                        observeCurrentlyReading(),
                    ) { books, localPositions, remotePositions, links, currentlyReading ->
                        BooksProgressSnapshot(
                            buildBooksWithProgress(books.first, localPositions, remotePositions, if (groupLinked) links else emptyList(), currentlyReading)
                                .getOrElse { emptyList() },
                            books.second,
                        )
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
                    remotePositionStore.refresh(serverId = book.serverId, bookUuid = book.uuid)
                }
            }.awaitAll()
        }
    }

    /**
     * Clears the shared remote progress store.
     */
    fun clearCache() {
        remotePositionStore.clear()
    }

    private fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> =
        readerSettingsRepository.observeCurrentlyReading()
            .onStart { emit(readerSettingsRepository.getCurrentlyReading()) }
            .distinctUntilChanged()

    private suspend fun buildBooksWithProgress(
        books: List<ServerBook>,
        localPositions: List<ServerPosition>,
        remotePositions: Map<String, ServerPosition>,
        links: List<BookLink>,
        currentlyReading: CurrentlyReadingDomainModel?,
    ): AppResult<List<BookWithProgressDomainModel>> {
        // Every book's position is keyed by its uuid; for your library that is the book id.
        val localPositionMap = localPositions.associateBy { position -> position.bookUuid }

        val booksWithProgress = books.map { serverBook ->
            val bookUuid = serverBook.uuid
            val remoteProgression = remotePositions[bookUuid]?.totalProgression

            val progressInfo = createProgressInfo(
                serverBook = serverBook,
                localPosition = localPositionMap[bookUuid],
                remoteProgression = remoteProgression,
            )

            BookWithProgressDomainModel(
                book = serverBook.toBookDomainModel(),
                progressInfo = progressInfo,
                lastOpenedMillis = serverBook.toBookDomainModel().lastOpenedMillis(localPositionMap[bookUuid]),
                currentlyReading = serverBook.serverId == currentlyReading?.serverId && serverBook.uuid == currentlyReading.bookUuid,
            )
        }

        val grouped = groupLinkedCopies(
            entries = booksWithProgress,
            links = links,
            localPositions = localPositionMap,
            currentlyReading = currentlyReading,
        )
        return Ok(grouped.sortedBy { book -> book.book.title.lowercase() })
    }

    /** One entry per linked book: its primary copy, keeping that copy's progress. */
    private fun groupLinkedCopies(
        entries: List<BookWithProgressDomainModel>,
        links: List<BookLink>,
        localPositions: Map<String, ServerPosition>,
        currentlyReading: CurrentlyReadingDomainModel?,
    ): List<BookWithProgressDomainModel> {
        if (links.isEmpty()) return entries
        val progressByBook = entries.associate { entry ->
            (entry.book.serverId to entry.book.uuid) to entry.progressInfo
        }
        val lastOpened = entries.mapNotNull { entry ->
            val openedAt = entry.book.lastOpenedMillis(localPositions[entry.book.uuid])
                ?: return@mapNotNull null
            entry.book.copyKey() to openedAt
        }.toMap()
        val reading = entries.firstOrNull { entry ->
            entry.book.serverId == currentlyReading?.serverId &&
                entry.book.uuid == currentlyReading.bookUuid
        }?.book?.copyKey()
        val downloaded = entries
            .filter { entry -> entry.progressInfo?.hasAnyCached == true }
            .mapTo(mutableSetOf()) { entry -> entry.book.copyKey() }
        return groupLinkedBooks(
            books = entries.map { entry -> entry.book },
            links = links,
            lastOpened = lastOpened,
            currentlyReading = reading,
            downloaded = downloaded,
        ).map { book ->
            BookWithProgressDomainModel(
                book = book,
                progressInfo = progressByBook[book.serverId to book.uuid],
            )
        }
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

/** When a copy was last opened on this device: its saved position, or the library's record. */
internal fun BookDomainModel.lastOpenedMillis(position: ServerPosition?): Long? {
    val positionMillis = position?.timestamp
        ?: position?.updatedAt?.let { value -> Instant.parseOrNull(value)?.toEpochMilliseconds() }
    val libraryMillis = (this as? BookDomainModel.LibraryBook)?.lastOpenedAt
        ?.let { value -> Instant.parseOrNull(value)?.toEpochMilliseconds() }
    return listOfNotNull(positionMillis, libraryMillis).maxOrNull()
}
