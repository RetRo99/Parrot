package com.retro99.cloud.implementation.transfer

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class TusUploadClient(
    @Provided private val httpClient: HttpClient,
    @Provided private val localFileSource: TusLocalFileSource,
) {
    suspend fun upload(
        profile: TusUploadProfile,
        uploadEndpoint: String,
        metadata: TusUploadMetadata,
        localPath: String,
        sizeBytes: Long,
        resumeUrl: String?,
        resumeOffset: Long,
        onSession: suspend (url: String, expiresAt: String?) -> Unit,
        onHashReset: suspend () -> Unit,
        onChunkHashed: suspend (bytes: ByteArray) -> Unit,
        onProgress: suspend (bytesTransferred: Long) -> Unit,
    ): String {
        require(sizeBytes > 0) { "TUS upload must contain at least one byte" }
        if (localFileSource.size(localPath) != sizeBytes) {
            throw TusUploadVerificationException("Upload file size changed")
        }

        // A stored URL from an older build may point off-origin; start fresh.
        var url = resumeUrl?.takeIf { candidate ->
            isTrustedUploadUrl(absoluteUrl(profile.baseUrl, uploadEndpoint), candidate)
        }
        var expiresAt: String? = null
        var offset = 0L
        var hashedOffset = 0L
        var completionHeader: String? = null
        if (url == null) {
            val created = createUpload(profile, uploadEndpoint, metadata, sizeBytes)
            url = created.url
            expiresAt = created.expiresAt
            offset = created.offset
            onSession(url, expiresAt)
        } else {
            val head = head(url, profile.requestHeaderPolicy)
            if (head == null) {
                val created = createUpload(profile, uploadEndpoint, metadata, sizeBytes)
                url = created.url
                expiresAt = created.expiresAt
                offset = created.offset
                onSession(url, expiresAt)
            } else {
                require(head.length == sizeBytes) { "TUS upload length changed" }
                require(head.offset in 0L..sizeBytes) { "TUS server returned an invalid upload offset" }
                if (head.offset < resumeOffset) onHashReset()
                offset = head.offset
                expiresAt = head.expiresAt
                onSession(requireNotNull(url), expiresAt)
            }
        }

        suspend fun hashThrough(targetOffset: Long) {
            require(targetOffset in 0L..sizeBytes) { "Invalid TUS upload offset" }
            if (targetOffset < hashedOffset) {
                onHashReset()
                hashedOffset = 0L
            }
            while (hashedOffset < targetOffset) {
                val count = minOf(HASH_READ_CHUNK_SIZE.toLong(), targetOffset - hashedOffset).toInt()
                val bytes = localFileSource.read(localPath, hashedOffset, count)
                if (bytes.isEmpty()) {
                    throw TusUploadVerificationException("Upload file changed while hashing")
                }
                onChunkHashed(bytes)
                hashedOffset += bytes.size
            }
        }

        onProgress(offset)
        hashThrough(offset)
        while (offset < sizeBytes) {
            val chunkLength = minOf(CHUNK_SIZE_BYTES.toLong(), sizeBytes - offset).toInt()
            val chunk = localFileSource.read(localPath, offset, chunkLength)
            if (chunk.size != chunkLength) {
                throw TusUploadVerificationException("Upload file changed while sending")
            }
            val response = sendPatch(requireNotNull(url), offset, chunk, profile.requestHeaderPolicy)
            when {
                response.status == HttpStatusCode.Conflict -> {
                    val head = head(requireNotNull(url), profile.requestHeaderPolicy)
                        ?: error("TUS upload session disappeared")
                    require(head.length == sizeBytes) { "TUS upload length changed" }
                    require(head.offset in 0L..sizeBytes) { "TUS server returned an invalid upload offset" }
                    offset = head.offset
                    val refreshedExpiry = head.expiresAt
                    if (refreshedExpiry != null && refreshedExpiry != expiresAt) {
                        expiresAt = refreshedExpiry
                        onSession(requireNotNull(url), expiresAt)
                    }
                    onProgress(offset)
                    hashThrough(offset)
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
                    if (newOffset == sizeBytes) {
                        completionHeader = response.headers[HEADER_TUS_COMPLETE]
                    }
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
        // Supabase signals completion via Tus-Complete: 1 (spike findings §1.2).
        // Tolerate its absence (proxies may drop unknown headers) but never a
        // contradictory value.
        completionHeader?.let { value ->
            check(value == "1") { "TUS server did not confirm upload completion" }
        }
        return requireNotNull(url)
    }

    private suspend fun createUpload(
        profile: TusUploadProfile,
        endpoint: String,
        metadata: TusUploadMetadata,
        sizeBytes: Long,
    ): TusCreatedSession {
        val response = httpClient.request(absoluteUrl(profile.baseUrl, endpoint)) {
            method = HttpMethod.Post
            tusHeaders(TusRequestType.Create, profile.requestHeaderPolicy)
            header(HttpHeaders.ContentLength, "0")
            header(HEADER_UPLOAD_LENGTH, sizeBytes.toString())
            header(
                HEADER_UPLOAD_METADATA,
                encodeMetadata(profile.metadataEncoder.encode(metadata)),
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
            url = resolveLocation(absoluteUrl(profile.baseUrl, endpoint), location),
            offset = response.headers[HEADER_UPLOAD_OFFSET]?.toLongOrNull() ?: 0L,
            expiresAt = response.headers[HEADER_UPLOAD_EXPIRES],
        )
    }

    private suspend fun head(url: String, requestHeaderPolicy: TusRequestHeaderPolicy): TusHead? {
        val response = httpClient.request(url) {
            method = HttpMethod.Head
            tusHeaders(TusRequestType.Head, requestHeaderPolicy)
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

    private suspend fun sendPatch(
        url: String,
        offset: Long,
        bytes: ByteArray,
        requestHeaderPolicy: TusRequestHeaderPolicy,
    ) =
        httpClient.request(url) {
            method = HttpMethod.Patch
            tusHeaders(TusRequestType.Patch, requestHeaderPolicy)
            header(HEADER_UPLOAD_OFFSET, offset.toString())
            header(HttpHeaders.ContentType, "application/offset+octet-stream")
            setBody(bytes)
        }

    /** Returns false, without sending anything, when [uploadUrl] is off-origin. */
    suspend fun cancel(profile: TusUploadProfile, uploadUrl: String): Boolean {
        // Persisted URLs from older builds may be foreign; DELETE carries auth.
        if (!isTrustedUploadUrl(profile.baseUrl, uploadUrl)) return false
        val response = httpClient.request(uploadUrl) {
            method = HttpMethod.Delete
            tusHeaders(TusRequestType.Delete, profile.requestHeaderPolicy)
        }
        if (response.status.value !in 200..299 && response.status != HttpStatusCode.NotFound &&
            response.status != HttpStatusCode.Gone && response.status != HttpStatusCode.BadRequest
        ) {
            throw TusUploadException(
                message = "TUS cancellation failed with HTTP ${response.status.value}",
                statusCode = response.status.value,
            )
        }
        return true
    }

    private fun io.ktor.client.request.HttpRequestBuilder.tusHeaders(
        requestType: TusRequestType,
        requestHeaderPolicy: TusRequestHeaderPolicy,
    ) {
        header(HEADER_TUS_RESUMABLE, TUS_VERSION)
        requestHeaderPolicy.headersFor(requestType).forEach { (name, value) -> header(name, value) }
    }

    private fun absoluteUrl(baseUrl: String, endpoint: String): String = when {
        endpoint.startsWith("https://") || endpoint.startsWith("http://") -> endpoint
        else -> "${baseUrl.trimEnd('/')}/${endpoint.trimStart('/')}"
    }

    private fun resolveLocation(endpointUrl: String, location: String): String {
        val resolved = if (location.startsWith("https://") || location.startsWith("http://")) {
            location
        } else {
            // Relative to the endpoint's origin, which may be the storage host.
            "${originOf(endpointUrl)}/${location.trimStart('/')}"
        }
        // Chunks carry the auth headers, so they may only go where we created.
        check(isTrustedUploadUrl(endpointUrl, resolved)) { "TUS server returned a foreign Location" }
        return resolved
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
        const val HEADER_TUS_COMPLETE = "Tus-Complete"
        const val CHUNK_SIZE_BYTES = 6 * 1024 * 1024
        const val HASH_READ_CHUNK_SIZE = 64 * 1024
    }
}

class TusUploadException(
    message: String,
    val statusCode: Int,
) : Exception(message)

class TusUploadVerificationException(message: String) : Exception(message)

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

private val SUPABASE_DOMAINS = listOf("supabase.co", "supabase.in")

/**
 * Same scheme, host and port as [endpoint], so https never downgrades to
 * http. Supabase may answer on the project's storage host
 * (`ref.storage.supabase.co` for `ref.supabase.co`), which is allowed too.
 */
internal fun isTrustedUploadUrl(endpoint: String, candidate: String): Boolean {
    val expected = runCatching { Url(endpoint) }.getOrNull() ?: return false
    val actual = runCatching { Url(candidate) }.getOrNull() ?: return false
    // Url.port already falls back to the scheme's default port.
    if (!expected.protocol.name.equals(actual.protocol.name, ignoreCase = true)) return false
    if (expected.port != actual.port) return false
    val expectedHost = expected.host.lowercase()
    val actualHost = actual.host.lowercase()
    if (expectedHost == actualHost) return true
    return SUPABASE_DOMAINS.any { domain ->
        expectedHost.supabaseProjectRef(domain)?.let { ref ->
            ref == actualHost.supabaseProjectRef(domain)
        } == true
    }
}

private fun originOf(url: String): String {
    val parsed = Url(url)
    val port = if (parsed.port == parsed.protocol.defaultPort) "" else ":${parsed.port}"
    return "${parsed.protocol.name}://${parsed.host}$port"
}

/** `ref` for `ref.supabase.co` or `ref.storage.supabase.co`, else null. */
private fun String.supabaseProjectRef(domain: String): String? {
    val labels = removeSuffix(".$domain").takeIf { it != this }?.split('.') ?: return null
    return when {
        labels.size == 1 -> labels[0]
        labels.size == 2 && labels[1] == "storage" -> labels[0]
        else -> null
    }?.takeIf { it.isNotEmpty() }
}
