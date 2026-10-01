package com.retro99.server.parrotcloud

import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParrotCloudBookFileChangeApplierTest {
    @Test
    fun availableFileEventMapsServerStateToTheLocalMirror() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("available"))

        assertEquals(1, cloudFiles.upserts.size)
        val file = cloudFiles.upserts.single()
        assertEquals(BOOK_ID, file.libraryBookId)
        assertEquals("cloud-file", file.cloudBookFileId)
        assertEquals("ebook", file.mediaType)
        assertEquals("extras/cover.epub", file.relativePath)
        assertEquals("cover.epub", file.fileName)
        assertEquals("available", file.status)
        assertEquals(321L, file.sizeBytes)
        assertEquals("content-hash", file.contentHash)
        assertEquals("sha-256-v1", file.contentHashAlgorithm)
        assertEquals(8L, file.remoteRevision)
        assertTrue(manager.invalidated.isEmpty())
        assertTrue(cloudFiles.deleted.isEmpty())
    }

    @Test
    fun deletingEventInvalidatesTheCloudReplicaAndStoresDeletingState() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("deleting"))

        assertEquals(listOf("cloud-file"), manager.invalidated)
        assertEquals("deleting", cloudFiles.upserts.single().status)
        assertTrue(cloudFiles.deleted.isEmpty())
    }

    @Test
    fun removedEventInvalidatesReplicaAndRemovesOnlyItsMirrorEntry() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("removed"))

        assertEquals(listOf("cloud-file"), manager.invalidated)
        assertTrue(cloudFiles.upserts.isEmpty())
        assertEquals(listOf(Triple(BOOK_ID, "ebook", "extras/cover.epub")), cloudFiles.deleted)
    }

    private fun applier(
        cloudFiles: RecordingCloudFilesDatabase,
        manager: RecordingTransferManager,
    ): ParrotCloudBookFileChangeApplier = ParrotCloudBookFileChangeApplier(
        cloudFilesDatabase = cloudFiles,
        libraryBooksDatabase = ParrotTestLibraryBooksDatabase(parrotTestBook(BOOK_ID)),
        bookFileTransferManager = manager,
    )

    private fun payload(status: String) = Json.parseToJsonElement(
        """
        {
          "cloud_book_id":"$BOOK_ID",
          "cloud_book_file_id":"cloud-file",
          "media_type":"ebook",
          "relative_path":"extras/cover.epub",
          "file_name":"cover.epub",
          "status":"$status",
          "size_bytes":321,
          "content_hash":"content-hash",
          "content_hash_algorithm":"sha-256-v1",
          "remote_revision":8
        }
        """.trimIndent(),
    ).jsonObject

    private class RecordingCloudFilesDatabase : CloudFilesDatabase {
        val upserts = mutableListOf<CloudBookFileEntity>()
        val deleted = mutableListOf<Triple<String, String, String>>()

        override suspend fun upsertFileState(file: CloudBookFileEntity) {
            upserts += file
        }
        override suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity> = emptyList()
        override suspend fun getFileStateById(cloudBookFileId: String): CloudBookFileEntity? = null
        override suspend fun findFileStateByHash(algorithm: String, hash: String): CloudBookFileEntity? =
            null
        override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = flowOf(upserts.toList())
        override suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String) {
            deleted += Triple(libraryBookId, mediaType, relativePath)
        }
        override suspend fun insertTransfer(transfer: CloudFileTransferEntity) = Unit
        override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? = null
        override suspend fun updateTransfer(transfer: CloudFileTransferEntity) = Unit
        override suspend fun deleteTransfer(transferId: String) = Unit
        override suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity> = emptyList()
        override suspend fun getTransfersForCloudFile(cloudBookFileId: String): List<CloudFileTransferEntity> = emptyList()
        override fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override suspend fun clearAllData() = Unit
    }

    private class RecordingTransferManager : BookFileTransferManager {
        val invalidated = mutableListOf<String>()
        override fun supportsUpload(serverId: String) = false
        override fun supportsDownload(serverId: String) = false
        override fun supportsDeletion(serverId: String) = false
        override suspend fun enqueueUpload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            rightsAttestation: UploadRightsAttestation,
        ): String = error("unused")
        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: UploadRightsAttestation,
        ): BackupAllResult = error("unused")
        override suspend fun enqueueDownload(serverId: String, libraryBookId: String, mediaType: String): String = error("unused")
        override suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun deleteRemoteBackup(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun invalidateCloudFile(cloudBookFileId: String) {
            invalidated += cloudBookFileId
        }
        override suspend fun cancel(serverId: String, libraryBookId: String) = Unit
        override suspend fun cancelTransfer(transferId: String) = Unit
        override suspend fun retry(transferId: String) = Unit
        override fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> = emptyFlow()
    }

    private companion object {
        const val BOOK_ID = "44444444-4444-4444-8444-444444444444"
    }
}
