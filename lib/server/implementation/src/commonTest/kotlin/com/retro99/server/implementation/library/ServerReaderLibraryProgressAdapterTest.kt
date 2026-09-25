package com.retro99.server.implementation.library

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryProgressAdapter
import com.retro99.server.api.library.LibraryProgressValue
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerReaderLibraryProgressAdapterTest {
    @Test
    fun localAndCloudOwnersUseNativeIdsAndKeepFullPositionThroughTheRepository() = runTest {
        // Given
        val localRepository = FakeReaderRepository("local-connection", LOCAL_ADAPTER)
        val cloudRepository = FakeReaderRepository("cloud-connection", CLOUD_ADAPTER)
        val registry = DefaultLibraryProgressAdapterRegistry(
            adapters = emptyList(),
            repositoryProvider = FakeRepositoryProvider(
                mapOf(
                    localRepository.serverId to localRepository,
                    cloudRepository.serverId to cloudRepository,
                ),
            ),
        )
        val owners = listOf(
            owner(LOCAL_ADAPTER, "local-connection", "local-native-book"),
            owner(CLOUD_ADAPTER, "cloud-connection", "cloud-native-book"),
        )

        // When
        owners.forEach { owner ->
            val adapter = registry.adapter(owner.adapterId) as LibraryProgressAdapter
            val position = fullPosition(owner.nativeProgressId, owner.source.connectionId!!.value)
            val sourceRepository = if (owner.adapterId == LOCAL_ADAPTER) {
                localRepository
            } else {
                cloudRepository
            }
            sourceRepository.localPosition = position
            sourceRepository.remotePosition = position.copy(locatorHref = "remote.xhtml")

            val local = adapter.readLocalPosition(owner)
            val remote = adapter.readRemotePosition(owner)
            val write = adapter.savePositionWithSync(
                owner,
                position.copy(locatorHref = "saved.xhtml"),
            )

            // Then
            assertTrue(local.isOk)
            assertTrue(remote.isOk)
            assertTrue(write.isOk)
            assertEquals(owner.nativeProgressId, sourceRepository.lastReadLocalId)
            assertEquals(owner.nativeProgressId, sourceRepository.lastReadRemoteId)
            assertEquals(owner.nativeProgressId, sourceRepository.lastWrittenBookUuid)
            assertEquals(1, sourceRepository.syncWriteCount)
            assertEquals("saved.xhtml", sourceRepository.lastWrittenPosition?.locatorHref)
            assertEquals(800L, sourceRepository.lastWrittenPosition?.audioTimestampMs)
            assertEquals("#word-9", sourceRepository.lastWrittenPosition?.cssSelector)

            val readValue = adapter.read(owner) as LibraryProgressValue.ReaderPosition
            assertEquals(position, readValue.position)
        }
    }

    @Test
    fun disconnectedOrMismatchedOwnerCannotUseAnotherRepository() = runTest {
        // Given
        val repository = FakeReaderRepository("connection-one", LOCAL_ADAPTER)
        val registry = DefaultLibraryProgressAdapterRegistry(
            adapters = emptyList(),
            repositoryProvider = FakeRepositoryProvider(mapOf(repository.serverId to repository)),
        )
        val adapter = requireNotNull(registry.adapter(CLOUD_ADAPTER))
        val owner = owner(CLOUD_ADAPTER, "connection-one", "native-cloud-book")

        // When
        val result = adapter.readLocalPosition(owner)

        // Then
        assertTrue(result.isErr)
        assertEquals(null, repository.lastReadLocalId)
    }

    private class FakeRepositoryProvider(
        private val readers: Map<String, ServerReaderRepository>,
    ) : AuthenticatedRepositoryProvider {
        override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> = emptyFlow()

        override suspend fun getBooksRepositories(): List<ServerBooksRepository> = emptyList()

        override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = null

        override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? =
            readers[serverId]

        override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = emptyFlow()

        override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
    }

    private class FakeReaderRepository(
        override val serverId: String,
        override val libraryAdapterId: LibraryAdapterId,
    ) : ServerReaderRepository {
        var localPosition: ServerPosition? = null
        var remotePosition: ServerPosition? = null
        var lastReadLocalId: String? = null
        var lastReadRemoteId: String? = null
        var lastWrittenBookUuid: String? = null
        var lastWrittenPosition: ServerPosition? = null
        var syncWriteCount = 0

        override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> =
            getLocalPosition(bookUuid)

        override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> {
            lastReadLocalId = bookUuid
            return Ok(localPosition)
        }

        override suspend fun saveLocalPosition(
            position: ServerPosition,
        ): CompletableResult = Ok(Unit)

        override suspend fun saveLocalPositionWithSync(
            bookUuid: String,
            position: ServerPosition,
        ): CompletableResult {
            syncWriteCount++
            lastWrittenBookUuid = bookUuid
            lastWrittenPosition = position
            return Ok(Unit)
        }

        override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
            lastReadRemoteId = bookUuid
            return Ok(remotePosition)
        }
    }

    private fun owner(
        adapterId: LibraryAdapterId,
        connectionId: String,
        nativeBookId: String,
    ) = ProgressOwnerRef(
        adapterId = adapterId,
        source = SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile-1"),
                adapterId = adapterId,
                accountIdentity = if (adapterId == CLOUD_ADAPTER) {
                    SourceAccountIdentity.Portable("parrot-cloud", "account-1")
                } else {
                    SourceAccountIdentity.Unresolved(SourceConnectionId(connectionId))
                },
                nativeBookId = NativeBookId(nativeBookId),
            ),
            connectionId = SourceConnectionId(connectionId),
        ),
        nativeProgressId = nativeBookId,
    )

    private fun fullPosition(bookUuid: String, serverId: String) = ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = 1_700_000_000_000L,
        createdAt = "created-at",
        updatedAt = "updated-at",
        locatorHref = "chapter.xhtml#section-2",
        locatorType = "application/epub+zip",
        locatorTitle = "Chapter Two",
        locatorTarget = 9,
        audioTimestampMs = 800L,
        chapterIndex = 2,
        progression = 0.23,
        totalChapters = 10,
        totalDurationMs = 64_000L,
        totalProgression = 0.41,
        position = 9,
        cssSelector = "#word-9",
        remoteRevision = 7L,
    )

    private companion object {
        val LOCAL_ADAPTER = LibraryAdapterId("local")
        val CLOUD_ADAPTER = LibraryAdapterId("parrot-cloud")
    }
}
