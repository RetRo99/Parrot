package com.retro99.database.api.cloudfiles

import com.retro99.database.api.DataClearable
import kotlinx.coroutines.flow.Flow

interface CloudFilesDatabase : DataClearable {
    suspend fun upsertFileState(file: CloudBookFileEntity)
    suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity>
    fun observeFileStates(): Flow<List<CloudBookFileEntity>>
    suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String)
    suspend fun deleteFileStateByCloudBookFileId(
        libraryBookId: String,
        cloudBookFileId: String,
    ) {
        val matchingFiles = getFileStates(libraryBookId).filter { file ->
            file.cloudBookFileId == cloudBookFileId
        }
        if (matchingFiles.size == 1) {
            val file = matchingFiles.single()
            deleteFileState(libraryBookId, file.mediaType, file.relativePath)
        }
    }

    suspend fun insertTransfer(transfer: CloudFileTransferEntity)
    suspend fun getTransfer(transferId: String): CloudFileTransferEntity?
    suspend fun updateTransfer(transfer: CloudFileTransferEntity)
    suspend fun updateTransferIfState(
        transfer: CloudFileTransferEntity,
        expectedStates: List<String>,
    ): Boolean {
        val current = getTransfer(transfer.transferId) ?: return false
        if (current.state !in expectedStates) return false
        updateTransfer(transfer)
        return true
    }
    suspend fun deleteTransfer(transferId: String)
    suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity>
    suspend fun getTransfersForCloudFile(cloudBookFileId: String): List<CloudFileTransferEntity>

    suspend fun enqueuePendingFileFeedChange(
        cloudBookId: String,
        feedRevision: Long?,
        payloadJson: String,
        receivedAt: String,
    ): PendingCloudFileFeedChange

    suspend fun getPendingFileFeedChanges(
        cloudBookId: String,
    ): List<PendingCloudFileFeedChange>

    suspend fun getPendingFileFeedCloudBookIds(): List<String>

    suspend fun deletePendingFileFeedChange(changeId: Long)

    fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>>
    fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>>
    fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>>
}
