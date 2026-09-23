package com.retro99.cloud.implementation.transfer

import com.retro99.cloud.implementation.CloudConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class TusUploadClient(
    @Provided private val httpClient: HttpClient,
    @Provided private val localFileSource: TusLocalFileSource,
    @Provided private val configuration: CloudConfiguration,
    @Provided private val accessTokenProvider: TusAccessTokenProvider,
) {
    suspend fun upload(
        uploadEndpoint: String,
        storagePath: String,
        localPath: String,
        sizeBytes: Long,
        contentHash: String,
        resumeUrl: String?,
        resumeOffset: Long,
        onSession: suspend (url: String, expiresAt: String?) -> Unit,
        onChunkHashed: suspend (bytes: ByteArray) -> Unit,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
    ): String {
        require(sizeBytes > 0) { "TUS upload must contain at least one byte" }
        require(localFileSource.size(localPath) == sizeBytes) { "Upload file size changed" }

        var url = resumeUrl
        var expiresAt: String? = null
        var offset = 0L
        if (url == null) {
            val created = createUpload(uploadEndpoint, storagePath, sizeBytes, contentHash)
            url = created.url
            expiresAt = created.expiresAt
            offset = created.offset
            onSession(url, expiresAt)
        } else {
            val head = head(url)
            if (head == null) {
                val created = createUpload(uploadEndpoint, storagePath, sizeBytes, contentHash)
                url = created.url
                expiresAt = created.expiresAt
                offset = created.offset
                onSession(url, expiresAt)
            } else {
                require(head.length == sizeBytes) { "TUS upload length changed" }
                require(head.offset >= resumeOffset) { "TUS server moved upload offset backwards" }
                offset = head.offset
                expiresAt = head.expiresAt
                onSession(requireNotNull(url), expiresAt)
            }
        }

        var hashedOffset = 0L
        suspend fun hashThrough(targetOffset: Long) {
            require(targetOffset in hashedOffset..sizeBytes) { "Invalid TUS upload offset" }
            while (hashedOffset < targetOffset) {
                val count = minOf(HASH_READ_CHUNK_SIZE.toLong(), targetOffset - hashedOffset).toInt()
                val bytes = localFileSource.read(localPath, hashedOffset, count)
                check(bytes.isNotEmpty()) { "Unexpected end of upload file" }
                onChunkHashed(bytes)
                hashedOffset += bytes.size
            }
        }

        hashThrough(offset)
        onProgress(offset)
        while (offset < sizeBytes) {
            val chunkLength = minOf(CHUNK_SIZE_BYTES.toLong(), sizeBytes - offset).toInt()
            val chunk = localFileSource.read(localPath, offset, chunkLength)
            check(chunk.size == chunkLength) { "Unexpected end of upload file" }
            val response = sendPatch(requireNotNull(url), offset, chunk)
            when {
                response.status == HttpStatusCode.Conflict -> {
                    val head = head(requireNotNull(url)) ?: error("TUS upload session disappeared")
                    require(head.length == sizeBytes) { "TUS upload length changed" }
                    require(head.offset >= offset) { "TUS server moved upload offset backwards" }
                    require(head.offset > offset) { "TUS conflict did not advance the upload offset" }
                    offset = head.offset
                    val refreshedExpiry = head.expiresAt
                    if (refreshedExpiry != null && refreshedExpiry != expiresAt) {
                        expiresAt = refreshedExpiry
                        onSession(requireNotNull(url), expiresAt)
                    }
                    hashThrough(offset)
                    onProgress(offset)
                }

                response.status == HttpStatusCode.NotFound ||
                    response.status == HttpStatusCode.Gone -> {
                    throw TusUploadSessionExpiredException()
                }

                response.status == HttpStatusCode.NoContent -> {
                    val newOffset = response.headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull()
                        ?: error("TUS PATCH response omitted Upload-Offset")
                    require(newOffset > offset && newOffset <= sizeBytes) {
                        "TUS server returned an invalid upload offset"
                    }
                    offset = newOffset
                    val refreshedExpiry = response.headers[HEADER_UPLOAD_EXPIRES]
                    if (refreshedExpiry != null && refreshedExpiry != expiresAt) {
                        expiresAt = refreshedExpiry
                        onSession(requireNotNull(url), expiresAt)
                    }
                    hashThrough(offset)
                    onProgress(offset)
                }

                else -> throw TusUploadException(
                    message = "TUS PATCH failed with HTTP ${response.status.value}",
                    statusCode = response.status.value,
                )
            }
        }
        check(offset == sizeBytes) { "TUS upload ended before all bytes were acknowledged" }
        return requireNotNull(url)
    }

    private suspend fun createUpload(
        endpoint: String,
        storagePath: String,
        sizeBytes: Long,
        contentHash: String,
    ): TusCreatedSession {
        val response = httpClient.request(absoluteUrl(endpoint)) {
            method = HttpMethod.Post
            tusHeaders()
            header(HttpHeaders.ContentLength, "0")
            header(HEADER_UPLOAD_LENGTH, sizeBytes.toString())
            header(
                HEADER_UPLOAD_METADATA,
                encodeMetadata(
                    linkedMapOf(
                        "bucketName" to "book-files",
                        "objectName" to storagePath,
                        "contentType" to "application/epub+zip",
                        "cacheControl" to "3600",
                        "metadata" to "{\"sha256\":\"$contentHash\"}",
                    ),
                ),
            )
        }
        if (response.status != HttpStatusCode.Created) {
            throw TusUploadException(
                message = "TUS session creation failed with HTTP ${response.status.value}",
                statusCode = response.status.value,
            )
        }
        val location = response.headers[HttpHeaders.Location]
            ?: error("TUS create response omitted Location")
        return TusCreatedSession(
            url = resolveLocation(location),
            offset = response.headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull() ?: 0L,
            expiresAt = response.headers[HEADER_UPLOAD_EXPIRES],
        )
    }

    private suspend fun head(url: String): TusHead? {
        val response = httpClient.request(url) {
            method = HttpMethod.Head
            tusHeaders()
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone) {
            return null
        }
        if (response.status.value !in 200..299) {
            throw TusUploadException(
                message = "TUS offset lookup failed with HTTP ${response.status.value}",
                statusCode = response.status.value,
            )
        }
        val offset = response.headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull()
            ?: error("TUS HEAD response omitted Upload-Offset")
        val length = response.headers[HEADER_UPLOAD_LENGTH]?.toLongOrNull()
            ?: error("TUS HEAD response omitted Upload-Length")
        return TusHead(offset, length, response.headers[HEADER_UPLOAD_EXPIRES])
    }

    private suspend fun sendPatch(url: String, offset: Long, bytes: ByteArray) =
        httpClient.request(url) {
            method = HttpMethod.Patch
            tusHeaders()
            header(HEADER_UPLOAD_OFFSET, offset.toString())
            header(HttpHeaders.ContentType, "application/offset+octet-stream")
            setBody(bytes)
        }

    suspend fun cancel(uploadUrl: String) {
        val response = httpClient.request(uploadUrl) {
            method = HttpMethod.Delete
            tusHeaders()
        }
        if (response.status.value !in 200..299 && response.status != HttpStatusCode.NotFound &&
            response.status != HttpStatusCode.Gone && response.status != HttpStatusCode.BadRequest
        ) {
            throw TusUploadException(
                message = "TUS cancellation failed with HTTP ${response.status.value}",
                statusCode = response.status.value,
            )
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.tusHeaders() {
        header(HEADER_TUS_RESUMABLE, TUS_VERSION)
        header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        header("apikey", configuration.publishableKey)
    }

    private fun accessToken(): String = accessTokenProvider.currentAccessToken()
        ?: error("Parrot Cloud session is not authenticated")

    private fun absoluteUrl(endpoint: String): String = when {
        endpoint.startsWith("https://") || endpoint.startsWith("http://") -> endpoint
        else -> "${configuration.supabaseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
    }

    private fun resolveLocation(location: String): String = when {
        location.startsWith("https://") || location.startsWith("http://") -> location
        else -> "${configuration.supabaseUrl.trimEnd('/')}/${location.trimStart('/')}"
    }

    private data class TusCreatedSession(val url: String, val offset: Long, val expiresAt: String?)
    private data class TusHead(val offset: Long, val length: Long, val expiresAt: String?)

    private companion object {
        const val TUS_VERSION = "1.0.0"
        const val HEADER_TUS_RESUMABLE = "Tus-Resumable"
        const val HEADER_UPLOAD_LENGTH = "Upload-Length"
        const val HEADER_UPLOAD_OFFSET = "Upload-Offset"
        const val HEADER_UPLOAD_EXPIRES = "Upload-Expires"
        const val HEADER_UPLOAD_METADATA = "Upload-Metadata"
        const val CHUNK_SIZE_BYTES = 6 * 1024 * 1024
        const val HASH_READ_CHUNK_SIZE = 64 * 1024
    }
}

class TusUploadException(
    message: String,
    val statusCode: Int,
) : Exception(message)

class TusUploadSessionExpiredException : Exception("TUS upload session expired")

private fun encodeMetadata(values: Map<String, String>): String = values.entries.joinToString(",") { (key, value) ->
    "$key ${value.encodeToByteArray().encodeBase64()}"
}

private fun ByteArray.encodeBase64(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString(((size + 2) / 3) * 4) {
        var index = 0
        while (index < size) {
            val first = this@encodeBase64[index].toInt() and 0xff
            val second = if (index + 1 < size) this@encodeBase64[index + 1].toInt() and 0xff else 0
            val third = if (index + 2 < size) this@encodeBase64[index + 2].toInt() and 0xff else 0
            append(alphabet[first ushr 2])
            append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            append(if (index + 1 < size) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            append(if (index + 2 < size) alphabet[third and 0x3f] else '=')
            index += 3
        }
    }
}
