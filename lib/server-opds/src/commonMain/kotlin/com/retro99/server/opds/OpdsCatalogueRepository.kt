package com.retro99.server.opds

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
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

/** One profile/source/access session. No bearer client, crawling or auth discovery. */
class OpdsCatalogueRepository(
    private val profileId: String,
    internal val config: ServerConfig,
    private val transport: OpdsTransport,
    private val credentials: OpdsCredentialStore,
    private val access: CatalogueAccessStore,
    private val isCurrent: () -> Boolean,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val cache: OpdsFeedCache = MemoryOpdsFeedCache(),
    private val accessGeneration: Long = 0,
    private val accountOverride: (() -> OpdsAccountDetails?)? = null,
    private val recordAccessUpdates: Boolean = true,
) : ServerCatalogueRepository, CatalogueAcquisitionRepository, CatalogueImageRepository, CatalogueAccountVerifier {
    override val serverId = config.id
    /**
     * Whose places these are: this profile's catalogue at this address. Equal across sessions,
     * so a page can be asked for again after signing in replaced the session, and never equal
     * for another profile, another catalogue or an address that was changed.
     */
    private data class Owner(val profileId: String, val sourceId: String, val address: String)
    private val owner = Owner(profileId, config.id, config.baseUrl)

    /** A search, and the page a file is located on, belong to the session that fetched them. */
    private val session = Any()
    private class Target(val owner: Any, val session: Any, val url: String) : CatalogueTarget {
        override fun toString() = "CatalogueTarget(redacted)"
    }
    private class Search(val owner: Any, val expand: (CatalogueQuery) -> String) : CatalogueSearch {
        override fun toString() = "CatalogueSearch(redacted)"
    }
    private val parser = ParserFactory.opdsParser()
    private val loader = CachedOpdsFeedLoader(transport, parser, cache, now)
    private val resolver = ParserFactory.urlResolver()
    private val openSearch = OpenSearchReader(resolver)
    private val templates = Rfc6570Expander()
    private val mapper = OpdsCatalogueMapper { Target(owner, session, it) }
    private val mutex = Mutex()
    private val stopped = MutableStateFlow(false)
    internal val isStopped: Boolean get() = stopped.value
    private val requests = MutableStateFlow<Set<Job>>(emptySet())
    private val descriptors = linkedMapOf<String, OpdsSearchTemplate>()

    private fun currentAccount(): OpdsAccountDetails? =
        if (accountOverride == null) credentials.get(profileId, serverId) else accountOverride.invoke()

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

    /**
     * @param unasked a request the user was not asked about and cannot see the address of: a
     *   picture, a search description, the file behind a link. See [OpdsRequest.refuseLocalNetworkFromPublic].
     */
    private fun networkRequest(url: String, root: Boolean, accept: List<String> = OPDS_ACCEPT_MEDIA_TYPES, unasked: Boolean = false): OpdsRequest {
        val details = currentAccount()
        return OpdsRequest(url, accept, details?.let { OpdsCredentials.Basic(it.username, it.password) } ?: OpdsCredentials.Anonymous,
            allowCleartext = config.baseUrl.startsWith("http://", ignoreCase = true), isCatalogueRoot = root, refuseLocalNetworkFromPublic = unasked)
    }

    override suspend fun getRoot() = request { load(config.baseUrl, true) }
    private fun verifying(account: OpdsAccountDetails) = OpdsCatalogueRepository(profileId, config, transport, credentials, access, isCurrent, now,
        MemoryOpdsFeedCache(), accessGeneration, accountOverride = { account }, recordAccessUpdates = false)
    override suspend fun checkAccount(target: CatalogueTarget?, account: OpdsAccountDetails): AppResult<CatalogueDocument> = request {
        val checking = verifying(account)
        // The temporary session owns no transport: closing it would close this live session.
        if (target == null) checking.getRoot() else checking.getDocument(target)
    }
    override suspend fun checkSearchAccount(document: CatalogueDocument, query: CatalogueQuery, account: OpdsAccountDetails): AppResult<CatalogueDocument> = request {
        val checking = verifying(account)
        checking.discoverSearch(document).fold(
            success = { search -> search?.let { checking.search(it, query) } ?: Err(AppError.ApiError(400, "UnsupportedSearch")) },
            failure = { Err(it) },
        )
    }
    override suspend fun getDocument(target: CatalogueTarget) = request {
        val owned = target as? Target
        if (owned == null || owned.owner != owner) Err(AppError.ApiError(400, "ForeignCatalogueTarget")) else load(owned.url, false)
    }

    private suspend fun load(url: String, root: Boolean): AppResult<CatalogueDocument> {
        val request = networkRequest(url, root)
        val result = loader.load(OpdsCacheKey(url, profileId, serverId, accessGeneration, representation = request.acceptMediaTypes.joinToString(", ") { it.trim().lowercase() }), request)
        checkCurrent()
        return when (result) {
            is OpdsLoadResult.Document -> {
                if (recordAccessUpdates) access.recordSuccess(profileId, serverId, currentAccount()?.username, now(), root)
                val document = mapper.map(result.document, CatalogueFetchStatus(now(), result.fromCache, result.crossOriginPrivateNetwork))
                Ok(document)
            }
            is OpdsLoadResult.SavedCopy -> {
                // Still a failed check: the status says the catalogue could not be reached.
                if (recordAccessUpdates) access.recordFailure(profileId, serverId, CatalogueErrorKind.Unreachable, now(), false)
                Ok(mapper.map(result.document, CatalogueFetchStatus(now(), fromCache = true, crossOriginPrivateNetwork = false, savedCopyAt = result.storedAtMillis)))
            }
            is OpdsLoadResult.FetchFailure ->
                if (result.error.code == OpdsTransportError.Code.UNREACHABLE) {
                    if (recordAccessUpdates) access.recordFailure(profileId, serverId, CatalogueErrorKind.Unreachable, now(), false)
                    Err(AppError.ApiError(result.error.status ?: 400, CatalogueErrorKind.OfflineNoSavedCopy.name))
                } else failure(result.error)
            is OpdsLoadResult.ParseFailure -> parseFailure(
                result.rejection,
                isWebPage = result.responseContentType?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true,
            )
            OpdsLoadResult.NotModifiedWithoutCache -> parseFailure(null)
        }
    }

    /**
     * The catalogue's own search address replaces whatever it advertises. Otherwise the advertised
     * one is used, unless it would send an https catalogue's queries over plain http: that one is
     * never used, and the catalogue simply offers no search.
     */
    override suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?> = request {
        if ((document.context as? Target)?.owner != owner) return@request Err(AppError.ApiError(400, "ForeignCatalogueDocument"))
        val offer = (document as? CatalogueFeedDocument)?.search ?: return@request Ok(null)
        config.searchTemplate?.let { override ->
            val template = OpdsSearchTemplate(null, override, inputEncoding = "UTF-8", baseUrl = config.baseUrl)
            return@request Ok(allowed(Search(session) { query -> openSearch.expand(template, query.text, query.fields) }))
        }
        if (offer.kind == CatalogueSearchOffer.Kind.UriTemplate) {
            return@request Ok(allowed(Search(session) { query -> resolver.resolve(offer.link.effectiveBaseUri, templates.expand(offer.link.rawHref, query.fields + ("query" to query.text))) }))
        }
        val url = offer.link.resolvedHref ?: return@request Err(AppError.ApiError(400, "InvalidSearchDescriptor"))
        var preferred = descriptors[url]
        if (preferred == null) {
            val fetched = transport.fetch(networkRequest(url, false, listOf("application/opensearchdescription+xml"), unasked = true))
            checkCurrent()
            if (fetched is OpdsFetchResult.Failure) return@request failure(fetched.error)
            fetched as OpdsFetchResult.Response
            val parsed = openSearch.readDescriptor(OpdsPayload(fetched.contentType, fetched.body), fetched.effectiveUrl)
            if (parsed is OpdsOpenSearchReader.OpdsDescriptorResult.NotADescriptor) return@request parseFailure(parsed.rejection)
            preferred = (parsed as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor.preferred
            if (recordAccessUpdates) access.recordSuccess(profileId, serverId, currentAccount()?.username, now())
            if (preferred != null && "no-store" !in fetched.cacheDirectives && fetched.headers["vary"].orEmpty().all { it.equals("accept", true) }) {
                descriptors[url] = preferred
                while (descriptors.size > 20) descriptors.remove(descriptors.keys.first())
            }
        }
        val template = preferred ?: return@request Err(AppError.ApiError(400, "UnsupportedSearch"))
        Ok(allowed(Search(session) { query -> openSearch.expand(template, query.text, query.fields) }))
    }

    /** Null when an https catalogue's search would be sent over http. */
    private fun allowed(search: Search): Search? {
        // A template that cannot be expanded keeps failing when it is searched, as it always did.
        val probe = try { search.expand(CatalogueQuery("x")) } catch (_: OpdsSearchError) { return search } catch (_: IllegalArgumentException) { return search }
        val downgrade = config.baseUrl.startsWith("https://", ignoreCase = true) && probe.startsWith("http://", ignoreCase = true)
        return search.takeUnless { downgrade }
    }

    override suspend fun search(search: CatalogueSearch, query: CatalogueQuery) = request {
        val owned = search as? Search
        if (owned == null || owned.owner !== session) Err(AppError.ApiError(400, "ForeignCatalogueSearch")) else load(owned.expand(query), false)
    }

    override fun locate(document: CatalogueDocument, publication: CataloguePublication, choice: CatalogueFileChoice): CatalogueAcquisitionLocator? {
        val context = document.context as? Target ?: return null
        // A file is located on a page this session fetched itself.
        if (context.session !== session) return null
        val download = choice.action as? CatalogueAcquisitionAction.Download ?: return null
        if (download.link.resolvedHref == null || publications(document).none { it === publication || it == publication }) return null
        val representation = publication.representationKeyOf(choice) ?: return null
        return CatalogueAcquisitionLocator(context.url, publication.publicationKey, representation)
    }

    /**
     * The listing is fetched fresh, outside the cache, so an expired or re-signed file link is
     * never reused. The session lock is not held: browsing continues while a file streams.
     * Catalogue status is recorded for the listing request only; the file may live on a CDN.
     */
    override suspend fun download(locator: CatalogueAcquisitionLocator, sink: CatalogueFileSink): CatalogueDownloadOutcome = coroutineScope {
        val job = currentCoroutineContext().job
        requests.update { it + job }
        try {
            checkCurrent()
            val listing = transport.fetch(networkRequest(locator.documentUrl, false))
            checkCurrent()
            if (listing is OpdsFetchResult.Failure) {
                failure<Unit>(listing.error)
                return@coroutineScope CatalogueDownloadOutcome.Failed(downloadFailure(listing.error))
            }
            listing as OpdsFetchResult.Response
            val parsed = try { parser.parse(OpdsPayload(listing.contentType, listing.body), listing.effectiveUrl) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            val document = (parsed as? OpdsParseResult.Document)?.document?.let { mapper.map(it, CatalogueFetchStatus(now(), false, listing.crossOriginPrivateNetwork)) }
                ?: return@coroutineScope CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused)
            access.recordSuccess(profileId, serverId, credentials.get(profileId, serverId)?.username, now())
            val href = publications(document).firstOrNull { it.publicationKey == locator.publicationKey }
                ?.choiceForRepresentation(locator.representationKey)
                ?.let { (it.action as? CatalogueAcquisitionAction.Download)?.link?.resolvedHref }
                ?: return@coroutineScope CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused)
            val result = transport.download(networkRequest(href, false, OPDS_DOWNLOAD_ACCEPT_MEDIA_TYPES, unasked = true), object : OpdsDownloadSink {
                override suspend fun start(declaredLength: Long?) { checkCurrent(); sink.start(declaredLength) }
                override suspend fun write(buffer: ByteArray, length: Int) = sink.write(buffer, length)
            })
            currentCoroutineContext().ensureActive()
            checkCurrent()
            when (result) {
                is OpdsDownloadResult.Complete -> CatalogueDownloadOutcome.Complete(result.bytes, result.declaredLength)
                is OpdsDownloadResult.Failure ->
                    CatalogueDownloadOutcome.Failed(downloadFailure(result.error), result.error.declaredLength)
                is OpdsDownloadResult.SinkFailure -> CatalogueDownloadOutcome.SinkFailed(result.cause)
            }
        } finally { requests.update { it - job } }
    }

    /**
     * A cover or thumbnail, through this catalogue's transport: account details only on https
     * hops on the catalogue's own origin, the redirect and address checks of a page, and a
     * ceiling of its own. Like a file it does not hold the session lock and does not change the
     * catalogue's status: pictures often live on another host. A catalogue that is not on the
     * local network gets no picture from a device that is: the list would ask for it unseen.
     */
    override suspend fun loadImage(url: String): ByteArray? = coroutineScope {
        val job = currentCoroutineContext().job
        requests.update { it + job }
        try {
            checkCurrent()
            var bytes = ByteArray(0)
            var size = 0
            val result = transport.download(networkRequest(url, false, IMAGE_ACCEPT_MEDIA_TYPES, unasked = true), object : OpdsDownloadSink {
                override suspend fun start(declaredLength: Long?) {
                    checkCurrent()
                    bytes = ByteArray(declaredLength?.toInt() ?: IMAGE_FIRST_BUFFER_BYTES)
                }
                override suspend fun write(buffer: ByteArray, length: Int) {
                    if (size + length > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
                    buffer.copyInto(bytes, size, 0, length)
                    size += length
                }
            }, CatalogueImageLimits.MAX_IMAGE_BYTES)
            currentCoroutineContext().ensureActive()
            checkCurrent()
            when (result) {
                is OpdsDownloadResult.Complete -> if (size == bytes.size) bytes else bytes.copyOf(size)
                is OpdsDownloadResult.Failure -> null
                is OpdsDownloadResult.SinkFailure -> throw result.cause
            }
        } finally { requests.update { it - job } }
    }

    private fun publications(document: CatalogueDocument): List<CataloguePublication> = when (document) {
        is CataloguePublicationDocument -> listOf(document.publication)
        is CatalogueFeedDocument -> document.publications + document.groups.flatMap { it.publications }
    }

    private fun downloadFailure(error: OpdsTransportError): CatalogueDownloadFailure = when (error.code) {
        OpdsTransportError.Code.SIGN_IN_NEEDED -> CatalogueDownloadFailure.SignInNeeded
        OpdsTransportError.Code.RESPONSE_TOO_LARGE -> CatalogueDownloadFailure.TooLarge
        OpdsTransportError.Code.UNREACHABLE, OpdsTransportError.Code.TIMEOUT, OpdsTransportError.Code.TLS_UNTRUSTED,
        OpdsTransportError.Code.LENGTH_MISMATCH, OpdsTransportError.Code.RATE_LIMITED, OpdsTransportError.Code.SERVER_ERROR,
        OpdsTransportError.Code.SERVICE_UNAVAILABLE, OpdsTransportError.Code.REDIRECT_LIMIT, OpdsTransportError.Code.REDIRECT_LOOP,
        OpdsTransportError.Code.REDIRECT_MISSING_LOCATION -> CatalogueDownloadFailure.Connection
        // 403, 404 and other 4xx, a sign-in method Parrot does not support, and links the transport will not follow.
        else -> CatalogueDownloadFailure.Refused
    }

    private suspend fun <T> parseFailure(rejection: OpdsRejection?, isWebPage: Boolean = false): AppResult<T> {
        val kind = if (rejection is OpdsRejection.TooLarge) CatalogueErrorKind.TooLarge else CatalogueErrorKind.InvalidDocument
        if (recordAccessUpdates) access.recordFailure(profileId, serverId, kind, now(), false)
        val message = if (isWebPage) WEB_PAGE_VALIDATION_ERROR else if (rejection is OpdsRejection.NotACatalogue) NOT_CATALOGUE_VALIDATION_ERROR else kind.name
        return Err(AppError.ApiError(400, message))
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
        if (recordAccessUpdates) access.recordFailure(profileId, serverId, kind, now(), error.status == 401 && error.isCatalogueRoot)
        val message = if (!recordAccessUpdates && error.code == OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED && error.isCatalogueRoot) {
            UNSUPPORTED_ROOT_VALIDATION_ERROR
        } else {
            kind.name
        }
        return Err(AppError.ApiError(error.status ?: 400, message))
    }

    internal fun stop() {
        stopped.value = true
        requests.value.forEach { it.cancel(CancellationException("Catalogue session invalidated")) }
        transport.close()
    }
    suspend fun dispose() {
        stop()
        // A cache that keeps pages for offline reading ignores this; its pages go with the source.
        mutex.withLock { cache.clearAll(); descriptors.clear() }
    }
}

private const val WEB_PAGE_VALIDATION_ERROR = "WebPage"
private const val NOT_CATALOGUE_VALIDATION_ERROR = "NotCatalogue"
private const val UNSUPPORTED_ROOT_VALIDATION_ERROR = "SignInUnsupportedRoot"

/** Only the kinds [catalogueRasterImageType] accepts; what comes back is checked again by its bytes. */
private val IMAGE_ACCEPT_MEDIA_TYPES = listOf("image/webp", "image/png", "image/jpeg", "image/gif")
private const val IMAGE_FIRST_BUFFER_BYTES = 64 * 1024
