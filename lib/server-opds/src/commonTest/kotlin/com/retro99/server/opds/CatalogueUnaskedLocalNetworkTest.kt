package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Found in the security read-through: a list asks for its pictures by itself, so a catalogue
 * on the internet could make Parrot send requests to devices on the user's own network. The
 * same held for a search description and for the file behind a download link. Pages already
 * ask first ("Open a device on your network?").
 */
class CatalogueUnaskedLocalNetworkTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val seen = mutableListOf<String>()
    private val local = listOf(
        "https://192.168.1.1/admin/reboot.png",
        "https://10.0.0.7/cover.png",
        "https://127.0.0.1:8443/cover.png",
        "https://localhost/cover.png",
        "https://printer.local/cover.png",
        "https://nas.lan/cover.png",
        "https://[::1]/cover.png",
        "https://[fd00::1]/cover.png",
        "https://[::ffff:192.168.1.1]/cover.png",
    )

    private fun repository(
        root: String,
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond(png) },
    ): OpdsCatalogueRepository {
        val preferences = TestPreferences()
        val engine = MockEngine { request -> seen += request.url.toString(); respond(request) }
        return OpdsCatalogueRepository("a", ServerConfig("source", "Books", ServerType.Opds, root, 0), KtorOpdsTransport(engine, root),
            OpdsCredentialStoreImpl(preferences), CatalogueAccessStoreImpl(preferences), { true }, { 10L })
    }

    @Test fun a_catalogue_on_the_internet_gets_no_picture_from_a_device_on_the_local_network() = runTest {
        val repository = repository("https://books.example/opds/")

        local.forEach { address -> assertNull(repository.loadImage(address), address) }

        assertEquals(emptyList(), seen, "no local device was contacted")
        assertContentEquals(png, repository.loadImage("https://cdn.example/cover.png"))
    }

    @Test fun a_picture_that_redirects_to_a_device_on_the_local_network_stops_before_that_device() = runTest {
        val repository = repository("https://books.example/opds/") { request ->
            if (request.url.host == "cdn.example") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://192.168.1.1/admin/reboot")) else respond(png)
        }

        assertNull(repository.loadImage("https://cdn.example/cover.png"))

        assertEquals(listOf("https://cdn.example/cover.png"), seen)
    }

    @Test fun a_catalogue_that_is_itself_on_the_local_network_keeps_its_pictures() = runTest {
        // Its own address, and another device next to it: the user put this catalogue there.
        val repository = repository("http://192.168.1.20:8080/opds/")

        assertContentEquals(png, repository.loadImage("http://192.168.1.20:8080/covers/1.png"))
        assertContentEquals(png, repository.loadImage("http://192.168.1.21/covers/1.png"))
    }

    @Test fun a_file_link_to_a_device_on_the_local_network_is_refused_for_a_catalogue_on_the_internet() = runTest {
        val feed = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1"},"links":[{"href":"https://192.168.1.1/cgi-bin/factory-reset","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""
        val repository = repository("https://books.example/opds/") { request ->
            if (request.url.host == "books.example") respond(feed, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) else respond(ByteReadChannel(ByteArray(10)))
        }
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val book = root.publications.single()
        val locator = assertNotNull(repository.locate(root, book, book.acquisitionChoices.single()))
        var written = 0

        val outcome = repository.download(locator, object : CatalogueFileSink {
            override suspend fun write(buffer: ByteArray, length: Int) { written += length }
        })

        assertEquals(CatalogueDownloadFailure.Refused, assertIs<CatalogueDownloadOutcome.Failed>(outcome).kind)
        assertEquals(0, written)
        assertTrue(seen.none { "192.168.1.1" in it }, seen.toString())
    }

    @Test fun a_search_description_on_the_local_network_is_not_fetched_for_a_catalogue_on_the_internet() = runTest {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:feed</id><title>Books</title><link rel="search" href="https://192.168.1.1/osd.xml" type="application/opensearchdescription+xml"/></feed>"""
        val repository = repository("https://books.example/opds/") { respond(atom, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml")) }
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())

        assertTrue(repository.discoverSearch(root).isErr)

        assertEquals(listOf("https://books.example/opds/"), seen)
    }
}
