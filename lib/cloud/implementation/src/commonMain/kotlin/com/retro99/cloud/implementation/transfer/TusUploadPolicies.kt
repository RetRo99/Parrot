package com.retro99.cloud.implementation.transfer

import io.ktor.http.HttpHeaders

/** Backend-neutral values a server may need when creating a TUS upload. */
data class TusUploadMetadata(
    val targetPath: String,
    val bookUuid: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val totalFiles: Int = 1,
) {
    fun contentType(): String = when (mediaType.lowercase()) {
        "ebook", "readaloud" -> "application/epub+zip"
        "audiobook" -> audioContentType(fileName)
        else -> contentTypeFromFileName(fileName) ?: "application/octet-stream"
    }

    private fun audioContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun contentTypeFromFileName(fileName: String): String? = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        else -> null
    }
}

/** Produces backend-specific key/value pairs; TUS wire encoding stays in [TusUploadClient]. */
fun interface TusMetadataEncoder {
    fun encode(metadata: TusUploadMetadata): Map<String, String>
}

enum class TusRequestType {
    Create,
    Head,
    Patch,
    Delete,
}

/** Supplies all backend-specific headers for each TUS request. */
fun interface TusRequestHeaderPolicy {
    fun headersFor(requestType: TusRequestType): Map<String, String>
}

/** Access-token source is intentionally backend-neutral; implementations may refresh on demand. */
fun interface TusAccessTokenProvider {
    fun currentAccessToken(): String?
}

/**
 * Reusable bearer-auth policy for TUS servers. Configure request types and optional gateway
 * headers per backend (for example, Storyteller authenticates HEAD while Supabase does not).
 */
class BearerTusRequestHeaderPolicy(
    private val accessTokenProvider: TusAccessTokenProvider,
    private val authenticatedRequests: Set<TusRequestType> = TusRequestType.values().toSet(),
    private val apiKey: String? = null,
    private val apiKeyRequests: Set<TusRequestType> = emptySet(),
) : TusRequestHeaderPolicy {
    override fun headersFor(requestType: TusRequestType): Map<String, String> = buildMap {
        if (requestType in authenticatedRequests) {
            val token = accessTokenProvider.currentAccessToken()
                ?: error("TUS request requires an authenticated session")
            put(HttpHeaders.Authorization, "Bearer $token")
        }
        if (requestType in apiKeyRequests) {
            put("apikey", requireNotNull(apiKey) { "TUS request requires an API key" })
        }
    }
}

/** A complete backend profile supplied by the transport, never selected by the protocol client. */
data class TusUploadProfile(
    val baseUrl: String,
    val metadataEncoder: TusMetadataEncoder,
    val requestHeaderPolicy: TusRequestHeaderPolicy,
)
