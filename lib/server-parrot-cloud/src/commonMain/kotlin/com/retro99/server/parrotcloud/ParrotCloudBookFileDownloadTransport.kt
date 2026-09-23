package com.retro99.server.parrotcloud

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferSessionExpiredException
import com.retro99.books.domain.BookFileTransferDownloadIncompleteException
import com.retro99.books.domain.BookFileTransferDownloadException
import com.retro99.cloud.implementation.transfer.CloudFileTransferClient
import com.retro99.cloud.implementation.transfer.CloudFileTransferGrantExpiredException
import com.retro99.cloud.implementation.transfer.CloudFileTransferIncompleteException
import com.retro99.cloud.implementation.transfer.CloudFileTransferHttpException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlinx.coroutines.CancellationException

@Single(binds = [BookFileDownloadTransport::class])
class ParrotCloudBookFileDownloadTransport(
    @Provided private val service: ParrotCloudBookFileService,
    @Provided private val cloudFileTransferClient: CloudFileTransferClient,
) : BookFileDownloadTransport {
    override val serverId: String = PARROT_CLOUD_SERVER_ID
    override val supportsDownload: Boolean = true

    override suspend fun download(
        request: BookFileDownloadRequest,
        resumeOffset: Long,
        onResponseOffset: suspend (offset: Long) -> Unit,
        onChunk: suspend (bytes: ByteArray) -> Unit,
    ) {
        try {
            val signedUrl = service.createDownloadUrl(request)
            cloudFileTransferClient.download(
                signedUrl = signedUrl,
                resumeOffset = resumeOffset,
                expectedSize = request.sizeBytes,
                onResponseOffset = onResponseOffset,
                onChunk = onChunk,
            )
        } catch (_: CloudFileTransferGrantExpiredException) {
            throw BookFileTransferSessionExpiredException()
        } catch (_: CloudFileTransferIncompleteException) {
            throw BookFileTransferDownloadIncompleteException()
        } catch (exception: BookFileTransferRejectedException) {
            throw exception
        } catch (exception: CloudFileTransferHttpException) {
            if (exception.statusCode in 400..499 &&
                exception.statusCode !in setOf(408, 409, 429)
            ) {
                throw BookFileTransferRejectedException("download_http_${exception.statusCode}")
            }
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Ktor exceptions can include the signed URL; never persist them in transfer state.
            throw BookFileTransferDownloadException()
        }
    }
}
