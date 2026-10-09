package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsBudgets

/**
 * Isolated OPDS HTTP transport (plan §4 "HTTP security and lifecycle").
 *
 * Requirements encoded by the implementation + tests:
 * - engine redirect auto-follow OFF; bounded manual redirect loop;
 * - credentials only to the configured HTTPS origin, never on a redirect hop;
 * - no HTTPS-to-HTTP downgrade in a followed redirect;
 * - response byte budget enforced while reading;
 * - cancellation propagates; errors carry bounded, hygiene-safe codes only.
 */
interface OpdsTransport {
    suspend fun fetch(request: OpdsRequest): OpdsFetchResult

    /**
     * Streams a file to [sink] without holding it in memory. Same redirect, origin and
     * credential rules as [fetch]; its own ceiling, [maxBytes].
     *
     * A missing Content-Length is normal. A declared length that differs from the bytes
     * received fails with [OpdsTransportError.Code.LENGTH_MISMATCH].
     */
    suspend fun download(
        request: OpdsRequest,
        sink: OpdsDownloadSink,
        maxBytes: Long = OpdsBudgets.MAX_DOWNLOAD_BYTES,
    ): OpdsDownloadResult
    fun close()
}

val OPDS_ACCEPT_MEDIA_TYPES = listOf("application/opds+json", "application/opds-publication+json",
    "application/atom+xml;profile=opds-catalog;kind=acquisition", "application/atom+xml;profile=opds-catalog;kind=navigation", "application/atom+xml")

data class OpdsRequest(
    val url: String,
    val acceptMediaTypes: List<String> = OPDS_ACCEPT_MEDIA_TYPES,
    val credentials: OpdsCredentials = OpdsCredentials.Anonymous,
    /** Explicitly permitted plain-HTTP catalogue (cleartext warning is Phase 2 UI work). */
    val allowCleartext: Boolean = false,
    /** ETag/Last-Modified validators for conditional requests (§4 cache behavior). */
    val cacheValidators: Map<String, String>? = null,
    /** Optional explicit root-fetch context; null means compare with the configured root URL. */
    val isCatalogueRoot: Boolean? = null,
    /**
     * For requests nobody was asked about (pictures, search descriptions, the file behind a
     * link): a catalogue that is not on the local network may not send them to a device that
     * is, directly or through a redirect. Fails with [OpdsTransportError.Code.LOCAL_NETWORK_NOT_ALLOWED]
     * before that device is contacted.
     */
    val refuseLocalNetworkFromPublic: Boolean = false,
)

sealed interface OpdsCredentials {
    object Anonymous : OpdsCredentials

    data class Basic(val username: String, val password: String) : OpdsCredentials
}

sealed interface OpdsFetchResult {
    data class Response(
        val status: Int,
        val headers: Map<String, List<String>>, // header names lower-cased by the impl
        val body: ByteArray,
        /** The URL that actually resolved the response (after redirects), plan §4. */
        val effectiveUrl: String,
        val fromCache: Boolean = false,
        /** Advisory only: cross-origin literal private/loopback address or local hostname. */
        val crossOriginPrivateNetwork: Boolean = false,
    ) : OpdsFetchResult {
        val contentType: String? get() = headers["content-type"]?.singleOrNull()
        val eTag: String? get() = headers["etag"]?.singleOrNull()
        val lastModified: String? get() = headers["last-modified"]?.singleOrNull()
        val cacheControl: List<String> get() = headers["cache-control"].orEmpty()

        /** Directives of the first `Cache-Control` value (bounded parsing). */
        val cacheDirectives: Set<String> get() = cacheControl.flatMap { it.split(',') }
            .map { it.trim().substringBefore('=').lowercase() }.toSet()
    }

    data class Failure(val error: OpdsTransportError, val crossOriginPrivateNetwork: Boolean = false) : OpdsFetchResult
}

/** Where downloaded bytes go. Throwing from either call stops the download. */
interface OpdsDownloadSink {
    /** Called once, after the final response's headers and before the first byte. */
    suspend fun start(declaredLength: Long?) {}

    /** The first [length] bytes of [buffer]. The buffer is reused, so copy what must be kept. */
    suspend fun write(buffer: ByteArray, length: Int)
}

/** Accept header for a book file: EPUB first, anything else the server insists on after. */
val OPDS_DOWNLOAD_ACCEPT_MEDIA_TYPES = listOf("application/epub+zip", "*/*;q=0.5")

sealed interface OpdsDownloadResult {
    data class Complete(
        val status: Int,
        val bytes: Long,
        /** The Content-Length the server sent, when it sent one. */
        val declaredLength: Long?,
        val effectiveUrl: String,
        val contentType: String?,
        val crossOriginPrivateNetwork: Boolean = false,
    ) : OpdsDownloadResult {
        override fun toString() = "OpdsDownloadResult.Complete($status, $bytes bytes)"
    }

    data class Failure(val error: OpdsTransportError, val crossOriginPrivateNetwork: Boolean = false) : OpdsDownloadResult

    /** The sink threw; [cause] is its exception, untouched. Bytes written so far are the caller's to delete. */
    class SinkFailure(val cause: Throwable) : OpdsDownloadResult
}

/**
 * Hygiene-safe transport errors: no URLs, no credentials, never titles or
 * search strings (plan §4 "Error/log categories are bounded").
 */
data class OpdsTransportError(val code: Code, val status: Int? = null, val note: String? = null,
    val retryAfterMillis: Long? = null, val isCatalogueRoot: Boolean = false,
    /** For [Code.RESPONSE_TOO_LARGE] on a download: the Content-Length that was over the ceiling. */
    val declaredLength: Long? = null) {
    enum class Code {
        UNREACHABLE,
        TIMEOUT,
        TLS_UNTRUSTED,
        CLIENT_ERROR, // 4xx besides 401/429
        SIGN_IN_NEEDED,
        SIGN_IN_METHOD_UNSUPPORTED,
        PASSWORD_OVER_HTTP,
        CLEARTEXT_NOT_ALLOWED,
        FORBIDDEN, // 403
        NOT_FOUND, // 404
        RATE_LIMITED, // 429 (Retry-After honored by the impl)
        SERVER_ERROR, // 5xx
        SERVICE_UNAVAILABLE,
        REDIRECT_LIMIT,
        REDIRECT_LOOP,
        REDIRECT_MISSING_LOCATION,
        REDIRECT_SCHEME_DOWNGRADE,
        RESPONSE_TOO_LARGE,
        LENGTH_MISMATCH, // download only: fewer or more bytes than Content-Length declared
        UNSUPPORTED_SCHEME,
        MALFORMED_URL,
        LOCAL_NETWORK_NOT_ALLOWED, // a public catalogue's unasked-for request to a local-network device
    }
}
