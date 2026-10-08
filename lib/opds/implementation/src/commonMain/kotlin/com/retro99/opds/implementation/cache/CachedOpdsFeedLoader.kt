package com.retro99.opds.implementation.cache

import com.retro99.opds.api.*
import io.ktor.http.HttpHeaders
import kotlin.time.Clock

/**
 * Always revalidate. A cached page is served without the catalogue's say-so in one case only:
 * the catalogue could not be reached, and then it is marked as a saved copy. No crawling or
 * auth discovery.
 */
class CachedOpdsFeedLoader(
    private val transport: OpdsTransport,
    private val parser: OpdsParser,
    private val cache: OpdsFeedCache,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : OpdsFeedLoader {
    override suspend fun load(key: OpdsCacheKey, request: OpdsRequest): OpdsLoadResult {
        require(key.url == request.url && key.representation == request.acceptMediaTypes.joinToString(", ") { it.trim().lowercase() }) { "cache key does not match request" }
        val cached = cache.load(key)
        val fetched = transport.fetch(request.copy(cacheValidators = cached?.validators ?: request.cacheValidators))
        if (fetched is OpdsFetchResult.Failure) {
            if (fetched.error.code == OpdsTransportError.Code.UNREACHABLE && cached != null) {
                val saved = cached.document?.let { OpdsParseResult.Document(it) } ?: parser.parse(cached.body, cached.effectiveUrl)
                if (saved is OpdsParseResult.Document) return OpdsLoadResult.SavedCopy(saved.document, cached.storedAtMillis)
            }
            return OpdsLoadResult.FetchFailure(fetched.error, fetched.crossOriginPrivateNetwork)
        }
        fetched as OpdsFetchResult.Response
        val notModified = fetched.status == 304
        if (notModified && cached == null) return OpdsLoadResult.NotModifiedWithoutCache
        val payload = if (notModified) cached!!.body else OpdsPayload(fetched.contentType, fetched.body)
        val parsed = if (notModified && cached!!.effectiveUrl == fetched.effectiveUrl && cached.document != null) {
            OpdsParseResult.Document(cached.document!!)
        } else parser.parse(payload, fetched.effectiveUrl)
        if (parsed is OpdsParseResult.Rejected) {
            cache.invalidate(key)
            return OpdsLoadResult.ParseFailure(parsed.rejection, fetched.contentType)
        }
        parsed as OpdsParseResult.Document
        val controls = if (fetched.cacheControl.isNotEmpty()) fetched.cacheControl else if (notModified) cached!!.cacheControl else emptyList()
        // Vary:* or unkeyed request headers cannot safely reuse this representation.
        val vary = fetched.headers["vary"].orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }
        if (controls.hasNoStore() || vary.any { it != "accept" }) {
            cache.invalidate(key)
        } else {
            val validators = if (notModified) cached!!.validators.toMutableMap() else mutableMapOf()
            fetched.eTag?.let { validators[HttpHeaders.IfNoneMatch] = it }
            fetched.lastModified?.let { validators[HttpHeaders.IfModifiedSince] = it }
            cache.store(key, OpdsCacheEntry(payload, validators, nowMillis(), fetched.effectiveUrl, controls, parsed.document))
        }
        return OpdsLoadResult.Document(parsed.document, fromCache = notModified, crossOriginPrivateNetwork = fetched.crossOriginPrivateNetwork)
    }
}
