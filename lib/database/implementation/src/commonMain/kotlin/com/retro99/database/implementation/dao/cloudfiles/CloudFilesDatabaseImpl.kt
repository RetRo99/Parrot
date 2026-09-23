package com.retro99.database.implementation.dao.cloudfiles

import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import kotlinx.coroutines.flow.Flow

internal class CloudFilesDatabaseImpl(
    private val dao: CloudFilesSqlDelightDao,
) : CloudFilesDatabase {
    override suspend fun upsertFileState(file: CloudBookFileEntity) = dao.upsertFileState(file)

    override suspend fun getFileStates(libraryBookId: String) = dao.getFileStates(libraryBookId)

    override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = dao.observeFileStates()

    override suspend fun deleteFileState(
        libraryBookId: String,
        mediaType: String,
        relativePath: String,
    ) = dao.deleteFileState(libraryBookId, mediaType, relativePath)

    override suspend fun insertTransfer(transfer: CloudFileTransferEntity) = dao.saveTransfer(transfer)

    override suspend fun getTransfer(transferId: String) = dao.getTransfer(transferId)

    override suspend fun updateTransfer(transfer: CloudFileTransferEntity) = dao.saveTransfer(transfer)

    override suspend fun getTransfers(serverId: String, states: List<String>) =
        dao.getTransfers(serverId, states)

    override fun observeTransfers(
        serverId: String,
        libraryBookId: String,
    ): Flow<List<CloudFileTransferEntity>> = dao.observeTransfers(serverId, libraryBookId)

    override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> =
        dao.observeActiveTransfers()

    override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = dao.observeAllTransfers()

    override suspend fun clearAllData() {
        dao.deleteAllFileStates()
        dao.deleteAllTransfers()
    }
}
