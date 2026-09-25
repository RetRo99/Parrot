package com.retro99.server.local

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.LocalBookFileUsageCoordinator
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalLibraryOperationAdapterTest {
    @Test
    fun importedReplicaCannotBeRemovedWhileAnyTransferIsPending() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook())
        val files = FakeFileImportManager()
        val transfers = FakeBookFileTransferManager(
            listOf(transfer(state = "pending", direction = "upload")),
        )
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            transfers,
            LocalBookFileUsageCoordinator(),
        )
        val target = target()

        // When
        val availability = classUnderTest.availability(target).single()
        val result = classUnderTest.execute(request(target))

        // Then
        assertFalse(availability.isAvailable)
        assertTrue(availability.reason.orEmpty().contains("transfer"))
        assertTrue(files.deletedPaths.isEmpty())
        assertTrue(source.deletedUuids.isEmpty())
        assertFalse(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun importedReplicaRemovalDeletesOnlyTheExactMediaFileAndKeepsCoverFiles() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook())
        val files = FakeFileImportManager()
        val transfers = FakeBookFileTransferManager(emptyList())
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            transfers,
            LocalBookFileUsageCoordinator(),
        )
        val target = target()

        // When
        val result = classUnderTest.execute(request(target))

        // Then
        assertEquals(
            listOf("import-1" to target.storageRef.value),
            files.deletedPaths,
        )
        assertEquals(listOf("import-1"), source.deletedUuids)
        assertTrue(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun sharedReplicaDetachKeepsBytesAndRetryDoesNotDeleteAssociationTwice() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook())
        val files = FakeFileImportManager()
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            FakeBookFileTransferManager(emptyList()),
            LocalBookFileUsageCoordinator(),
        )
        val request = request(target().copy(deleteStorageBytes = false))

        // When
        val firstResult = classUnderTest.execute(request)
        val retryResult = classUnderTest.execute(request)

        // Then
        assertTrue(firstResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)
        assertTrue(retryResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)
        assertTrue(files.deleteAttempts.isEmpty())
        assertEquals(listOf("import-1"), source.deletedUuids)
    }

    @Test
    fun finalOwnerRemovalRetryAcceptsAnAlreadyDeletedAssociation() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(book = null)
        val files = FakeFileImportManager(existingPaths = emptySet())
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            FakeBookFileTransferManager(emptyList()),
            LocalBookFileUsageCoordinator(),
        )
        val request = request(target())

        // When
        val firstResult = classUnderTest.execute(request)
        val retryResult = classUnderTest.execute(request)

        // Then
        assertTrue(firstResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)
        assertTrue(retryResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)
        assertEquals(
            listOf(
                "import-1" to "/imports/import-1_ebook.epub",
                "import-1" to "/imports/import-1_ebook.epub",
            ),
            files.deleteAttempts,
        )
        assertTrue(files.deletedPaths.isEmpty())
        assertTrue(source.deletedUuids.isEmpty())
    }

    @Test
    fun restoredCloudReplicaCanBeRemovedWithoutRemovingItsRemoteTransfer() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook(origin = "cloud_download"))
        val files = FakeFileImportManager()
        val transfers = FakeBookFileTransferManager(emptyList())
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            transfers,
            LocalBookFileUsageCoordinator(),
        )
        val target = target()

        // When
        val availability = classUnderTest.availability(target).single()
        val result = classUnderTest.execute(request(target))

        // Then
        assertTrue(availability.isAvailable)
        assertEquals(
            listOf("import-1" to target.storageRef.value),
            files.deletedPaths,
        )
        assertEquals(listOf("import-1"), source.deletedUuids)
        assertTrue(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun missingBytesAfterInterruptedRemovalCanStillRetireLocalAssociation() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook())
        val files = FakeFileImportManager(existingPaths = emptySet())
        val transfers = FakeBookFileTransferManager(emptyList())
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            transfers,
            LocalBookFileUsageCoordinator(),
        )
        val target = target()

        // When
        val result = classUnderTest.execute(request(target))

        // Then
        assertEquals(listOf("import-1" to target.storageRef.value), files.deleteAttempts)
        assertTrue(files.deletedPaths.isEmpty())
        assertEquals(listOf("import-1"), source.deletedUuids)
        assertTrue(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun unsupportedLocalReplicaOriginCannotBeRemoved() = runBlocking {
        // Given
        val source = FakeImportedBooksSource(localBook(origin = "external_cache"))
        val files = FakeFileImportManager()
        val transfers = FakeBookFileTransferManager(emptyList())
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            files,
            transfers,
            LocalBookFileUsageCoordinator(),
        )
        val target = target()

        // When
        val availability = classUnderTest.availability(target).single()
        val result = classUnderTest.execute(request(target))

        // Then
        assertFalse(availability.isAvailable)
        assertTrue(files.deletedPaths.isEmpty())
        assertTrue(source.deletedUuids.isEmpty())
        assertFalse(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun activeFileUseBlocksRemovalUntilReleased() = runBlocking {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val source = FakeImportedBooksSource(localBook())
        val files = FakeFileImportManager()
        val transfers = FakeBookFileTransferManager(emptyList())
        val classUnderTest = LocalLibraryOperationAdapter(source, files, transfers, coordinator)
        val target = target()
        val useLease = coordinator.acquireUse(target.storageRef.value)

        // When
        val availability = classUnderTest.availability(target).single()
        val blockedResult = classUnderTest.execute(request(target))

        // Then
        assertFalse(availability.isAvailable)
        assertTrue(availability.reason.orEmpty().contains("in use"))
        assertTrue(files.deletedPaths.isEmpty())
        assertTrue(source.deletedUuids.isEmpty())
        assertFalse(blockedResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)

        useLease.release()
        val acceptedResult = classUnderTest.execute(request(target))
        assertTrue(acceptedResult is com.retro99.server.api.library.LibraryOperationResult.Accepted)
    }

    @Test
    fun removalLeaseCoversAssociationCleanup() = runBlocking {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val target = target()
        var removalStayedExclusive = false
        val source = FakeImportedBooksSource(localBook()) {
            val unexpectedLease = coordinator.tryAcquireRemoval(target.storageRef.value)
            removalStayedExclusive = unexpectedLease == null
            unexpectedLease?.release()
        }
        val classUnderTest = LocalLibraryOperationAdapter(
            source,
            FakeFileImportManager(),
            FakeBookFileTransferManager(emptyList()),
            coordinator,
        )

        // When
        val result = classUnderTest.execute(request(target))

        // Then
        assertTrue(result is com.retro99.server.api.library.LibraryOperationResult.Accepted)
        assertTrue(removalStayedExclusive)
        assertNotNull(coordinator.tryAcquireRemoval(target.storageRef.value)).release()
    }

    private fun target() = LibraryOperationTarget.DeviceReplica(
        source = SourceBookRef(
            key = SourceBookKey(
                profileId = com.retro99.server.api.library.LibraryProfileId("profile-a"),
                adapterId = LibraryAdapterId("local"),
                accountIdentity = SourceAccountIdentity.Portable(
                    LocalContentIdentity.BACKEND_ID,
                    LocalContentIdentity.ACCOUNT_ID,
                ),
                nativeBookId = LocalContentIdentity.nativeBookId(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            ),
            connectionId = SourceConnectionId(LOCAL_SERVER_ID),
        ),
        resource = SourceResourceRef(
            book = SourceBookKey(
                profileId = com.retro99.server.api.library.LibraryProfileId("profile-a"),
                adapterId = LibraryAdapterId("local"),
                accountIdentity = SourceAccountIdentity.Portable(
                    LocalContentIdentity.BACKEND_ID,
                    LocalContentIdentity.ACCOUNT_ID,
                ),
                nativeBookId = LocalContentIdentity.nativeBookId(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            ),
            nativeResourceId = "import-1",
        ),
        replicaId = StorageReplicaId("replica-1"),
        storageRef = DeviceStorageRef("/imports/import-1_ebook.epub"),
    )

    private fun request(target: LibraryOperationTarget.DeviceReplica) =
        LibraryOperationRequest(
            operationId = "remove-1",
            operation = LibraryOperation.RemoveDeviceReplica,
            assetId = MediaAssetId("asset-1"),
            target = target,
        )

    private fun localBook(origin: String = "import") = BookDomainModel.LocalBook(
        uuid = "import-1",
        serverId = LOCAL_SERVER_ID,
        serverType = ServerType.Local,
        title = "Imported book",
        description = null,
        coverUrl = "file:///covers/import-1.png",
        author = "Author",
        filePath = "/imports/import-1_ebook.epub",
        fileSize = 1024L,
        contentHash = "content-hash",
        contentHashAlgorithm = "sha-256-v1",
        importedAt = "2026-09-24T00:00:00Z",
        lastOpenedAt = null,
        bookType = BookType.EBOOK,
        publicationDate = null,
        origin = origin,
    )

    private fun transfer(state: String, direction: String) = BookFileTransfer(
        transferId = "transfer-1",
        serverId = PARROT_CLOUD_SERVER_ID,
        libraryBookId = "sha-256-v1:content-hash",
        direction = direction,
        mediaType = "ebook",
        state = state,
        bytesTransferred = 10,
        totalBytes = 20,
        attemptCount = 1,
        lastError = null,
    )

    private class FakeImportedBooksSource(
        private var book: BookDomainModel.LocalBook?,
        private val onDeleteAssociation: suspend () -> Unit = {},
    ) : ImportedBooksLocalSource {
        val deletedUuids = mutableListOf<String>()

        override suspend fun saveImportedBook(book: BookDomainModel.LocalBook) = Ok(Unit)

        override fun observeAllImportedBooks(): Flow<List<BookDomainModel.LocalBook>> =
            flowOf(listOfNotNull(book))

        override suspend fun getImportedBookByUuid(uuid: String) = book?.takeIf { it.uuid == uuid }

        override suspend fun getImportedBookByContentHash(contentHash: String) =
            book?.takeIf { it.contentHash == contentHash }

        override suspend fun deleteImportedBook(uuid: String): CompletableResult {
            if (book?.uuid == uuid) {
                onDeleteAssociation()
                deletedUuids += uuid
                book = null
            }
            return Ok(Unit)
        }

        override suspend fun updateLastOpenedAt(uuid: String) = Ok(Unit)
    }

    private class FakeFileImportManager(
        private val existingPaths: Set<String> = setOf("/imports/import-1_ebook.epub"),
    ) : FileImportManager {
        val deleteAttempts = mutableListOf<Pair<String, String>>()
        val deletedPaths = mutableListOf<Pair<String, String>>()

        override suspend fun importEpubFile(platformFile: PlatformFile) = error("Unused")

        override fun getImportedBookPath(uuid: String): String? = null

        override fun deleteImportedBookFiles(uuid: String): Boolean = error("Unused")

        override fun deleteImportedBookReplicaFile(
            uuid: String,
            storageReference: String,
        ): Boolean {
            deleteAttempts += uuid to storageReference
            if (storageReference in existingPaths) {
                deletedPaths += uuid to storageReference
            }
            return true
        }

        override suspend fun deleteLocalBook(uuid: String) = Ok(Unit)
    }

    private class FakeBookFileTransferManager(
        private val transfers: List<BookFileTransfer>,
    ) : BookFileTransferManager {
        override fun supportsUpload(serverId: String) = false
        override fun supportsDownload(serverId: String) = false
        override fun supportsDeletion(serverId: String) = false
        override suspend fun enqueueUpload(
            serverId: String,
            localBookUuid: String,
            rightsAttestation: com.retro99.books.domain.UploadRightsAttestation,
        ) = error("Unused")
        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: com.retro99.books.domain.UploadRightsAttestation,
        ) = BackupAllResult(0, 0)
        override suspend fun enqueueDownload(serverId: String, libraryBookId: String, mediaType: String) =
            error("Unused")
        override suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun deleteRemoteBackup(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ) = Unit
        override suspend fun cancelDownloadsForCloudFile(cloudBookFileId: String) = Unit
        override suspend fun invalidateCloudFile(cloudBookFileId: String) = Unit
        override suspend fun cancel(serverId: String, libraryBookId: String) = Unit
        override suspend fun cancelTransfer(transferId: String) = Unit
        override suspend fun retry(transferId: String) = Unit
        override fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> =
            flowOf(transfers)
    }
}
