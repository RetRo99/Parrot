package com.retro99.opds.api

/**
 * Isolated OPDS HTTP transport (plan §4 "HTTP security and lifecycle").
 *
 * Requirements encoded by the implementation + tests:
 * - engine redirect auto-follow OFF; bounded manual redirect loop;
 * - credentials only to the configured HTTPS origin, never on a redirect hop;
 * - no scheme downgrade (https→https/http) in a followed redirect;
 * - response byte budget enforced while reading;
 * - cancellation propagates; errors carry bounded, hygiene-safe codes only.
 */
interface OpdsTransport {
    suspend fun fetch(request: OpdsRequest): OpdsFetchResult
}

data class OpdsRequest(
    val url: String,
    val acceptMediaTypes: List<String>,
    val credentials: OpdsCredentials,
    /** Explicitly permitted plain-HTTP catalogue (cleartext warning is Phase 2 UI work). */
    val allowCleartext: Boolean = false,
    /** ETag/Last-Modified validators for conditional requests (§4 cache behavior). */
    val cacheValidators: Map<String, String>? = null,
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
    ) : OpdsFetchResult {
        val contentType: String? get() = headers["content-type"]?.singleOrNull()
        val eTag: String? get() = headers["etag"]?.singleOrNull()
        val lastModified: String? get() = headers["last-modified"]?.singleOrNull()
        val cacheControl: List<String> get() = headers["cache-control"].orEmpty()

        /** Directives of the first `Cache-Control` value (bounded parsing). */
        val cacheDirectives: Set<String> get() = cacheControl.singleOrNull()
            ?.split(',')?.map { it.trim().lowercase() }?.toSet() ?: emptySet()
    }

    data class Failure(val error: OpdsTransportError) : OpdsFetchResult
}

/**
 * Hygiene-safe transport errors: no URLs, no credentials, never titles or
 * search strings (plan §4 "Error/log categories are bounded").
 */
data class OpdsTransportError(val code: Code, val status: Int? = null, val note: String? = null) {
    enum class Code {
        UNREACHABLE,
        TIMEOUT,
        TLS_UNTRUSTED,
        CLIENT_ERROR, // 4xx besides 401/429
        UNAUTHORIZED, // 401 (auth challenge; Phase 2 maps to access state)
        FORBIDDEN, // 403
        NOT_FOUND, // 404
        RATE_LIMITED, // 429 (Retry-After honored by the impl)
        SERVER_ERROR, // 5xx
        REDIRECT_LIMIT,
        REDIRECT_SCHEME_DOWNGRADE,
        RESPONSE_TOO_LARGE,
        CANCELLED,
        UNSUPPORTED_SCHEME,
        MALFORMED_URL,
    }
}
