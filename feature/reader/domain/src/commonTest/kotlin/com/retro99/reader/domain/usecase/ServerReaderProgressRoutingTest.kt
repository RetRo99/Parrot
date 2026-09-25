package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressAdapterRegistry
import com.retro99.server.api.library.LibraryProgressValue
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerReaderProgressRoutingTest {
    @Test
    fun mismatchedProgressAdapterCannotReadFromTheSelectedConnection() = runTest {
        // Given
        val repository = FakeServerReaderRepository(
            serverId = CONNECTION_ID,
            libraryAdapterId = STORYTELLER_ADAPTER,
        )
        val positionDatabase = FakePositionDatabase()
        val provider = FakeAuthenticatedRepositoryProvider(repository)
        val classUnderTest = GetReadingProgressWithConflictUseCase(
            provider,
            positionDatabase,
            FakeLibraryProgressAdapterRegistry(),
        )

        // When
        val result = classUnderTest(
            serverId = CONNECTION_ID,
            bookUuid = NATIVE_BOOK_ID,
            expectedAdapterId = AUDIOBOOKSHELF_ADAPTER,
        )

        // Then
        assertTrue(result.isErr)
        assertEquals(0, repository.localReadCount)
        assertEquals(0, repository.remoteReadCount)
        assertEquals(0, positionDatabase.remoteBaselineReadCount)
    }

    @Test
    fun mismatchedProgressAdapterCannotWriteToTheSelectedConnection() = runTest {
        // Given
        val repository = FakeServerReaderRepository(
            serverId = CONNECTION_ID,
            libraryAdapterId = STORYTELLER_ADAPTER,
        )
        val classUnderTest = SaveReadingProgressUseCase(
            FakeAuthenticatedRepositoryProvider(repository),
            FakeLibraryProgressAdapterRegistry(),
        )

        // When
        val result = classUnderTest(
            progress = position(),
            expectedAdapterId = AUDIOBOOKSHELF_ADAPTER,
        )

        // Then
        assertTrue(result.isErr)
        assertEquals(0, repository.writeCount)
    }

    @Test
    fun matchingProgressAdapterUsesItsNativeRepositoryAndProgressId() = runTest {
        // Given
        val repository = FakeServerReaderRepository(
            serverId = CONNECTION_ID,
            libraryAdapterId = STORYTELLER_ADAPTER,
        )
        val positionDatabase = FakePositionDatabase()
        val provider = FakeAuthenticatedRepositoryProvider(repository)
        val registry = FakeLibraryProgressAdapterRegistry()
        val getProgress = GetReadingProgressWithConflictUseCase(
            provider,
            positionDatabase,
            registry,
        )
        val saveProgress = SaveReadingProgressUseCase(provider, registry)

        // When
        val readResult = getProgress(
            serverId = CONNECTION_ID,
            bookUuid = NATIVE_BOOK_ID,
            expectedAdapterId = STORYTELLER_ADAPTER,
        )
        val saveResult = saveProgress(
            progress = position(),
            expectedAdapterId = STORYTELLER_ADAPTER,
        )

        // Then
        assertTrue(readResult.isOk)
        assertTrue(saveResult.isOk)
        assertEquals(1, repository.localReadCount)
        assertEquals(1, repository.remoteReadCount)
        assertEquals(
            listOf(NATIVE_BOOK_ID, NATIVE_BOOK_ID),
            repository.readNativeIds,
        )
        assertEquals(1, repository.writeCount)
        assertEquals(NATIVE_BOOK_ID, repository.writtenNativeId)
        assertEquals(1, positionDatabase.remoteBaselineReadCount)
    }

    @Test
    fun arbitraryAdapterReadsConflictsAndWritesUsingItsSelectedNativeOwner() = runTest {
        // Given
        val adapterId = LibraryAdapterId("test-native-progress")
        val nativeProgressId = "adapter-owned-book-7"
        val owner = progressOwner(adapterId, nativeProgressId)
        val adapter = FakeLibraryProgressAdapter(
            adapterId = adapterId,
            localPosition = serverPosition(nativeProgressId, href = "chapter-4.xhtml"),
            remotePosition = serverPosition(nativeProgressId, href = "chapter-2.xhtml"),
        )
        val registry = FakeLibraryProgressAdapterRegistry(mapOf(adapterId to adapter))
        val provider = FakeAuthenticatedRepositoryProvider(
            FakeServerReaderRepository(CONNECTION_ID, STORYTELLER_ADAPTER),
        )
        val getProgress = GetReadingProgressWithConflictUseCase(
            provider,
            FakePositionDatabase(),
            registry,
        )
        val saveProgress = SaveReadingProgressUseCase(provider, registry)
        val progress = position().copy(
            bookUuid = nativeProgressId,
            locatorHref = "chapter-5.xhtml",
            locatorType = "application/epub+zip",
            locatorTitle = "Chapter Five",
            locatorTarget = 23,
            cssSelector = "#paragraph-23",
            audioTimestampMs = 840L,
            chapterIndex = 5,
            progression = 0.45,
            totalChapters = 12,
            totalDurationMs = 95_000L,
            totalProgression = 0.38,
            position = 23,
        )

        // When
        val readResult = getProgress(
            serverId = CONNECTION_ID,
            bookUuid = nativeProgressId,
            expectedAdapterId = adapterId,
            progressOwner = owner,
        )
        val saveResult = saveProgress(
            progress = progress,
            expectedAdapterId = adapterId,
            progressOwner = owner,
        )

        // Then
        assertTrue(readResult.isOk)
        assertTrue(saveResult.isOk)
        val conflict = readResult.getOrElse { error -> throw AssertionError(error) }
        assertTrue(conflict is ReadingProgressResult.Conflict)
        assertEquals(owner, adapter.lastLocalOwner)
        assertEquals(owner, adapter.lastRemoteOwner)
        assertEquals(owner, adapter.lastWriteOwner)
        val writtenPosition = requireNotNull(adapter.writtenPosition)
        assertEquals(nativeProgressId, writtenPosition.bookUuid)
        assertEquals(CONNECTION_ID, writtenPosition.serverId)
        assertEquals("chapter-5.xhtml", writtenPosition.locatorHref)
        assertEquals("application/epub+zip", writtenPosition.locatorType)
        assertEquals("Chapter Five", writtenPosition.locatorTitle)
        assertEquals(23, writtenPosition.locatorTarget)
        assertEquals("#paragraph-23", writtenPosition.cssSelector)
        assertEquals(840L, writtenPosition.audioTimestampMs)
        assertEquals(5, writtenPosition.chapterIndex)
        assertEquals(0.45, writtenPosition.progression)
        assertEquals(12, writtenPosition.totalChapters)
        assertEquals(95_000L, writtenPosition.totalDurationMs)
        assertEquals(0.38, writtenPosition.totalProgression)
        assertEquals(23, writtenPosition.position)
    }

    @Test
    fun disconnectedSelectedOwnerDoesNotReadAStaleSharedRemoteBaseline() = runTest {
        // Given
        val owner = progressOwner(STORYTELLER_ADAPTER, NATIVE_BOOK_ID)
        val repositoryProvider = FakeAuthenticatedRepositoryProvider(
            FakeServerReaderRepository(CONNECTION_ID, STORYTELLER_ADAPTER),
        )
        val positionDatabase = FakePositionDatabase()
        val adapter = FakeUnavailableLibraryProgressAdapter(STORYTELLER_ADAPTER)
        val classUnderTest = GetReadingProgressWithConflictUseCase(
            repositoryProvider,
            positionDatabase,
            FakeLibraryProgressAdapterRegistry(mapOf(STORYTELLER_ADAPTER to adapter)),
        )

        // When
        val result = classUnderTest(
            serverId = CONNECTION_ID,
            bookUuid = NATIVE_BOOK_ID,
            expectedAdapterId = STORYTELLER_ADAPTER,
            progressOwner = owner,
        )

        // Then
        assertTrue(result.isOk)
        val readingResult = result.getOrElse { error -> throw AssertionError(error) }
        assertEquals(ReadingProgressResult.Resolved(null), readingResult)
        assertEquals(0, positionDatabase.remoteBaselineReadCount)
    }

    private fun position() = PositionDomainModel(
        bookUuid = NATIVE_BOOK_ID,
        serverId = CONNECTION_ID,
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = null,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = 120L,
        chapterIndex = 1,
        progression = null,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = null,
        position = null,
    )

    private fun progressOwner(adapterId: LibraryAdapterId, nativeProgressId: String) =
        ProgressOwnerRef(
            adapterId = adapterId,
            source = SourceBookRef(
                key = SourceBookKey(
                    profileId = com.retro99.server.api.library.LibraryProfileId("profile-1"),
                    adapterId = adapterId,
                    accountIdentity = SourceAccountIdentity.Unresolved(
                        SourceConnectionId(CONNECTION_ID),
                    ),
                    nativeBookId = NativeBookId("source-book-id"),
                ),
                connectionId = SourceConnectionId(CONNECTION_ID),
            ),
            nativeProgressId = nativeProgressId,
        )

    private fun serverPosition(bookUuid: String, href: String) = ServerPosition(
        bookUuid = bookUuid,
        serverId = CONNECTION_ID,
        timestamp = 1L,
        createdAt = "created",
        updatedAt = "updated",
        locatorHref = href,
        locatorType = "application/epub+zip",
        locatorTitle = "Chapter",
        locatorTarget = null,
        audioTimestampMs = null,
        chapterIndex = null,
        progression = 0.2,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = 0.2,
        position = null,
    )

    private class FakeLibraryProgressAdapterRegistry(
        private val adapters: Map<LibraryAdapterId, LibraryProgressAdapter> = emptyMap(),
    ) : LibraryProgressAdapterRegistry {
        override fun adapter(adapterId: LibraryAdapterId): LibraryProgressAdapter? =
            adapters[adapterId]
    }

    private class FakeLibraryProgressAdapter(
        override val adapterId: LibraryAdapterId,
        private val localPosition: ServerPosition,
        private val remotePosition: ServerPosition,
    ) : LibraryProgressAdapter {
        var lastLocalOwner: ProgressOwnerRef? = null
        var lastRemoteOwner: ProgressOwnerRef? = null
        var lastWriteOwner: ProgressOwnerRef? = null
        var writtenPosition: ServerPosition? = null

        override suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue {
            lastLocalOwner = owner
            return LibraryProgressValue.ReaderPosition(localPosition)
        }

        override suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue) {
            lastWriteOwner = owner
            writtenPosition = (value as LibraryProgressValue.ReaderPosition).position
        }

        override suspend fun readRemotePosition(
            owner: ProgressOwnerRef,
        ): AppResult<ServerPosition?> {
            lastRemoteOwner = owner
            return Ok(remotePosition)
        }
    }

    private class FakeUnavailableLibraryProgressAdapter(
        override val adapterId: LibraryAdapterId,
    ) : LibraryProgressAdapter {
        override suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue? = null

        override suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue) = Unit

        override suspend fun validateOwner(owner: ProgressOwnerRef): AppResult<Unit> =
            Err(AppError.NotFoundError("Progress source is disconnected"))
    }

    private class FakeAuthenticatedRepositoryProvider(
        private val readerRepository: ServerReaderRepository,
    ) : AuthenticatedRepositoryProvider {
        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
            flowOf(emptyList())

        override suspend fun getBooksRepositories(): List<ServerBooksRepository> = emptyList()

        override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = null

        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? =
            readerRepository.takeIf { repository -> repository.serverId == serverId }

        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
            flowOf(emptyList())

        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }

    private class FakeServerReaderRepository(
        override val serverId: String,
        override val libraryAdapterId: LibraryAdapterId,
    ) : ServerReaderRepository {
        var localReadCount = 0
        var remoteReadCount = 0
        var writeCount = 0
        var writtenNativeId: String? = null
        val readNativeIds = mutableListOf<String>()

        override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> =
            Ok(null)

        override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
            localReadCount++
            readNativeIds += bookUuid
            return Ok(null)
        }

        override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult =
            Ok(Unit)

        override suspend fun saveLocalPositionWithSync(
            bookUuid: String,
            position: ServerPosition,
        ): CompletableResult {
            writeCount++
            writtenNativeId = bookUuid
            return Ok(Unit)
        }

        override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
            remoteReadCount++
            readNativeIds += bookUuid
            return Ok(null)
        }
    }

    private class FakePositionDatabase : PositionDatabase {
        var remoteBaselineReadCount = 0

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

        override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? {
            remoteBaselineReadCount++
            return null
        }

        override suspend fun deleteRemotePosition(bookUuid: String) = unused()

        override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? = unused()

        override suspend fun getAllPositions(): List<PositionEntity> = unused()

        override suspend fun deletePosition(bookUuid: String) = unused()

        override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = unused()

        override fun observeAllPositions(): Flow<List<PositionEntity>> = unused()

        override suspend fun clearAllData() = unused()

        private fun unused(): Nothing = error("This test must not use unrelated position methods")
    }

    private companion object {
        const val CONNECTION_ID = "source-connection"
        const val NATIVE_BOOK_ID = "native-book-id"
        val STORYTELLER_ADAPTER = LibraryAdapterId("storyteller")
        val AUDIOBOOKSHELF_ADAPTER = LibraryAdapterId("audiobookshelf")
    }
}
