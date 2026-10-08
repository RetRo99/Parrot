package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.fakes.FakeReaderRepository
import com.retro99.reader.domain.fakes.FakeRepositoryProvider
import com.retro99.reader.domain.fakes.serverPosition
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.progress.RemotePositionStore
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The library list row, book details and the continue-reading card must show the same percent
 * for the same book. They all read the shared [RemotePositionStore]; these tests pin that they
 * do, so a book can never show one percent in the list and another on its details screen again.
 */
class BookProgressConsistencyTest {

    @Test
    fun `library list and book details show the same percent for the same book`() = runTest {
        // Given: no position on this device, so the remote value is the one shown
        val reader = FakeReaderRepository(serverId = SERVER_ID).apply {
            remote[BOOK_UUID] = serverPosition(BOOK_UUID, SERVER_ID, totalProgression = 0.79)
        }
        val fixture = fixture(reader)
        fixture.list.fetchRemoteProgress(fixture.list().first().get().orEmpty())
        assertEquals(79, fixture.listPercent())

        // When: the book is opened and the server has moved on since the library loaded
        reader.remote[BOOK_UUID] = serverPosition(BOOK_UUID, SERVER_ID, totalProgression = 0.88)
        val detailPercent = fixture.detailPercent()

        // Then: both surfaces show the same number, read from one source
        assertEquals(88, detailPercent)
        assertEquals(detailPercent, fixture.listPercent())
    }

    @Test
    fun `a failed fetch keeps every surface on the last known percent`() = runTest {
        val reader = FakeReaderRepository(serverId = SERVER_ID).apply {
            remote[BOOK_UUID] = serverPosition(BOOK_UUID, SERVER_ID, totalProgression = 0.88)
        }
        val fixture = fixture(reader)
        fixture.list.fetchRemoteProgress(fixture.list().first().get().orEmpty())
        assertEquals(88, fixture.listPercent())

        // When: the server can't be reached while the book is opened
        reader.remoteFails = true
        val detailPercent = fixture.detailPercent()

        // Then: nothing drops to zero and the surfaces still agree
        assertEquals(88, detailPercent)
        assertEquals(detailPercent, fixture.listPercent())
    }

    private fun fixture(reader: FakeReaderRepository): Fixture {
        val book = serverBook()
        val provider = FakeRepositoryProvider(
            readers = listOf(reader),
            books = listOf(singleBookRepository(book)),
        )
        val positions = FakePositions()
        val store = RemotePositionStore(provider)
        val list = ObserveAllBooksWithProgressUseCase(
            repositoryProvider = provider,
            readerSettingsRepository = FakeReaderSettings(),
            positionLocalSource = positions,
            bookLinksRepository = FakeLinks(),
            remotePositionStore = store,
        )
        val detail = ObserveBookWithProgressUseCase(
            repositoryProvider = provider,
            readerSettingsRepository = FakeReaderSettings(),
            positionLocalSource = positions,
            remotePositionStore = store,
            getReadingProgressWithConflictUseCase = GetReadingProgressWithConflictUseCase(
                provider, com.retro99.reader.domain.fakes.FakePositionDatabase(),
                com.retro99.reader.domain.fakes.FakeSyncOutboxDatabase(),
                com.retro99.sync.domain.ProgressAccountResolver { it },
            ),
        )
        return Fixture(list, detail)
    }

    private inner class Fixture(
        val list: ObserveAllBooksWithProgressUseCase,
        private val detail: ObserveBookWithProgressUseCase,
    ) {
        suspend fun listPercent(): Int? = list().first().get()
            ?.firstOrNull { entry -> entry.book.uuid == BOOK_UUID }
            ?.progressInfo
            ?.progressPercent

        suspend fun detailPercent(): Int? = detail(SERVER_ID, BOOK_UUID).first().get()
            ?.progressInfo
            ?.progressPercent
    }

    private fun serverBook() = ServerBook(
        uuid = BOOK_UUID,
        serverId = SERVER_ID,
        title = "Book",
        description = null,
        coverUrl = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        serverType = ServerType.Storyteller,
    )

    private fun singleBookRepository(book: ServerBook) = object : ServerBooksRepository {
        override val serverId: String = book.serverId
        override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(listOf(book)))
        override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = flowOf(Ok(book))
        override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)
        override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = Ok(listOf(book))
    }

    private class FakePositions : ServerPositionLocalSource {
        override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> = Ok(null)
        override suspend fun savePosition(position: ServerPosition): CompletableResult = Ok(Unit)
        override suspend fun savePositionWithSync(
            position: ServerPosition,
            remoteAccountId: String,
        ): CompletableResult = Ok(Unit)
        override suspend fun getAllPositions(): AppResult<List<ServerPosition>> = Ok(emptyList())
        override suspend fun deletePosition(bookUuid: String): CompletableResult = Ok(Unit)
        override fun observePosition(bookUuid: String): Flow<ServerPosition?> = emptyFlow()
        override fun observeAllPositions(): Flow<List<ServerPosition>> = flowOf(emptyList())
    }

    private class FakeLinks : BookLinksRepository {
        override fun observeLinks(): Flow<List<BookLink>> = flowOf(emptyList())
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

    private class FakeReaderSettings : ReaderSettingsRepository {
        override suspend fun prepareEbook(
            bookUuid: String,
            ebookFilePath: String,
            bookType: BookType,
        ): AppResult<String> = error("not used")
        override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> = emptyFlow()
        override suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel) = Ok(Unit)
        override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = emptyFlow()
        override suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>) = Ok(Unit)
        override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean = false
        override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType) = false
        override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = null
        override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> =
            flowOf(null)
        override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
        override fun clearCurrentlyReading() = Unit
        override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> =
            Ok(emptyList())
    }

    private companion object {
        const val SERVER_ID = "st-1"
        const val BOOK_UUID = "book-1"
    }
}
