package com.retro99.opds.implementation.cache

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FeedCacheTest {
    private val root = "https://example.org/root"
    private val body = """{"metadata":{"title":"Feed"},"publications":[]}"""
    private fun key(url: String = root) = OpdsCacheKey(url, "profile", "source", representation = OPDS_ACCEPT_MEDIA_TYPES.joinToString(", "))
    private fun entry(bytes: Int, noStore: Boolean = false) = OpdsCacheEntry(OpdsPayload("application/opds+json", ByteArray(bytes)), emptyMap(), 0, root,
        cacheControl = if (noStore) listOf("private", "NO-STORE") else emptyList())

    @Test fun keys_isolate_source_profile_generation_url_and_representation() = runTest {
        val cache = MemoryOpdsFeedCache()
        val key = key()
        cache.store(key, entry(1))
        for (other in listOf(key.copy(serverId = "other"), key.copy(profileId = "other"), key.copy(accessGeneration = 1), key.copy(url = "$root?query=other"), key.copy(representation = "application/atom+xml"))) assertNull(cache.load(other))
        assertNotNull(cache.load(key))
    }
    @Test fun entry_count_eviction_is_lru() = runTest {
        val cache = MemoryOpdsFeedCache(maxEntries = 2, maxBytes = 100)
        val a = key("$root/a"); val b = key("$root/b"); val c = key("$root/c")
        cache.store(a, entry(1)); cache.store(b, entry(1)); cache.load(a); cache.store(c, entry(1))
        assertNotNull(cache.load(a)); assertNull(cache.load(b)); assertNotNull(cache.load(c))
    }
    @Test fun byte_eviction_updates_replacements_and_rejects_oversize_entries() = runTest {
        val cache = MemoryOpdsFeedCache(maxEntries = 10, maxBytes = 4)
        val a = key("$root/a"); val b = key("$root/b"); val c = key("$root/c")
        cache.store(a, entry(2)); cache.store(b, entry(2)); cache.store(a, entry(3))
        assertNull(cache.load(b)); assertEquals(3, cache.load(a)?.body?.bytes?.size)
        cache.store(c, entry(5)); assertNull(cache.load(c))
        cache.store(a, entry(5)); assertNull(cache.load(a))
        cache.store(b, entry(4)); assertNotNull(cache.load(b))
        cache.invalidate(b); assertNull(cache.load(b))
        cache.store(a, entry(4)); cache.clearAll(); assertNull(cache.load(a))
    }
    @Test fun no_store_removes_previous_cache_entry() = runTest {
        val cache = MemoryOpdsFeedCache()
        cache.store(key(), entry(1)); cache.store(key(), entry(1, noStore = true))
        assertNull(cache.load(key()))
    }
    @Test fun byte_arrays_cannot_mutate_cached_state_or_budget() = runTest {
        val cache = MemoryOpdsFeedCache()
        val entry = entry(1)
        cache.store(key(), entry)
        entry.body.bytes[0] = 1
        assertEquals(0, cache.load(key())?.body?.bytes?.first()?.toInt())
        cache.load(key())!!.body.bytes[0] = 2
        assertEquals(0, cache.load(key())?.body?.bytes?.first()?.toInt())
    }
    @Test fun validators_are_sent_and_304_serves_cached_document() = runTest {
        var hop = 0
        val engine = MockEngine { if (hop++ == 0) respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("application/opds+json"), HttpHeaders.ETag to listOf("\"v1\""), HttpHeaders.LastModified to listOf("Wed, 21 Oct 2015 07:28:00 GMT"))) else respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v2\"")) }
        val transport = KtorOpdsTransport(engine, root)
        val cache = MemoryOpdsFeedCache()
        val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), cache, nowMillis = { 10 })
        val fresh = loader.load(key(), OpdsRequest(root)) as OpdsLoadResult.Document
        val cached = loader.load(key(), OpdsRequest(root)) as OpdsLoadResult.Document
        assertFalse(fresh.fromCache); assertTrue(cached.fromCache)
        assertEquals(fresh.document, cached.document)
        assertEquals("\"v1\"", engine.requestHistory.last().headers[HttpHeaders.IfNoneMatch])
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", engine.requestHistory.last().headers[HttpHeaders.IfModifiedSince])
        assertEquals("\"v2\"", cache.load(key())?.validators?.get(HttpHeaders.IfNoneMatch))
        transport.close()
    }
    @Test fun no_store_response_and_304_no_store_are_honored() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotModified)) {
            var hop = 0
            val engine = MockEngine { if (hop++ == 0) respond(body, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) else respond(if (status == HttpStatusCode.OK) body else "", status, headersOf(HttpHeaders.CacheControl to listOf("private", "no-store"), HttpHeaders.ContentType to listOf("application/opds+json"))) }
            val transport = KtorOpdsTransport(engine, root)
            val cache = MemoryOpdsFeedCache()
            val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), cache)
            loader.load(key(), OpdsRequest(root)); assertNotNull(cache.load(key()))
            assertIs<OpdsLoadResult.Document>(loader.load(key(), OpdsRequest(root)))
            assertNull(cache.load(key()))
            transport.close()
        }
    }
    @Test fun malformed_documents_and_vary_star_are_not_cached() = runTest {
        for ((text, headers) in listOf("invalid" to headersOf(HttpHeaders.ContentType, "application/opds+json"), body to headersOf(HttpHeaders.ContentType to listOf("application/opds+json"), HttpHeaders.Vary to listOf("*")))) {
            val engine = MockEngine { respond(text, headers = headers) }
            val transport = KtorOpdsTransport(engine, root)
            val cache = MemoryOpdsFeedCache()
            val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), cache)
            loader.load(key(), OpdsRequest(root)); assertNull(cache.load(key()))
            transport.close()
        }
    }
    @Test fun an_unreachable_catalogue_serves_the_saved_copy_with_its_time_and_no_other_failure_does() = runTest {
        var answer: suspend MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData = { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }
        val engine = MockEngine { answer() }
        val transport = KtorOpdsTransport(engine, root)
        val cache = MemoryOpdsFeedCache()
        var now = 10L
        val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), cache, nowMillis = { now })
        val fresh = loader.load(key(), OpdsRequest(root)) as OpdsLoadResult.Document
        now = 99
        answer = { throw kotlinx.io.IOException("Unable to resolve host") }
        val saved = assertIs<OpdsLoadResult.SavedCopy>(loader.load(key(), OpdsRequest(root)))
        assertEquals(fresh.document, saved.document)
        assertEquals(10L, saved.storedAtMillis)
        assertEquals(10L, cache.load(key())?.storedAtMillis, "serving a saved copy does not make it newer")
        val other = assertIs<OpdsLoadResult.FetchFailure>(loader.load(key("$root/never-opened"), OpdsRequest("$root/never-opened")))
        assertEquals(OpdsTransportError.Code.UNREACHABLE, other.error.code)
        answer = { respond("", HttpStatusCode.InternalServerError) }
        assertEquals(OpdsTransportError.Code.SERVER_ERROR, assertIs<OpdsLoadResult.FetchFailure>(loader.load(key(), OpdsRequest(root))).error.code)
        answer = { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"b\"")) }
        assertEquals(OpdsTransportError.Code.SIGN_IN_NEEDED, assertIs<OpdsLoadResult.FetchFailure>(loader.load(key(), OpdsRequest(root))).error.code)
        transport.close()
    }
    @Test fun unexpected_304_is_not_an_empty_catalogue() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotModified) }
        val transport = KtorOpdsTransport(engine, root)
        val loader = CachedOpdsFeedLoader(transport, ParserFactory.opdsParser(), MemoryOpdsFeedCache())
        assertIs<OpdsLoadResult.NotModifiedWithoutCache>(loader.load(key(), OpdsRequest(root)))
        transport.close()
    }
}
