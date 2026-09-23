package com.retro99.server.parrotcloud

import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.cloud.implementation.SupabaseClientProvider
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadRightsAttestation
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** RPC adapter for the server-owned Parrot Cloud file lifecycle. */
@Single
class ParrotCloudBookFileService(
    @Provided private val clientProvider: SupabaseClientProvider,
) {
    suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult {
        val response = clientProvider.client.postgrest
            .rpc(
                "reserve_book_upload",
                buildJsonObject {
                    put("cloud_book_id", request.cloudBookId)
                    put("media_type", request.mediaType)
                    put("relative_path", request.relativePath)
                    put("file_name", request.fileName)
                    put("size_bytes", request.sizeBytes)
                    put("content_hash_algorithm", request.contentHashAlgorithm)
                    put("content_hash", request.contentHash)
                    put(
                        "rights_attestation",
                        request.rightsAttestation.toRpcJson(),
                    )
                },
            )
            .decodeAs<ParrotCloudBookFileRpcResponse>()

        return when (response.status) {
            STATUS_RESERVED -> UploadReservationResult.Reserved(
                UploadReservation(
                    uploadId = requireNotNull(response.uploadId),
                    cloudBookFileId = requireNotNull(response.cloudBookFileId),
                    storagePath = requireNotNull(response.storagePath),
                    uploadEndpoint = requireNotNull(response.uploadUrl),
                ),
            )

            STATUS_ALREADY_AVAILABLE -> UploadReservationResult.AlreadyAvailable(
                CloudBookFileRecord(
                    cloudBookId = request.cloudBookId,
                    cloudBookFileId = requireNotNull(response.cloudBookFileId),
                    mediaType = request.mediaType,
                    relativePath = request.relativePath,
                    fileName = request.fileName,
                    status = "available",
                    sizeBytes = response.sizeBytes ?: request.sizeBytes,
                    contentHash = response.contentHash ?: request.contentHash,
                    contentHashAlgorithm = response.contentHashAlgorithm ?: request.contentHashAlgorithm,
                    remoteRevision = response.revision ?: 0L,
                ),
            )

            STATUS_REJECTED -> UploadReservationResult.Rejected(
                reason = response.reason ?: "upload_rejected",
                usedBytes = response.usedBytes,
                quotaBytes = response.quotaBytes,
            )

            else -> error("Unknown Parrot Cloud upload reservation status: ${response.status}")
        }
    }

    suspend fun finalize(reservation: UploadReservation, request: BookFileUploadRequest): CloudBookFileRecord {
        val response = clientProvider.client.postgrest
            .rpc(
                "finalize_book_upload",
                buildJsonObject {
                    put("upload_id", reservation.uploadId)
                    put("size_bytes", request.sizeBytes)
                    put("content_hash", request.contentHash)
                },
            )
            .decodeAs<ParrotCloudBookFileRpcResponse>()
        if (response.status != STATUS_AVAILABLE) {
            throw BookFileTransferRejectedException(response.reason ?: "finalize_rejected")
        }
        return CloudBookFileRecord(
            cloudBookId = request.cloudBookId,
            cloudBookFileId = response.cloudBookFileId ?: reservation.cloudBookFileId,
            mediaType = request.mediaType,
            relativePath = request.relativePath,
            fileName = request.fileName,
            status = "available",
            sizeBytes = response.sizeBytes ?: request.sizeBytes,
            contentHash = response.contentHash ?: request.contentHash,
            contentHashAlgorithm = response.contentHashAlgorithm ?: request.contentHashAlgorithm,
            remoteRevision = response.revision ?: 0L,
        )
    }

    suspend fun cancel(reservation: UploadReservation) {
        val response = clientProvider.client.postgrest
            .rpc(
                "cancel_book_upload",
                buildJsonObject { put("upload_id", reservation.uploadId) },
            )
            .decodeAs<ParrotCloudBookFileRpcResponse>()
        if (response.status != STATUS_CANCELLED && response.status != STATUS_REJECTED) {
            error("Unknown Parrot Cloud upload cancellation status: ${response.status}")
        }
    }

    private companion object {
        const val STATUS_RESERVED = "reserved"
        const val STATUS_ALREADY_AVAILABLE = "already_available"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_AVAILABLE = "available"
        const val STATUS_CANCELLED = "cancelled"
    }
}

private fun UploadRightsAttestation.toRpcJson() = buildJsonObject {
    put("attested_at", attestedAt)
    put("tos_version", tosVersion)
    put("attestation_version", attestationVersion)
}
