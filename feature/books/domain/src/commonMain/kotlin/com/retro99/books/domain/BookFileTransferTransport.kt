package com.retro99.books.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Backend-neutral upload contract. The engine owns persistence and retry policy. */
interface BookFileTransferTransport {
    val serverId: String
    val capabilities: TransferTransportCapabilities

    suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult

    suspend fun upload(
        request: BookFileUploadRequest,
        reservation: UploadReservation,
        resumeUrl: String?,
        resumeOffset: Long,
        onSession: suspend (url: String, expiresAt: String?) -> Unit,
        // Called before hashing the reconciled prefix when the server offset moves backwards.
        onHashReset: suspend () -> Unit,
        onChunkHashed: suspend (bytes: ByteArray) -> Unit,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
    ): UploadSessionResult

    suspend fun finalize(reservation: UploadReservation, request: BookFileUploadRequest): CloudBookFileRecord

    suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?)
}

/** The transport owns short-lived grants; signed URLs are never exposed to persistent state. */
interface BookFileDownloadTransport {
    val serverId: String
    val supportsDownload: Boolean

    suspend fun download(
        request: BookFileDownloadRequest,
        resumeOffset: Long,
        onResponseOffset: suspend (offset: Long) -> Unit,
        onChunk: suspend (bytes: ByteArray) -> Unit,
    )
}

/** Deletes a cloud-owned book file and completes its server-side lifecycle. */
interface BookFileDeletionTransport {
    val serverId: String

    suspend fun delete(cloudBookFileId: String)
}

data class BookFileDownloadRequest(
    val transferId: String,
    val serverId: String,
    val libraryBookId: String,
    val cloudBookId: String,
    val cloudBookFileId: String,
    val mediaType: String,
    val fileName: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
)

data class TransferTransportCapabilities(
    val resumeMode: TransferResumeMode,
    val supportsClientSuppliedId: Boolean,
    val supportsReplaceInPlace: Boolean,
    val maxRequestSizeBytes: Long? = null,
    /** Server-controlled rollout gate. False keeps uploads dark even if UI is enabled. */
    val supportsUpload: Boolean = false,
)

enum class TransferResumeMode {
    None,
    ChunkIndexed,
    ByteOffset,
}

data class BookFileUploadRequest(
    val transferId: String,
    val serverId: String,
    val libraryBookId: String,
    val cloudBookId: String,
    val localBookUuid: String,
    val mediaType: String,
    val relativePath: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val rightsAttestation: UploadRightsAttestation,
)

@Serializable
data class UploadRightsAttestation(
    @SerialName("attested_at")
    val attestedAt: String,
    @SerialName("tos_version")
    val tosVersion: String,
    @SerialName("attestation_version")
    val attestationVersion: String,
)

sealed interface UploadReservationResult {
    data class Reserved(val reservation: UploadReservation) : UploadReservationResult
    data class AlreadyAvailable(val file: CloudBookFileRecord) : UploadReservationResult
    data class Rejected(
        val reason: String,
        val usedBytes: Long? = null,
        val quotaBytes: Long? = null,
        val retryAfterMillis: Long? = null,
    ) : UploadReservationResult
}

data class UploadReservation(
    val uploadId: String,
    val cloudBookFileId: String,
    val storagePath: String,
    val uploadEndpoint: String,
)

data class UploadSessionResult(
    val uploadUrl: String,
    val expiresAt: String?,
    val bytesTransferred: Long,
)

data class CloudBookFileRecord(
    val cloudBookId: String,
    val cloudBookFileId: String,
    val mediaType: String,
    val relativePath: String,
    val fileName: String,
    val status: String,
    val sizeBytes: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val remoteRevision: Long,
)

class BookFileTransferRejectedException(
    val reason: String,
    val retryAfterMillis: Long? = null,
) : Exception(reason)

class BookFileTransferSessionExpiredException : Exception("TUS upload session expired")

class BookFileTransferDownloadIncompleteException : Exception("Cloud file download was incomplete")

class BookFileTransferDownloadException : Exception("Cloud file download request failed")

/** Domain facade used by UI and import flows; implementations persist before scheduling work. */
interface BookFileTransferManager {
    fun supportsUpload(serverId: String): Boolean
    fun supportsDownload(serverId: String): Boolean
    fun supportsDeletion(serverId: String): Boolean

    suspend fun enqueueUpload(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String

    suspend fun backupAll(
        serverId: String,
        rightsAttestation: UploadRightsAttestation,
    ): Int

    suspend fun enqueueDownload(serverId: String, libraryBookId: String, mediaType: String): String

    suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String)

    suspend fun deleteRemoteBackup(serverId: String, libraryBookId: String, mediaType: String)

    /** Removes only app-provisioned replicas associated with a deleted cloud file. */
    suspend fun invalidateCloudFile(cloudBookFileId: String)

    suspend fun cancel(serverId: String, libraryBookId: String)

    suspend fun cancelTransfer(transferId: String)

    suspend fun retry(transferId: String)

    fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>>
}

data class BookFileTransfer(
    val transferId: String,
    val serverId: String,
    val libraryBookId: String,
    val direction: String,
    val mediaType: String,
    val state: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val attemptCount: Int,
    val lastError: String?,
)
