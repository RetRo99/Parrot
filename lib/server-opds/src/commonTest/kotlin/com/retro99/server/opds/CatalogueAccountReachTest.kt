package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Security review, question 1: with account details saved, every way a catalogue can name
 * another address. The details are sent to the catalogue's own https scheme, host and port and
 * to nothing else.
 */
class CatalogueAccountReachTest {
    private val account = OpdsAccountDetails("patron", "secret")
    private val basic = "Basic cGF0cm9uOnNlY3JldA=="
    private val seen = mutableListOf<HttpRequestData>()

    private fun feed(search: String = "https://elsewhere.example/find{?query}") = """{"metadata":{"title":"Books"},
        "navigation":[
          {"href":"child","title":"Here","type":"application/opds+json"},
          {"href":"https://elsewhere.example/opds","title":"Elsewhere","type":"application/opds+json"},
          {"href":"https://books.example:8443/opds","title":"Other port","type":"application/opds+json"},
          {"href":"https://books.example.elsewhere.example/opds","title":"Lookalike","type":"application/opds+json"}],
        "publications":[{"metadata":{"title":"Book","identifier":"urn:book:1"},
          "links":[{"href":"https://files.elsewhere.example/one.epub","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}],
        "links":[{"href":"$search","rel":"search","type":"application/opds+json","templated":true}]}"""

    private suspend fun repository(
        config: ServerConfig = ServerConfig("source", "Books", ServerType.Opds, ROOT, 0),
        body: String = feed(),
    ): OpdsCatalogueRepository {
        val preferences = TestPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        credentials.save("a", config.id, account)
        val engine = MockEngine { request ->
            seen += request
            if (request.url.encodedPath.endsWith(".epub")) respond(ByteReadChannel(ByteArray(10)))
            else respond(body, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
        }
        return OpdsCatalogueRepository("a", config, KtorOpdsTransport(engine, config.baseUrl), credentials, CatalogueAccessStoreImpl(preferences), { true }, { 10L })
    }

    private fun sent() = seen.map { it.url.toString() to it.headers[HttpHeaders.Authorization] }
    private fun assertNothingLeaked() {
        for (request in seen) {
            val own = request.url.protocol.name == "https" && request.url.host == "books.example" && request.url.port == 443
            if (!own) assertNull(request.headers[HttpHeaders.Authorization], "account details sent to ${request.url.host}:${request.url.port}")
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.url.user)
            assertFalse("secret" in request.url.toString() || "patron" in request.url.toString())
        }
    }

    @Test fun a_link_in_a_feed_to_another_host_port_or_lookalike_host_gets_nothing() = runTest {
        val repository = repository()
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())

        root.navigation.forEach { entry -> assertTrue(repository.getDocument(entry.links.single().target!!).isOk) }

        assertEquals(
            listOf(
                ROOT to basic,
                "https://books.example/opds/child" to basic,
                "https://elsewhere.example/opds" to null,
                "https://books.example:8443/opds" to null,
                "https://books.example.elsewhere.example/opds" to null,
            ),
            sent(),
        )
        assertNothingLeaked()
    }

    @Test fun a_search_address_on_another_host_gets_the_search_text_and_no_account_details() = runTest {
        val repository = repository()
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val search = assertNotNull(repository.discoverSearch(root).get())

        repository.search(search, CatalogueQuery("moby dick"))

        assertEquals("https://elsewhere.example/find?query=moby%20dick" to null, sent().last())
        assertNothingLeaked()
    }

    @Test fun a_search_address_on_the_catalogue_itself_gets_the_account_details() = runTest {
        val repository = repository(body = feed(search = "/opds/find{?query}"))
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())

        repository.search(assertNotNull(repository.discoverSearch(root).get()), CatalogueQuery("moby"))

        assertEquals("https://books.example/opds/find?query=moby" to basic, sent().last())
    }

    @Test fun a_presets_search_address_on_another_host_gets_no_account_details() = runTest {
        // A preset moved by hand to another server keeps no search address (the registry clears
        // it); this is the transport's own answer should one ever be stored.
        val config = ServerConfig("source", "Books", ServerType.Opds, ROOT, 0, searchTemplate = "https://preset.example/search?query={searchTerms}")
        val repository = repository(config)
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())

        repository.search(assertNotNull(repository.discoverSearch(root).get()), CatalogueQuery("moby"))

        assertEquals("https://preset.example/search?query=moby" to null, sent().last())
        assertNothingLeaked()
    }

    @Test fun a_search_description_on_another_host_and_the_template_it_names_get_nothing() = runTest {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:feed</id><title>Books</title><link rel="search" href="https://elsewhere.example/osd.xml" type="application/opensearchdescription+xml"/></feed>"""
        val description = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>Books</ShortName><Url type="application/atom+xml" template="https://third.example/find?q={searchTerms}"/></OpenSearchDescription>"""
        val preferences = TestPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        credentials.save("a", "source", account)
        val engine = MockEngine { request ->
            seen += request
            if (request.url.encodedPath.endsWith("osd.xml")) respond(description, headers = headersOf(HttpHeaders.ContentType, "application/opensearchdescription+xml"))
            else respond(atom, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        }
        val repository = OpdsCatalogueRepository("a", ServerConfig("source", "Books", ServerType.Opds, ROOT, 0), KtorOpdsTransport(engine, ROOT), credentials, CatalogueAccessStoreImpl(preferences), { true }, { 10L })
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())

        repository.search(assertNotNull(repository.discoverSearch(root).get()), CatalogueQuery("moby"))

        assertEquals(listOf(ROOT to basic, "https://elsewhere.example/osd.xml" to null, "https://third.example/find?q=moby" to null), sent())
        assertNothingLeaked()
    }

    @Test fun a_file_on_another_host_is_fetched_without_account_details_and_its_listing_with_them() = runTest {
        val repository = repository()
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val book = root.publications.single()
        val locator = assertNotNull(repository.locate(root, book, book.acquisitionChoices.single()))
        seen.clear()

        val outcome = repository.download(locator, object : CatalogueFileSink {
            override suspend fun write(buffer: ByteArray, length: Int) = Unit
        })

        assertIs<CatalogueDownloadOutcome.Complete>(outcome)
        assertEquals(listOf(ROOT to basic, "https://files.elsewhere.example/one.epub" to null), sent())
        assertNothingLeaked()
    }

    @Test fun a_catalogue_over_http_never_sends_account_details_to_anything() = runTest {
        val config = ServerConfig("source", "Books", ServerType.Opds, "http://books.example/opds/", 0)
        val repository = repository(config)

        assertTrue(repository.getRoot().isErr)
        assertNull(repository.loadImage("http://books.example/covers/1.png"))
        // The same host over https is another origin for an http catalogue: asked, without details.
        assertNotNull(repository.loadImage("https://books.example/covers/1.png"))

        assertTrue(seen.all { it.headers[HttpHeaders.Authorization] == null })
        assertTrue(seen.none { it.url.protocol.name == "http" }, "nothing went out over http while a password was set")
    }

    @Test fun account_details_never_appear_in_what_the_session_or_its_models_print() = runTest {
        val repository = repository()
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val search = assertNotNull(repository.discoverSearch(root).get())

        val printed = listOf(account.toString(), root.context.toString(), search.toString(), CatalogueImageModel("source", "https://books.example/key-1234/cover.png").toString())

        printed.forEach { text ->
            assertFalse("secret" in text || "patron" in text || "books.example" in text, text)
        }
    }

    private companion object {
        const val ROOT = "https://books.example/opds/"
    }
}
