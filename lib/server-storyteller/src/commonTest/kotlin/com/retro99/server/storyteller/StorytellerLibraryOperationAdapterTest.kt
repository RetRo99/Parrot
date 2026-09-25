package com.retro99.server.storyteller

import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.BookDownloadManager
import com.retro99.reader.domain.model.DownloadKey
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorytellerLibraryOperationAdapterTest {
    @Test
    fun supportedEbookTargetUsesSourceScopedReaderCache() {
        // Given
        val manager = RecordingDownloadManager()
        val source = source()
        val resource = SourceResourceRef(
            book = source.key,
            nativeResourceId = "/api/v2/books/book-1/files?format=ebook",
        )
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("remote-file"),
            remoteRef = RemoteResourceRef(resource.nativeResourceId),
            mediaType = BookType.EBOOK.value,
        )
        val adapter = StorytellerLibraryOperationAdapter(DownloadMediaUseCase(manager))

        // When
        val supportsReaderCache = adapter.supportsReaderCache(target)
        val readerCacheBookId = adapter.readerCacheBookId(target)

        // Then
        assertTrue(supportsReaderCache)
        assertNull(readerCacheBookId)
    }

    @Test
    fun downloadsExactStorytellerRouteIntoTheSourceScopedCache() = runTest {
        // Given
        val manager = RecordingDownloadManager()
        val source = source()
        val resource = SourceResourceRef(
            book = source.key,
            nativeResourceId = "/api/v2/books/book-1/files?format=ebook",
        )
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("remote-file"),
            remoteRef = RemoteResourceRef(resource.nativeResourceId),
        )
        val request = LibraryOperationRequest(
            operationId = "operation-1",
            operation = LibraryOperation.Download,
            assetId = MediaAssetId("asset-1"),
            target = target,
            downloadCacheId = "library-cache-1",
            displayTitle = "Exact title",
        )
        val adapter = StorytellerLibraryOperationAdapter(DownloadMediaUseCase(manager))

        // When
        val supportsReaderCache = adapter.supportsReaderCache(target)
        val result = adapter.execute(request)

        // Then
        assertTrue(supportsReaderCache)
        assertEquals(LibraryOperationResult.Accepted("operation-1"), result)
        assertEquals(
            listOf(
                StartedDownload(
                    bookUuid = "library-cache-1",
                    bookType = BookType.EBOOK,
                    filePath = resource.nativeResourceId,
                    bookTitle = "Exact title",
                    serverId = "storyteller-connection",
                ),
            ),
            manager.downloads,
        )
    }

    @Test
    fun rejectsAResourceWhoseRouteDoesNotMatchItsBookAndMediaType() = runTest {
        // Given
        val manager = RecordingDownloadManager()
        val source = source()
        val wrongResource = SourceResourceRef(source.key, "/api/v2/books/other/files?format=ebook")
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = wrongResource,
            replicaId = StorageReplicaId("remote-file"),
            remoteRef = RemoteResourceRef(wrongResource.nativeResourceId),
        )
        val adapter = StorytellerLibraryOperationAdapter(DownloadMediaUseCase(manager))

        // When
        val supportsReaderCache = adapter.supportsReaderCache(target)
        val result = adapter.execute(
            LibraryOperationRequest(
                operationId = "operation-1",
                operation = LibraryOperation.Download,
                assetId = MediaAssetId("asset-1"),
                target = target,
            ),
        )

        // Then
        assertFalse(supportsReaderCache)
        assertEquals(
            LibraryOperationResult.Rejected(
                "The selected Storyteller file resource is no longer available",
            ),
            result,
        )
        assertEquals(emptyList(), manager.downloads)
    }

    private fun source(): SourceBookRef {
        val connectionId = SourceConnectionId("storyteller-connection")
        return SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile-1"),
                adapterId = LibraryAdapterId("storyteller"),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("book-1"),
            ),
            connectionId = connectionId,
        )
    }
}

private data class StartedDownload(
    val bookUuid: String,
    val bookType: BookType,
    val filePath: String,
    val bookTitle: String,
    val serverId: String,
)

private class RecordingDownloadManager : BookDownloadManager {
    val downloads = mutableListOf<StartedDownload>()

    override fun observeDownloadState(bookUuid: String, bookType: BookType): Flow<DownloadState> =
        emptyFlow()

    override fun observeAllDownloads(): Flow<Map<DownloadKey, DownloadState>> = emptyFlow()

    override suspend fun startDownload(
        bookUuid: String,
        bookType: BookType,
        filePath: String,
        bookTitle: String,
        serverId: String,
    ) {
        downloads += StartedDownload(bookUuid, bookType, filePath, bookTitle, serverId)
    }

    override suspend fun cancelDownload(bookUuid: String, bookType: BookType) = Unit

    override fun clearError(bookUuid: String, bookType: BookType) = Unit

    override fun getDownloadState(bookUuid: String, bookType: BookType): DownloadState =
        DownloadState.Idle

    override suspend fun deleteCache(bookUuid: String, bookType: BookType): Boolean = true
}
