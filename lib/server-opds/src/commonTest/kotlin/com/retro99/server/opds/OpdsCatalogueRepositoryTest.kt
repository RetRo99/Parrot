package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import com.retro99.preferences.api.*
import com.retro99.opds.api.*
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpdsCatalogueRepositoryTest {
    @Test fun uri_template_search_expands_before_resolution_and_foreign_targets_are_rejected() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
        }
        val first = repository(KtorOpdsTransport(engine, ROOT), preferences, checks)
        val feed = assertIs<CatalogueFeedDocument>(first.getRoot().get())
        val search = assertNotNull(first.discoverSearch(feed).get())
        assertTrue(first.search(search, CatalogueQuery("a & b")).isOk)
        assertEquals("https://books.example/opds/?query=a%20%26%20b", urls.last())
        val second = repository(object : OpdsTransport {
            override suspend fun fetch(request: OpdsRequest): OpdsFetchResult = error("Foreign references must not fetch")
            override suspend fun download(request: OpdsRequest, sink: OpdsDownloadSink, maxBytes: Long): OpdsDownloadResult = error("Foreign references must not fetch")
            override fun close() {}
        }, preferences, checks)
        assertTrue(second.search(search, CatalogueQuery("secret")).isErr)
        assertTrue(second.getDocument(feed.navigation.single().links.single().target!!).isErr)
        first.dispose()
        second.dispose()
    }

    @Test fun open_search_is_lazy_cached_and_disposed_with_the_source() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        var descriptorRequests = 0
        val urls = mutableListOf<String>()
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:feed</id><title>Books</title><link rel="search" href="search.xml" type="application/opensearchdescription+xml"/></feed>"""
        val descriptor = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>Books</ShortName><Url type="application/atom+xml" template="find?q={searchTerms}"/></OpenSearchDescription>"""
        val engine = MockEngine { request ->
            urls += request.url.toString()
            if (request.url.encodedPath.endsWith("search.xml")) {
                descriptorRequests++
                respond(descriptor, headers = headersOf(HttpHeaders.ContentType, "application/opensearchdescription+xml"))
            } else respond(xml, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        }
        val repository = repository(KtorOpdsTransport(engine, ROOT), preferences, checks)
        val feed = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        assertEquals(0, descriptorRequests)
        val search = assertNotNull(repository.discoverSearch(feed).get())
        assertNotNull(repository.discoverSearch(feed).get())
        assertEquals(1, descriptorRequests)
        assertTrue(repository.search(search, CatalogueQuery("a & b")).isOk)
        assertEquals("https://books.example/opds/find?q=a%20%26%20b", urls.last())
        repository.dispose()
        assertFailsWith<CancellationException> { repository.search(search, CatalogueQuery("book")) }
    }

    @Test fun anonymous_root_maps_metadata_navigation_publications_and_cache_revalidation() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertNull(request.headers[HttpHeaders.Authorization])
            if (calls == 1) respond(FEED, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/opds+json"), HttpHeaders.ETag to listOf("v1")))
            else {
                assertEquals("v1", request.headers[HttpHeaders.IfNoneMatch])
                respond("", HttpStatusCode.NotModified)
            }
        }
        val repository = repository(KtorOpdsTransport(engine, ROOT), preferences, checks)
        val feed = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        assertEquals("Books", feed.metadata.title.translations["und"])
        assertEquals(1, feed.navigation.size)
        assertEquals(1, feed.publications.size)
        val book = feed.publications.single()
        assertEquals("Book", book.title.translations["und"])
        assertEquals(2, book.acquisitionChoices.size)
        assertEquals(listOf(true, false), book.acquisitionChoices.map { it.isDefault })
        assertEquals("en", book.languages.single())
        assertEquals("Rights", book.rights?.translations?.get("und"))
        assertNotNull(book.links.first().target)
        assertFalse(feed.fetchStatus.fromCache)
        assertTrue(assertIs<CatalogueFeedDocument>(repository.getRoot().get()).fetchStatus.fromCache)
        assertEquals(ServerAccessState.Public, checks.get("a", "source").access)
        assertEquals(10L, checks.get("a", "source").lastCheck.lastSuccessAt)
        repository.dispose()
    }

    @Test fun root_401_is_distinct_from_child_401_and_public_access_never_uses_a_token() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        var deny = false
        val engine = MockEngine { if (deny) respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=books")) else respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }
        val repository = repository(KtorOpdsTransport(engine, ROOT), preferences, checks)
        val feed = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        deny = true
        assertTrue(repository.getDocument(feed.navigation.single().links.single().target!!).isErr)
        assertFalse(checks.get("a", "source").rootAnswered401)
        assertTrue(repository.getRoot().isErr)
        assertTrue(checks.get("a", "source").rootAnswered401)
        assertEquals(ServerAccessState.SignInNeeded, checks.get("a", "source").access)
        repository.dispose()
    }

    @Test fun basic_empty_password_uses_typed_store_and_profile_fence_prevents_requests() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        val accounts = OpdsCredentialStoreImpl(preferences)
        accounts.save("a", "source", OpdsAccountDetails("patron", ""))
        var active = true
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("Basic cGF0cm9uOg==", request.headers[HttpHeaders.Authorization])
            respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
        }
        val repository = repository(KtorOpdsTransport(engine, ROOT), preferences, checks, { active })
        assertTrue(repository.getRoot().isOk)
        assertEquals(ServerAccessState.SignedIn("patron"), checks.get("a", "source").access)
        active = false
        assertFailsWith<CancellationException> { repository.getRoot() }
        assertEquals(1, calls)
        repository.dispose()
    }

    @Test fun disposal_cancels_real_work_without_recording_a_network_error() = runTest {
        val preferences = TestPreferences()
        val checks = CatalogueAccessStoreImpl(preferences)
        val entered = CompletableDeferred<Unit>()
        val transport = object : OpdsTransport {
            override suspend fun fetch(request: OpdsRequest): OpdsFetchResult { entered.complete(Unit); awaitCancellation() }
            override suspend fun download(request: OpdsRequest, sink: OpdsDownloadSink, maxBytes: Long): OpdsDownloadResult = awaitCancellation()
            override fun close() {}
        }
        val repository = repository(transport, preferences, checks)
        val work = launch { repository.getRoot() }
        entered.await()
        repository.dispose()
        work.join()
        assertTrue(work.isCancelled)
        assertEquals(CatalogueAccessStatus(), checks.get("a", "source"))
    }

    private fun repository(transport: OpdsTransport, preferences: Preferences, checks: CatalogueAccessStore, valid: () -> Boolean = { true }) =
        OpdsCatalogueRepository("a", SOURCE, transport, OpdsCredentialStoreImpl(preferences), checks, valid, { 10L })

    companion object {
        const val ROOT = "https://books.example/opds/"
        val SOURCE = ServerConfig("source", "Books", ServerType.Opds, ROOT, 0)
        const val FEED = """{"metadata":{"title":"Books"},"navigation":[{"href":"child","title":"Folder","type":"application/opds+json"}],"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en","rights":"Rights"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"},{"href":"two","rel":"download","type":"application/epub+zip"}]}],"links":[{"href":"{?query}","rel":"search","type":"application/opds+json","templated":true}]}"""
    }
}

internal class TestPreferences : Preferences {
    private val values = mutableMapOf<String, String>()
    override fun getStringOrNull(key: PreferencesKey) = values[key.name]
    override fun putString(key: PreferencesKey, value: String) { values[key.name] = value }
    override fun observeStringOrNull(key: PreferencesKey) = flowOf(getStringOrNull(key))
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = error("unused")
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean) = flowOf(defaultValue)
    override fun getLong(key: PreferencesKey, defaultValue: Long) = defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = error("unused")
    override fun remove(key: PreferencesKey) { values.remove(key.name) }
}
