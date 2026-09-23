package com.retro99.cloud.implementation.transfer

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class CloudFileTransferClient(
    @Provided private val httpClient: HttpClient,
) {
    suspend fun download(
        signedUrl: String,
        resumeOffset: Long,
        expectedSize: Long,
        onResponseOffset: suspend (Long) -> Unit,
        onChunk: suspend (ByteArray) -> Unit,
    ) {
        httpClient.prepareGet(signedUrl) {
            if (resumeOffset > 0) header(HttpHeaders.Range, "bytes=$resumeOffset-")
        }.execute { response ->
            if (response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden
            ) {
                throw CloudFileTransferGrantExpiredException()
            }
            if (response.status.value !in 200..299) {
                throw CloudFileTransferHttpException(response.status.value)
            }

            val responseOffset = when (response.status) {
                HttpStatusCode.PartialContent -> parseContentRange(
                    response.headers[HttpHeaders.ContentRange],
                    expectedSize,
                ).also { start ->
                    require(start == resumeOffset) { "Download server returned an unexpected range" }
                }

                HttpStatusCode.OK -> 0L
                else -> throw CloudFileTransferHttpException(response.status.value)
            }
            onResponseOffset(responseOffset)

            var totalBytes = responseOffset
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            val body = response.bodyAsChannel()
            while (true) {
                val count = body.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                onChunk(buffer.copyOf(count))
                totalBytes += count
                check(totalBytes <= expectedSize) { "Download exceeded expected file size" }
            }
            if (totalBytes != expectedSize) throw CloudFileTransferIncompleteException()
        }
    }

    private fun parseContentRange(value: String?, expectedSize: Long): Long {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty())
            ?: error("Download response omitted a valid Content-Range")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val total = match.groupValues[3].toLong()
        require(start <= end && end == expectedSize - 1 && total == expectedSize) {
            "Download server returned an invalid Content-Range"
        }
        return start
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

class CloudFileTransferGrantExpiredException : Exception("Download authorization expired")

class CloudFileTransferIncompleteException : Exception("Downloaded file is incomplete")

class CloudFileTransferHttpException(val statusCode: Int) :
    Exception("Download failed with HTTP $statusCode")
