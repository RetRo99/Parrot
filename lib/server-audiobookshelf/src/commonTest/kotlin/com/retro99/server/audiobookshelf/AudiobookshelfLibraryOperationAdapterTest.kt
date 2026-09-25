package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.BookDownloadManager
import com.retro99.reader.domain.model.DownloadKey
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.ServerConfig
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
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import com.retro99.server.audiobookshelf.model.AudiobookshelfAudioFileApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfBookMetadataApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.AudiobookshelfMediaApiModel
import io.ktor.http.HeadersBuilder
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import retro99.network.api.QueryParamsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookshelfLibraryOperationAdapterTest {
    @Test
    fun downloadTargetPolicyGroupsEveryAudiobookFileIntoOneBundleTarget() {
        // Given
        val source = source()
        val resources = listOf("audio-inode-1", "audio-inode-2").map { inode ->
            SourceMediaResource(
                reference = SourceResourceRef(source.key, inode),
                mediaType = BookType.AUDIOBOOK.value,
                format = "audio/mpeg",
                availability = SourceResourceAvailability.AvailableRemotely,
                remoteResourceReference = RemoteResourceRef("/api/items/item-1/file/$inode"),
            )
        }
        val adapter = AudiobookshelfLibraryOperationAdapter(
            networkClientProvider = RecordingNetworkClientProvider(item()),
            downloadMediaUseCase = DownloadMediaUseCase(RecordingDownloadManager()),
        )

        // When
        val downloadTargets = adapter.downloadOperationResources(resources)

        // Then
        assertEquals(listOf(resources.first()), downloadTargets)
        assertEquals(
            listOf("audio-inode-1", "audio-inode-2"),
            resources.map { resource -> resource.reference.nativeResourceId },
        )
    }

    @Test
    fun supportedEbookTargetUsesSourceScopedReaderCache() {
        // Given
        val manager = RecordingDownloadManager()
        val source = source()
        val resource = SourceResourceRef(source.key, "ebook-inode-1")
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("ebook-inode-1"),
            remoteRef = RemoteResourceRef("/api/items/item-1/file/ebook-inode-1"),
            mediaType = BookType.EBOOK.value,
        )
        val adapter = AudiobookshelfLibraryOperationAdapter(
            networkClientProvider = RecordingNetworkClientProvider(item()),
            downloadMediaUseCase = DownloadMediaUseCase(manager),
        )

        // When
        val supportsReaderCache = adapter.supportsReaderCache(target)
        val readerCacheBookId = adapter.readerCacheBookId(target)

        // Then
        assertTrue(supportsReaderCache)
        assertNull(readerCacheBookId)
    }

    @Test
    fun downloadsTheSelectedAudiobookBundleAndKeepsItsSourceCacheIdentity() = runTest {
        // Given
        val item = item()
        val provider = RecordingNetworkClientProvider(item)
        val manager = RecordingDownloadManager()
        val source = source()
        val resource = SourceResourceRef(source.key, "audio-inode-2")
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("audio-inode-2"),
            remoteRef = RemoteResourceRef("/api/items/item-1/file/audio-inode-2"),
        )
        val adapter = AudiobookshelfLibraryOperationAdapter(
            networkClientProvider = provider,
            downloadMediaUseCase = DownloadMediaUseCase(manager),
        )

        // When
        val supportsReaderCache = adapter.supportsReaderCache(target)
        val result = adapter.execute(
            LibraryOperationRequest(
                operationId = "operation-1",
                operation = LibraryOperation.Download,
                assetId = MediaAssetId("asset-1"),
                target = target,
                downloadCacheId = "library-cache-1",
                displayTitle = "Audiobook title",
            ),
        )

        // Then
        assertTrue(supportsReaderCache)
        assertEquals(LibraryOperationResult.Accepted("operation-1"), result)
        assertEquals(listOf("/api/items/item-1"), provider.client.paths)
        assertEquals(
            listOf(
                StartedDownload(
                    bookUuid = "library-cache-1",
                    bookType = BookType.AUDIOBOOK,
                    filePath = "/api/items/item-1/file/audio-inode-1" +
                        "|/api/items/item-1/file/audio-inode-2",
                    bookTitle = "Audiobook title",
                    serverId = "audiobookshelf-connection",
                ),
            ),
            manager.downloads,
        )
    }

    @Test
    fun rejectsAStaleSelectedInodeWithoutStartingADownload() = runTest {
        // Given
        val provider = RecordingNetworkClientProvider(item())
        val manager = RecordingDownloadManager()
        val source = source()
        val resource = SourceResourceRef(source.key, "removed-inode")
        val target = LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId("removed-inode"),
            remoteRef = RemoteResourceRef("/api/items/item-1/file/removed-inode"),
        )
        val adapter = AudiobookshelfLibraryOperationAdapter(
            networkClientProvider = provider,
            downloadMediaUseCase = DownloadMediaUseCase(manager),
        )

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
        // This reports the cache used by valid downloads, not whether the remote inode is current.
        assertTrue(supportsReaderCache)
        assertEquals(
            LibraryOperationResult.Rejected(
                "The selected Audiobookshelf file is no longer available",
            ),
            result,
        )
        assertEquals(listOf("/api/items/item-1"), provider.client.paths)
        assertEquals(emptyList(), manager.downloads)
    }

    private fun source(): SourceBookRef {
        val connectionId = SourceConnectionId("audiobookshelf-connection")
        return SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile-1"),
                adapterId = LibraryAdapterId("audiobookshelf"),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("item-1"),
            ),
            connectionId = connectionId,
        )
    }

    private fun item() = AudiobookshelfLibraryItemApiModel(
        id = "item-1",
        media = AudiobookshelfMediaApiModel(
            metadata = AudiobookshelfBookMetadataApiModel(title = "Current title"),
            numAudioFiles = 2,
            audioFiles = listOf(
                AudiobookshelfAudioFileApiModel(
                    index = 1,
                    ino = "audio-inode-1",
                    mimeType = "audio/mpeg",
                    metadata = null,
                ),
                AudiobookshelfAudioFileApiModel(
                    index = 2,
                    ino = "audio-inode-2",
                    mimeType = "audio/mpeg",
                    metadata = null,
                ),
            ),
        ),
    )
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

private class RecordingNetworkClientProvider(
    item: AudiobookshelfLibraryItemApiModel,
) : ServerNetworkClientProvider {
    val client = RecordingServerNetworkClient(item)

    override fun create(serverConfig: ServerConfig): ServerNetworkClient = client

    override suspend fun createForServerId(serverId: String): ServerNetworkClient? =
        client.takeIf { serverId == it.serverId }
}

private class RecordingServerNetworkClient(
    private val item: AudiobookshelfLibraryItemApiModel,
) : ServerNetworkClient {
    override val serverId: String = "audiobookshelf-connection"
    override val baseUrl: String = "https://books.example"
    val paths = mutableListOf<String>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> getWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> {
        paths += path
        return Ok(item as T)
    }

    override suspend fun <T> postWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("Unused")

    override suspend fun <T> patchWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("Unused")

    override suspend fun <T> deleteWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("Unused")

    override suspend fun <T> postFormWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        formData: Map<String, String>,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<T> = error("Unused")

    override suspend fun downloadFile(
        path: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<ByteArray> = error("Unused")

    override suspend fun downloadFileToPath(
        path: String,
        destinationPath: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Unused")

    override suspend fun downloadFileToPathWithProgress(
        path: String,
        destinationPath: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = error("Unused")

    override fun close() = Unit
}
