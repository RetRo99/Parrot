package com.retro99.database.api.cloudfiles

import com.retro99.database.api.DataClearable
import kotlinx.coroutines.flow.Flow

interface CloudFilesDatabase : DataClearable {
    suspend fun upsertFileState(file: CloudBookFileEntity)
    suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity>
    fun observeFileStates(): Flow<List<CloudBookFileEntity>>
    suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String)

    suspend fun insertTransfer(transfer: CloudFileTransferEntity)
    suspend fun getTransfer(transferId: String): CloudFileTransferEntity?
    suspend fun updateTransfer(transfer: CloudFileTransferEntity)
    suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity>
    fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>>
    fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>>
    fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>>
}
