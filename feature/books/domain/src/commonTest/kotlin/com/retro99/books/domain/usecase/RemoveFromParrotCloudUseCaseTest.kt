package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.DeviceLibraryRepository
import com.retro99.books.domain.UploadRightsAttestation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoveFromParrotCloudUseCaseTest {

    private val calls = mutableListOf<String>()

    @Test
    fun `device copies become imports before Parrot Cloud files are deleted`() = runTest {
        // Given
        val classUnderTest = RemoveFromParrotCloudUseCase(
            deviceLibraryRepository = RecordingDeviceLibrary(),
            transferManager = RecordingTransferManager(),
        )

        // When
        classUnderTest(libraryBookId = "book", mediaTypes = listOf("ebook", "readaloud"))

        // Then
        assertEquals(
            listOf(
                "keep:book",
                "delete:parrot-cloud:book:ebook",
                "delete:parrot-cloud:book:readaloud",
            ),
            calls,
        )
    }

    @Test
    fun `delete from device goes through the device library`() = runTest {
        // Given
        val classUnderTest = DeleteBookFromDeviceUseCase(RecordingDeviceLibrary())

        // When
        classUnderTest("book")

        // Then
        assertEquals(listOf("deleteFromDevice:book"), calls)
    }

    private inner class RecordingDeviceLibrary : DeviceLibraryRepository {
        override suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult {
            calls += "deleteFromDevice:$libraryBookId"
            return Ok(Unit)
        }

        override suspend fun keepDeviceFilesAsImports(libraryBookId: String): CompletableResult {
            calls += "keep:$libraryBookId"
            return Ok(Unit)
        }
    }

    private inner class RecordingTransferManager : BookFileTransferManager {
        override fun supportsUpload(serverId: String) = true
        override fun supportsDownload(serverId: String) = true
        override fun supportsDeletion(serverId: String) = true
        override suspend fun enqueueUpload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
            rightsAttestation: UploadRightsAttestation,
        ): String = error("Unused")
        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: UploadRightsAttestation,
        ): BackupAllResult = error("Unused")
        override suspend fun enqueueDownload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ): String = error("Unused")
        override suspend fun removeDownload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ) = error("Unused")
        override suspend fun deleteRemoteBackup(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ) {
            calls += "delete:$serverId:$libraryBookId:$mediaType"
        }
        override suspend fun invalidateCloudFile(cloudBookFileId: String) = error("Unused")
        override suspend fun cancel(serverId: String, libraryBookId: String) = error("Unused")
        override suspend fun cancelTransfer(transferId: String) = error("Unused")
        override suspend fun retry(transferId: String) = error("Unused")
        override fun observeForBook(
            serverId: String,
            libraryBookId: String,
        ): Flow<List<BookFileTransfer>> = emptyFlow()
    }
}
