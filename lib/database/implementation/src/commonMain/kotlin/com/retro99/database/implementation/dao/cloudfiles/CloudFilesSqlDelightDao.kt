package com.retro99.database.implementation.dao.cloudfiles

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.implementation.Cloud_book_file_state
import com.retro99.database.implementation.Cloud_file_transfers
import com.retro99.database.implementation.DatabaseManager
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class CloudFilesSqlDelightDao(
    private val databaseManager: DatabaseManager,
    private val userRegistry: UserRegistry,
) {
    suspend fun upsertFileState(file: CloudBookFileEntity) = withContext(Dispatchers.IO) {
        databaseManager.getDatabase().cloudBookFileStateQueries.upsertCloudBookFileState(
            library_book_id = file.libraryBookId,
            cloud_book_file_id = file.cloudBookFileId,
            media_type = file.mediaType,
            relative_path = file.relativePath,
            file_name = file.fileName,
            status = file.status,
            size_bytes = file.sizeBytes,
            content_hash = file.contentHash,
            content_hash_algorithm = file.contentHashAlgorithm,
            remote_revision = file.remoteRevision,
            updated_at = file.updatedAt,
        )
    }

    suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity> =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().cloudBookFileStateQueries
                .getCloudBookFileStates(libraryBookId)
                .executeAsList()
                .map(Cloud_book_file_state::toEntity)
        }

    suspend fun getFileStateById(cloudBookFileId: String): CloudBookFileEntity? =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().cloudBookFileStateQueries
                .getCloudBookFileStateById(cloudBookFileId)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun findFileStateByHash(algorithm: String, hash: String): CloudBookFileEntity? =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().cloudBookFileStateQueries
                .findCloudFileByHash(algorithm, hash)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    fun observeFileStates(): Flow<List<CloudBookFileEntity>> =
        databaseManager.getDatabase().cloudBookFileStateQueries
            .observeAllCloudBookFileStates()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(Cloud_book_file_state::toEntity) }

    suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String) =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().cloudBookFileStateQueries
                .deleteCloudBookFileState(libraryBookId, mediaType, relativePath)
        }

    suspend fun deleteAllFileStates() = withContext(Dispatchers.IO) {
        databaseManager.getDatabase().cloudBookFileStateQueries.deleteAllCloudBookFileStates()
    }

    suspend fun saveTransfer(transfer: CloudFileTransferEntity) = withContext(Dispatchers.IO) {
        val queries = databaseManager.getDatabase().cloudFileTransferQueries
        if (transfer.relativePath.isNotEmpty() || transfer.sourcePath != null) {
            queries.insertCloudFileTransferWithSource(
                transfer_id = transfer.transferId,
                server_id = transfer.serverId,
                direction = transfer.direction,
                library_book_id = transfer.libraryBookId,
                cloud_book_file_id = transfer.cloudBookFileId,
                media_type = transfer.mediaType,
                staging_path = transfer.stagingPath,
                size_bytes = transfer.sizeBytes,
                bytes_transferred = transfer.bytesTransferred,
                content_hash = transfer.contentHash,
                content_hash_algorithm = transfer.contentHashAlgorithm,
                upload_id = transfer.uploadId,
                storage_path = transfer.storagePath,
                tus_upload_url = transfer.tusUploadUrl,
                tus_expires_at = transfer.tusExpiresAt,
                rights_attestation = transfer.rightsAttestation,
                state = transfer.state,
                attempt_count = transfer.attemptCount.toLong(),
                next_attempt_at = transfer.nextAttemptAt,
                last_error = transfer.lastError,
                created_at = transfer.createdAt,
                updated_at = transfer.updatedAt,
                relative_path = transfer.relativePath,
                source_path = transfer.sourcePath,
            )
            return@withContext
        }
        queries.insertCloudFileTransfer(
            transfer_id = transfer.transferId,
            server_id = transfer.serverId,
            direction = transfer.direction,
            library_book_id = transfer.libraryBookId,
            cloud_book_file_id = transfer.cloudBookFileId,
            media_type = transfer.mediaType,
            staging_path = transfer.stagingPath,
            size_bytes = transfer.sizeBytes,
            bytes_transferred = transfer.bytesTransferred,
            content_hash = transfer.contentHash,
            content_hash_algorithm = transfer.contentHashAlgorithm,
            upload_id = transfer.uploadId,
            storage_path = transfer.storagePath,
            tus_upload_url = transfer.tusUploadUrl,
            tus_expires_at = transfer.tusExpiresAt,
            rights_attestation = transfer.rightsAttestation,
            state = transfer.state,
            attempt_count = transfer.attemptCount.toLong(),
            next_attempt_at = transfer.nextAttemptAt,
            last_error = transfer.lastError,
            created_at = transfer.createdAt,
            updated_at = transfer.updatedAt,
        )
    }

    suspend fun deleteAllTransfers() = withContext(Dispatchers.IO) {
        databaseManager.getDatabase().cloudFileTransferQueries.deleteAllCloudFileTransfers()
    }

    suspend fun getTransfer(transferId: String): CloudFileTransferEntity? =
        withContext(Dispatchers.IO) {
            databaseManager.getDatabase().cloudFileTransferQueries
                .getCloudFileTransfer(transferId)
                .executeAsOneOrNull()
                ?.toEntity()
        }

    suspend fun deleteTransfer(transferId: String) = withContext(Dispatchers.IO) {
        databaseManager.getDatabase().cloudFileTransferQueries.deleteCloudFileTransfer(transferId)
    }

    suspend fun getTransfers(serverId: String, states: List<String>): List<CloudFileTransferEntity> =
        withContext(Dispatchers.IO) {
            if (states.isEmpty()) return@withContext emptyList()
            val profileId = userRegistry.getActiveProfileId() ?: return@withContext emptyList()
            databaseManager.withProfile(profileId) {
                databaseManager.getDatabase().cloudFileTransferQueries
                    .getCloudFileTransfersByStates(serverId, states)
                    .executeAsList()
                    .map(Cloud_file_transfers::toEntity)
            }
        }

    suspend fun getTransfersForCloudFile(
        cloudBookFileId: String,
    ): List<CloudFileTransferEntity> = withContext(Dispatchers.IO) {
        databaseManager.getDatabase().cloudFileTransferQueries
            .getCloudFileTransfersForCloudFile(cloudBookFileId)
            .executeAsList()
            .map(Cloud_file_transfers::toEntity)
    }

    fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>> =
        databaseManager.getDatabase().cloudFileTransferQueries
            .observeCloudFileTransfersForBook(serverId, libraryBookId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(Cloud_file_transfers::toEntity) }

    fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> =
        databaseManager.getDatabase().cloudFileTransferQueries
            .observeActiveCloudFileTransfers()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(Cloud_file_transfers::toEntity) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = userRegistry
        .observeActiveProfile()
        .map { profile -> profile?.id }
        .distinctUntilChanged()
        .flatMapLatest { profileId ->
            if (profileId == null) {
                flowOf(emptyList())
            } else {
                flow {
                    val transferRows = databaseManager.withProfile(profileId) {
                        databaseManager.getDatabase().cloudFileTransferQueries
                            .observeAllCloudFileTransfers()
                            .asFlow()
                            .mapToList(Dispatchers.IO)
                    }
                    transferRows.collect { rows ->
                        emit(rows.map(Cloud_file_transfers::toEntity))
                    }
                }
            }
        }
}

private fun Cloud_book_file_state.toEntity() = CloudBookFileEntity(
    libraryBookId = library_book_id,
    cloudBookFileId = cloud_book_file_id,
    mediaType = media_type,
    relativePath = relative_path,
    fileName = file_name,
    status = status,
    sizeBytes = size_bytes,
    contentHash = content_hash,
    contentHashAlgorithm = content_hash_algorithm,
    remoteRevision = remote_revision,
    updatedAt = updated_at,
)

private fun Cloud_file_transfers.toEntity() = CloudFileTransferEntity(
    transferId = transfer_id,
    serverId = server_id,
    direction = direction,
    libraryBookId = library_book_id,
    cloudBookFileId = cloud_book_file_id,
    mediaType = media_type,
    stagingPath = staging_path,
    sizeBytes = size_bytes,
    bytesTransferred = bytes_transferred,
    contentHash = content_hash,
    contentHashAlgorithm = content_hash_algorithm,
    uploadId = upload_id,
    storagePath = storage_path,
    tusUploadUrl = tus_upload_url,
    tusExpiresAt = tus_expires_at,
    rightsAttestation = rights_attestation,
    relativePath = relative_path,
    sourcePath = source_path,
    state = state,
    attemptCount = attempt_count.toInt(),
    nextAttemptAt = next_attempt_at,
    lastError = last_error,
    createdAt = created_at,
    updatedAt = updated_at,
)
