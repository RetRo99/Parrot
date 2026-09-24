package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferSessionExpiredException
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.TransferResumeMode
import com.retro99.books.domain.TransferTransportCapabilities
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadSessionResult
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.transfer.SupabaseTusMetadataEncoder
import com.retro99.cloud.implementation.transfer.SupabaseTusRequestHeaderPolicy
import com.retro99.cloud.implementation.transfer.TusUploadClient
import com.retro99.cloud.implementation.transfer.TusUploadException
import com.retro99.cloud.implementation.transfer.TusUploadSessionExpiredException
import com.retro99.cloud.implementation.transfer.TusUploadMetadata
import com.retro99.cloud.implementation.transfer.TusUploadProfile
import com.retro99.cloud.implementation.transfer.TusUploadVerificationException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [BookFileTransferTransport::class])
class ParrotCloudBookFileTransferTransport(
    @Provided private val service: ParrotCloudBookFileService,
    @Provided private val tusUploadClient: TusUploadClient,
    @Provided private val configuration: CloudConfiguration,
    @Provided private val metadataEncoder: SupabaseTusMetadataEncoder,
    @Provided private val requestHeaderPolicy: SupabaseTusRequestHeaderPolicy,
) : BookFileTransferTransport {
    override val serverId: String = PARROT_CLOUD_SERVER_ID

    override val capabilities = TransferTransportCapabilities(
        resumeMode = TransferResumeMode.ByteOffset,
        supportsClientSuppliedId = false,
        supportsReplaceInPlace = false,
        maxRequestSizeBytes = 6L * 1024L * 1024L,
        supportsUpload = false,
    )

    override suspend fun reserve(request: BookFileUploadRequest): UploadReservationResult =
        service.reserve(request)

    override suspend fun upload(
        request: BookFileUploadRequest,
        reservation: UploadReservation,
        resumeUrl: String?,
        resumeOffset: Long,
        onSession: suspend (url: String, expiresAt: String?) -> Unit,
        onHashReset: suspend () -> Unit,
        onChunkHashed: suspend (bytes: ByteArray) -> Unit,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
    ): UploadSessionResult {
        var expiry: String? = null
        val finalUrl = try {
            tusUploadClient.upload(
                profile = TusUploadProfile(
                    baseUrl = configuration.supabaseUrl,
                    metadataEncoder = metadataEncoder,
                    requestHeaderPolicy = requestHeaderPolicy,
                ),
                uploadEndpoint = reservation.uploadEndpoint,
                metadata = TusUploadMetadata(
                    targetPath = reservation.storagePath,
                    bookUuid = request.localBookUuid,
                    fileName = request.fileName,
                    mediaType = request.mediaType,
                    sizeBytes = request.sizeBytes,
                    contentHash = request.contentHash,
                    totalFiles = 1,
                ),
                localPath = request.localPath,
                sizeBytes = request.sizeBytes,
                resumeUrl = resumeUrl,
                resumeOffset = resumeOffset,
                onSession = { url, expiresAt ->
                    expiry = expiresAt
                    onSession(url, expiresAt)
                },
                onHashReset = onHashReset,
                onChunkHashed = onChunkHashed,
                onProgress = onProgress,
            )
        } catch (_: TusUploadSessionExpiredException) {
            throw BookFileTransferSessionExpiredException()
        } catch (_: TusUploadVerificationException) {
            throw BookFileTransferRejectedException("verify_failed")
        } catch (exception: TusUploadException) {
            if (exception.statusCode in 400..499 &&
                exception.statusCode !in setOf(401, 408, 409, 429)
            ) {
                throw BookFileTransferRejectedException("tus_http_${exception.statusCode}")
            }
            throw exception
        }
        return UploadSessionResult(
            uploadUrl = finalUrl,
            expiresAt = expiry,
            bytesTransferred = request.sizeBytes,
        )
    }

    override suspend fun finalize(reservation: UploadReservation, request: BookFileUploadRequest) =
        service.finalize(reservation, request)

    override suspend fun cancel(reservation: UploadReservation?, resumeUrl: String?) {
        try {
            if (resumeUrl != null) tusUploadClient.cancel(resumeUrl, requestHeaderPolicy)
        } finally {
            if (reservation != null) service.cancel(reservation)
        }
    }
}
