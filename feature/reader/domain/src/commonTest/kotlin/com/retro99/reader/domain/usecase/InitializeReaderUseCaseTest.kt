package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InitializeReaderUseCaseTest {

    private val readerSettings = RecordingReaderSettingsRepository()

    @Test
    fun `a library book opens its device copy without the reader cache`() = runTest {
        // Given
        val classUnderTest = useCase(
            libraryBook(MediaResource(mediaType = "ebook", localPath = DEVICE_PATH)),
        )

        // When
        val result = classUnderTest(serverId = "local", bookUuid = BOOK_ID, bookType = BookType.EBOOK)

        // Then
        val data = requireNotNull(result.get())
        assertEquals(DEVICE_PATH, data.localEbookPath)
        assertEquals(BOOK_ID, data.bookUuid)
        assertTrue(readerSettings.cacheCalls.isEmpty(), "reader cache was used: ${readerSettings.cacheCalls}")
    }

    @Test
    fun `a library book without a device copy cannot be opened`() = runTest {
        // Given
        val classUnderTest = useCase(
            libraryBook(
                MediaResource(
                    mediaType = "ebook",
                    remoteAvailability = RemoteFileAvailability.Available,
                ),
            ),
        )

        // When
        val result = classUnderTest(serverId = "local", bookUuid = BOOK_ID, bookType = BookType.EBOOK)

        // Then
        assertIs<AppError.NotFoundError>(result.getError())
        assertTrue(readerSettings.cacheCalls.isEmpty(), "reader cache was used: ${readerSettings.cacheCalls}")
    }

    private fun useCase(book: ServerBook): InitializeReaderUseCase {
        val provider = SingleBookRepositoryProvider(book)
        return InitializeReaderUseCase(
            getBookByUuidUseCase = GetBookByUuidUseCase(provider),
            prepareEbookUseCase = PrepareEbookUseCase(readerSettings),
            getReaderSettingsUseCase = GetReaderSettingsUseCase(readerSettings),
            getReadingProgressWithConflictUseCase = GetReadingProgressWithConflictUseCase(
                repositoryProvider = provider,
                positionDatabase = UnusedPositionDatabase,
            ),
        )
    }

    private fun libraryBook(resource: MediaResource) = ServerBook(
        uuid = BOOK_ID,
        serverId = "local",
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
        isLocal = true,
        serverType = ServerType.Local,
        libraryBookId = BOOK_ID,
        mediaResources = listOf(resource),
    )

    private class SingleBookRepositoryProvider(
        private val book: ServerBook,
    ) : AuthenticatedRepositoryProvider {
        private val repository = object : ServerBooksRepository {
            override val serverId = book.serverId
            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(listOf(book)))
            override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = flowOf(Ok(book))
            override suspend fun saveBook(book: ServerBook): CompletableResult = Ok(Unit)
            override suspend fun searchBooks(query: String) = Ok(listOf(book))
        }

        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
            flowOf(listOf(repository))
        override suspend fun getBooksRepositories() = listOf(repository)
        override suspend fun getBooksRepository(serverId: String) = repository
        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = emptyFlow()
        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }

    private class RecordingReaderSettingsRepository : ReaderSettingsRepository {
        val cacheCalls = mutableListOf<String>()

        override suspend fun prepareEbook(
            bookUuid: String,
            ebookFilePath: String,
            bookType: BookType,
        ): AppResult<String> {
            cacheCalls += "prepareEbook"
            return Err(AppError.UnknownError(Throwable("reader cache")))
        }

        override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> =
            flowOf(ReaderSettingsDomainModel())
        override suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel) = Ok(Unit)
        override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = emptyFlow()
        override suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>) = Ok(Unit)

        override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
            cacheCalls += "isEbookCached"
            return false
        }

        override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
            cacheCalls += "deleteEbookCache"
            return false
        }

        override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = null
        override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> = emptyFlow()
        override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
        override fun clearCurrentlyReading() = Unit
        override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> =
            Ok(emptyList())
    }

    private object UnusedPositionDatabase : PositionDatabase {
        override suspend fun upsertPosition(position: PositionEntity) = unused()
        override suspend fun upsertPositionWithMutation(
            position: PositionEntity,
            mutation: SyncOutboxEntry,
        ) = unused()
        override suspend fun updateRemoteRevision(
            bookUuid: String,
            remoteRevision: Long,
            expectedLocalGeneration: Long?,
        ) = unused()
        override suspend fun upsertRemotePosition(position: PositionEntity) = unused()
        override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? =
            unused()
        override suspend fun deleteRemotePosition(bookUuid: String) = unused()
        override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = unused()
        override suspend fun getAllPositions(): List<PositionEntity> = unused()
        override suspend fun deletePosition(bookUuid: String) = unused()
        override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = unused()
        override fun observeAllPositions(): Flow<List<PositionEntity>> = unused()
        override suspend fun clearAllData() = unused()

        private fun unused(): Nothing = error("PositionDatabase is not used")
    }

    private companion object {
        const val BOOK_ID = "66666666-6666-4666-8666-666666666666"
        const val DEVICE_PATH = "/library/book_ebook.epub"
    }
}
