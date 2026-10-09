package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.base.server.ServerType
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import com.retro99.opds.api.*
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueSearchTemplateTest {
    private val root = "https://www.gutenberg.org/ebooks.opds/"
    private val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:feed</id><title>Project Gutenberg</title><link rel="search" type="application/opensearchdescription+xml" href="https://www.gutenberg.org/catalog/osd-books.xml"/></feed>"""

    private fun repository(config: ServerConfig, urls: MutableList<String>): OpdsCatalogueRepository {
        val preferences = TestPreferences()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            if (request.url.encodedPath.endsWith("osd-books.xml")) respond(GUTENBERG_SEARCH_DESCRIPTION, headers = headersOf(HttpHeaders.ContentType, "application/opensearchdescription+xml"))
            else respond(feed, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
        }
        return OpdsCatalogueRepository("profile", config, KtorOpdsTransport(engine, config.baseUrl), OpdsCredentialStoreImpl(preferences), CatalogueAccessStoreImpl(preferences), { true }, { 10L })
    }

    @Test fun anHttpsCatalogueNeverSearchesOverHttp() = runTest {
        val urls = mutableListOf<String>()
        val repository = repository(ServerConfig("gutenberg", "Gutenberg", ServerType.Opds, root, 0), urls)
        val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        // Gutenberg advertises only http templates, so there is nothing safe to use.
        assertNull(repository.discoverSearch(document).get())
        assertTrue(urls.none { it.contains("search") && it.contains("query") })
        repository.dispose()
    }

    @Test fun theCataloguesOwnSearchAddressReplacesTheAdvertisedOne() = runTest {
        val urls = mutableListOf<String>()
        val config = ServerConfig("gutenberg", "Gutenberg", ServerType.Opds, root, 0, searchTemplate = "https://www.gutenberg.org/ebooks/search.opds/?query={searchTerms}")
        val repository = repository(config, urls)
        val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val search = assertNotNull(repository.discoverSearch(document).get())
        repository.search(search, CatalogueQuery("moby dick"))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=moby%20dick", urls.last())
        // The advertised description is not even fetched.
        assertTrue(urls.none { it.endsWith("osd-books.xml") })
        repository.dispose()
    }

    @Test fun anHttpCatalogueKeepsItsHttpSearch() = runTest {
        val urls = mutableListOf<String>()
        val config = ServerConfig("local", "Local", ServerType.Opds, "http://www.gutenberg.org/ebooks.opds/", 0)
        val repository = repository(config, urls)
        val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        assertNotNull(repository.discoverSearch(document).get())
        repository.dispose()
    }
}
