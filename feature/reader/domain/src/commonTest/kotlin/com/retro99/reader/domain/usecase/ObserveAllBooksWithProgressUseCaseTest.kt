package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.github.michaelbull.result.get
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.progress.RemotePositionStore
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveAllBooksWithProgressUseCaseTest {

    @Test
    fun `raw snapshot keeps linked versions separate for membership aggregation`() = runTest {
        val books = useCase(emptyList(), emptySet()).observeSnapshot(groupLinked = false).first().books
        assertEquals(3, books.size)
        assertEquals(setOf("s1", "s2", "b1"), books.map { it.book.uuid }.toSet())
    }

    @Test
    fun `failed source is reported while other books remain available`() = runTest {
        val provider = FakeProvider(listOf(storytellerBook, libraryBook), failedServerId = "st-1")
        val useCase = ObserveAllBooksWithProgressUseCase(provider, FakeReaderSettings(emptySet(), null),
            FakePositions(emptyList()), FakeLinks(emptyList()), RemotePositionStore(provider))
        val snapshot = useCase.observeSnapshot().first()
        assertEquals(listOf("b1"), snapshot.books.map { it.book.uuid })
        assertEquals(listOf("st-1"), snapshot.failures.map { it.serverId })
    }

    @Test
    fun `refresh failure retains last known books without disguising the failure`() = runTest {
        val provider = FakeProvider(listOf(storytellerBook, libraryBook))
        val useCase = ObserveAllBooksWithProgressUseCase(provider, FakeReaderSettings(emptySet(), null),
            FakePositions(emptyList()), FakeLinks(emptyList()), RemotePositionStore(provider))
        assertEquals(2, useCase.observeSnapshot(groupLinked = false).first().books.size)
        provider.failedServerId = "st-1"
        val stale = useCase.observeSnapshot(groupLinked = false).first()
        assertEquals(2, stale.books.size)
        assertEquals(listOf("st-1"), stale.failures.map { it.serverId })
    }

    private val storytellerBook = serverBook("s1", "st-1", ServerType.Storyteller)
    private val libraryBook = serverBook("b1", LOCAL_SERVER_ID, ServerType.Local).copy(
        isLocal = true,
        mediaResources = listOf(MediaResource(mediaType = "ebook", localPath = "/library/b1.epub")),
    )
    private val otherBook = serverBook("s2", "st-1", ServerType.Storyteller)
    private val link = BookLink(
        linkId = "link-1",
        members = setOf(
            requireNotNull(CopyKey.parse("storyteller:s1")),
            requireNotNull(CopyKey.parse("library:b1")),
        ),
    )

    @Test
    fun `linked copies become one entry that opens the copy being read`() = runTest {
        // Given
        val classUnderTest = useCase(
            positions = listOf(position("s1", 0.4), position("b1", 0.1)),
            cachedBookUuids = setOf("s1"),
            currentlyReading = CurrentlyReadingDomainModel(
                serverId = "st-1",
                bookUuid = "s1",
                bookType = BookType.EBOOK,
                bookTitle = "Book s1",
                coverUrl = null,
                totalProgression = 0.4,
            ),
        )

        // When
        val books = classUnderTest().first().get().orEmpty()

        // Then
        assertEquals(listOf("s1", "s2"), books.map { entry -> entry.book.uuid })
        val linked = books.first()
        assertEquals(0.4, linked.progressInfo?.localProgression)
        assertEquals(
            listOf("b1" to true),
            linked.book.linkedCopies.map { copy -> copy.uuid to copy.isDownloaded },
        )
    }

    @Test
    fun `the primary copy is the one opened most recently on this device`() = runTest {
        // Given
        val classUnderTest = useCase(
            positions = listOf(
                position("s1", 0.4, timestamp = 1_000L),
                position("b1", 0.1, timestamp = 2_000L),
            ),
            cachedBookUuids = setOf("s1"),
        )

        // When
        val books = classUnderTest().first().get().orEmpty()

        // Then
        val linked: BookWithProgressDomainModel = books.first { entry -> entry.book.uuid == "b1" }
        assertEquals(0.1, linked.progressInfo?.localProgression)
        assertEquals(
            listOf("s1" to true),
            linked.book.linkedCopies.map { copy -> copy.uuid to copy.isDownloaded },
        )
        assertEquals(2, books.size)
    }

    private fun useCase(
        positions: List<ServerPosition>,
        cachedBookUuids: Set<String>,
        currentlyReading: CurrentlyReadingDomainModel? = null,
    ): ObserveAllBooksWithProgressUseCase {
        val provider = FakeProvider(listOf(storytellerBook, otherBook, libraryBook))
        return ObserveAllBooksWithProgressUseCase(
            repositoryProvider = provider,
            readerSettingsRepository = FakeReaderSettings(cachedBookUuids, currentlyReading),
            positionLocalSource = FakePositions(positions),
            bookLinksRepository = FakeLinks(listOf(link)),
            remotePositionStore = RemotePositionStore(provider),
        )
    }

    private fun serverBook(uuid: String, serverId: String, serverType: ServerType) = ServerBook(
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
    )

    private fun position(bookUuid: String, progression: Double, timestamp: Long? = null) =
        ServerPosition(
            bookUuid = bookUuid,
            serverId = "any",
            timestamp = timestamp,
            createdAt = null,
            updatedAt = null,
            locatorHref = null,
            locatorType = null,
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = null,
            chapterIndex = null,
            progression = progression,
            totalChapters = null,
            totalDurationMs = null,
            totalProgression = progression,
            position = null,
        )

    private class FakeLinks(private val links: List<BookLink>) : BookLinksRepository {
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

    private class FakePositions(
        private val positions: List<ServerPosition>,
    ) : ServerPositionLocalSource {
        override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> =
            Ok(positions.firstOrNull { position -> position.bookUuid == bookUuid })
        override suspend fun savePosition(position: ServerPosition): CompletableResult = Ok(Unit)
        override suspend fun savePositionWithSync(
            position: ServerPosition,
            remoteAccountId: String,
        ): CompletableResult = Ok(Unit)
        override suspend fun getAllPositions(): AppResult<List<ServerPosition>> = Ok(positions)
        override suspend fun deletePosition(bookUuid: String): CompletableResult = Ok(Unit)
        override fun observePosition(bookUuid: String): Flow<ServerPosition?> = emptyFlow()
        override fun observeAllPositions(): Flow<List<ServerPosition>> = flowOf(positions)
    }

    private class FakeProvider(books: List<ServerBook>, var failedServerId: String? = null) : AuthenticatedRepositoryProvider {
        private val repositories = books.groupBy { book -> book.serverId }.map { (id, list) ->
            object : ServerBooksRepository {
                override val serverId = id
                override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(
                    if (id == failedServerId) Err(AppError.ApiError(503)) else Ok(list))
                override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = emptyFlow()
                override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)
                override suspend fun searchBooks(query: String) = Ok(list)
            }
        }

        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
            flowOf(repositories)
        override suspend fun getBooksRepositories() = repositories
        override suspend fun getBooksRepository(serverId: String) =
            repositories.firstOrNull { repository -> repository.serverId == serverId }
        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = emptyFlow()
        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }

    private class FakeReaderSettings(
        private val cachedBookUuids: Set<String>,
        private val currentlyReading: CurrentlyReadingDomainModel?,
    ) : ReaderSettingsRepository {
        override suspend fun prepareEbook(
            bookUuid: String,
            ebookFilePath: String,
            bookType: BookType,
        ): AppResult<String> = error("not used")
        override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> = emptyFlow()
        override suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel) = Ok(Unit)
        override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = emptyFlow()
        override suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>) = Ok(Unit)
        override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean =
            bookUuid in cachedBookUuids && bookType == BookType.EBOOK
        override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType) = false
        override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = currentlyReading
        override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> =
            flowOf(currentlyReading)
        override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
        override fun clearCurrentlyReading() = Unit
        override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> =
            Ok(emptyList())
    }
}
