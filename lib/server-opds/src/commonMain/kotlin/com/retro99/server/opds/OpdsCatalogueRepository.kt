package com.retro99.server.opds

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.*
import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.ParserFactory
import com.retro99.opds.implementation.cache.CachedOpdsFeedLoader
import com.retro99.opds.implementation.cache.MemoryOpdsFeedCache
import com.retro99.opds.implementation.search.OpenSearchReader
import com.retro99.opds.implementation.search.Rfc6570Expander
import com.retro99.server.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** One profile/source/access session. No bearer client, crawling, auth discovery or acquisitions. */
class OpdsCatalogueRepository(
    private val profileId: String,
    internal val config: ServerConfig,
    private val transport: OpdsTransport,
    private val credentials: OpdsCredentialStore,
    private val access: CatalogueAccessStore,
    private val isCurrent: () -> Boolean,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ServerCatalogueRepository {
    override val serverId = config.id
    private val owner = Any()
    private class Target(val owner: Any, val url: String) : CatalogueTarget {
        override fun toString() = "CatalogueTarget(redacted)"
    }
    private class Search(val owner: Any, val expand: (CatalogueQuery) -> String) : CatalogueSearch {
        override fun toString() = "CatalogueSearch(redacted)"
    }
    private val cache = MemoryOpdsFeedCache()
    private val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), cache, now)
    private val resolver = ParserFactory.urlResolver()
    private val openSearch = OpenSearchReader(resolver)
    private val templates = Rfc6570Expander()
    private val mapper = OpdsCatalogueMapper { Target(owner, it) }
    private val mutex = Mutex()
    private val stopped = MutableStateFlow(false)
    private val requests = MutableStateFlow<Set<Job>>(emptySet())
    private val documents = ArrayDeque<CatalogueDocument>()
    private val descriptors = linkedMapOf<String, OpdsSearchTemplate>()

    private fun checkCurrent() {
        if (stopped.value || !isCurrent()) throw CancellationException("Catalogue session invalidated")
    }

    private suspend fun <T> request(block: suspend () -> AppResult<T>): AppResult<T> = coroutineScope {
        val job = currentCoroutineContext().job
        requests.update { it + job }
        try {
            mutex.withLock {
                checkCurrent()
                val result = try { block() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IllegalArgumentException) { Err(AppError.ApiError(400, "InvalidCatalogueRequest")) }
                    catch (_: OpdsSearchError) { Err(AppError.ApiError(400, "UnsupportedSearch")) }
                currentCoroutineContext().ensureActive()
                checkCurrent()
                result
            }
        } finally { requests.update { it - job } }
    }

    private fun networkRequest(url: String, root: Boolean, accept: List<String> = OPDS_ACCEPT_MEDIA_TYPES): OpdsRequest {
        val details = credentials.get(profileId, serverId)
        return OpdsRequest(url, accept, details?.let { OpdsCredentials.Basic(it.username, it.password) } ?: OpdsCredentials.Anonymous,
            allowCleartext = config.baseUrl.startsWith("http://", ignoreCase = true), isCatalogueRoot = root)
    }

    override suspend fun getRoot() = request { load(config.baseUrl, true) }
    override suspend fun getDocument(target: CatalogueTarget) = request {
        val owned = target as? Target
        if (owned == null || owned.owner !== owner) Err(AppError.ApiError(400, "ForeignCatalogueTarget")) else load(owned.url, false)
    }

    private suspend fun load(url: String, root: Boolean): AppResult<CatalogueDocument> {
        val request = networkRequest(url, root)
        val result = loader.load(OpdsCacheKey(url, profileId, serverId, representation = request.acceptMediaTypes.joinToString(", ") { it.trim().lowercase() }), request)
        checkCurrent()
        return when (result) {
            is OpdsLoadResult.Document -> {
                access.recordSuccess(profileId, serverId, credentials.get(profileId, serverId)?.username, now(), root)
                val document = mapper.map(result.document, CatalogueFetchStatus(now(), result.fromCache, result.crossOriginPrivateNetwork))
                documents.addLast(document)
                while (documents.size > 20) documents.removeFirst()
                Ok(document)
            }
            is OpdsLoadResult.FetchFailure -> failure(result.error)
            is OpdsLoadResult.ParseFailure -> parseFailure(result.rejection)
            OpdsLoadResult.NotModifiedWithoutCache -> parseFailure(null)
        }
    }

    override suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?> = request {
        if (documents.none { it === document }) return@request Err(AppError.ApiError(400, "ForeignCatalogueDocument"))
        val offer = (document as? CatalogueFeedDocument)?.search ?: return@request Ok(null)
        if (offer.kind == CatalogueSearchOffer.Kind.UriTemplate) {
            return@request Ok(Search(owner) { query -> resolver.resolve(offer.link.effectiveBaseUri, templates.expand(offer.link.rawHref, query.fields + ("query" to query.text))) })
        }
        val url = offer.link.resolvedHref ?: return@request Err(AppError.ApiError(400, "InvalidSearchDescriptor"))
        var preferred = descriptors[url]
        if (preferred == null) {
            val fetched = transport.fetch(networkRequest(url, false, listOf("application/opensearchdescription+xml")))
            checkCurrent()
            if (fetched is OpdsFetchResult.Failure) return@request failure(fetched.error)
            fetched as OpdsFetchResult.Response
            val parsed = openSearch.readDescriptor(OpdsPayload(fetched.contentType, fetched.body), fetched.effectiveUrl)
            if (parsed is OpdsOpenSearchReader.OpdsDescriptorResult.NotADescriptor) return@request parseFailure(parsed.rejection)
            preferred = (parsed as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor.preferred
            access.recordSuccess(profileId, serverId, credentials.get(profileId, serverId)?.username, now())
            if (preferred != null && "no-store" !in fetched.cacheDirectives && fetched.headers["vary"].orEmpty().all { it.equals("accept", true) }) {
                descriptors[url] = preferred
                while (descriptors.size > 20) descriptors.remove(descriptors.keys.first())
            }
        }
        val template = preferred ?: return@request Err(AppError.ApiError(400, "UnsupportedSearch"))
        Ok(Search(owner) { query -> openSearch.expand(template, query.text, query.fields) })
    }

    override suspend fun search(search: CatalogueSearch, query: CatalogueQuery) = request {
        val owned = search as? Search
        if (owned == null || owned.owner !== owner) Err(AppError.ApiError(400, "ForeignCatalogueSearch")) else load(owned.expand(query), false)
    }

    private suspend fun <T> parseFailure(rejection: OpdsRejection?): AppResult<T> {
        val kind = if (rejection is OpdsRejection.TooLarge) CatalogueErrorKind.TooLarge else CatalogueErrorKind.InvalidDocument
        access.recordFailure(profileId, serverId, kind, now(), false)
        return Err(AppError.ApiError(400, kind.name))
    }
    private suspend fun <T> failure(error: OpdsTransportError): AppResult<T> {
        val kind = when (error.code) {
            OpdsTransportError.Code.SIGN_IN_NEEDED -> CatalogueErrorKind.SignInNeeded
            OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED -> CatalogueErrorKind.SignInUnsupported
            OpdsTransportError.Code.UNREACHABLE -> CatalogueErrorKind.Unreachable
            OpdsTransportError.Code.TIMEOUT -> CatalogueErrorKind.Timeout
            OpdsTransportError.Code.TLS_UNTRUSTED -> CatalogueErrorKind.Tls
            OpdsTransportError.Code.FORBIDDEN -> CatalogueErrorKind.Forbidden
            OpdsTransportError.Code.NOT_FOUND -> CatalogueErrorKind.NotFound
            OpdsTransportError.Code.RATE_LIMITED -> CatalogueErrorKind.RateLimited
            OpdsTransportError.Code.SERVER_ERROR, OpdsTransportError.Code.SERVICE_UNAVAILABLE -> CatalogueErrorKind.ServerError
            OpdsTransportError.Code.RESPONSE_TOO_LARGE -> CatalogueErrorKind.TooLarge
            else -> CatalogueErrorKind.SecurityPolicy
        }
        access.recordFailure(profileId, serverId, kind, now(), error.status == 401 && error.isCatalogueRoot)
        return Err(AppError.ApiError(error.status ?: 400, kind.name))
    }

    internal fun stop() {
        stopped.value = true
        requests.value.forEach { it.cancel(CancellationException("Catalogue session invalidated")) }
        transport.close()
    }
    suspend fun dispose() {
        stop()
        mutex.withLock { cache.clearAll(); descriptors.clear(); documents.clear() }
    }
}
