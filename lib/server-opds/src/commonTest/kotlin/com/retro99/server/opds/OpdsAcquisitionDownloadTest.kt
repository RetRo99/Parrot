package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpdsAcquisitionDownloadTest {
    private class Sink : CatalogueFileSink {
        val starts = mutableListOf<Long?>()
        var bytes = ByteArray(0)
        override suspend fun start(declaredLength: Long?) { starts += declaredLength }
        override suspend fun write(buffer: ByteArray, length: Int) { bytes += buffer.copyOf(length) }
    }

    private class Setup(val repository: OpdsCatalogueRepository, val engine: MockEngine, val access: CatalogueAccessStoreImpl)

    private suspend fun setup(account: OpdsAccountDetails? = null, valid: () -> Boolean = { true }, respond: suspend MockRequestHandleScope.(HttpRequestDataLite) -> io.ktor.client.request.HttpResponseData): Setup {
        val preferences = TestPreferences()
        val access = CatalogueAccessStoreImpl(preferences)
        val credentials = OpdsCredentialStoreImpl(preferences)
        if (account != null) credentials.save("a", "source", account)
        val engine = MockEngine { request -> respond(HttpRequestDataLite(request.url.toString(), request.headers[HttpHeaders.Authorization], request.headers[HttpHeaders.Accept])) }
        return Setup(OpdsCatalogueRepository("a", SOURCE, KtorOpdsTransport(engine, ROOT), credentials, access, valid, { 10L }), engine, access)
    }

    private fun MockRequestHandleScope.respondFeed(body: String = feed()) = respond(body, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))

    private suspend fun locator(setup: Setup, choiceIndex: Int = 0): CatalogueAcquisitionLocator {
        val document = assertIs<CatalogueFeedDocument>(setup.repository.getRoot().get())
        val publication = document.publications.first()
        return assertNotNull(setup.repository.locate(document, publication, publication.acquisitionChoices[choiceIndex]))
    }

    @Test fun a_locator_names_the_listing_and_never_the_file_link() = runTest {
        val setup = setup { respondFeed() }
        val first = locator(setup, 0)
        val second = locator(setup, 2)
        assertEquals(ROOT, first.documentUrl)
        assertEquals("https://books.example/#urn:book:1", first.publicationKey)
        assertEquals("application/epub+zip#1", first.representationKey)
        assertEquals("application/epub+zip#2", second.representationKey)
        assertFalse("signature" in first.documentUrl)
        assertEquals("CatalogueAcquisitionLocator(redacted)", first.toString())
        setup.repository.dispose()
    }

    @Test fun files_that_are_not_direct_downloads_have_no_locator() = runTest {
        val setup = setup { respondFeed() }
        val document = assertIs<CatalogueFeedDocument>(setup.repository.getRoot().get())
        val publication = document.publications.first()
        val pdf = publication.acquisitionChoices.single { it.link.mediaType?.subtype == "pdf" }
        assertNull(setup.repository.locate(document, publication, pdf))
        val other = setup { respondFeed() }
        val foreign = assertIs<CatalogueFeedDocument>(other.repository.getRoot().get())
        assertNull(setup.repository.locate(foreign, foreign.publications.first(), foreign.publications.first().acquisitionChoices[0]))
        setup.repository.dispose()
        other.repository.dispose()
    }

    @Test fun the_link_is_resolved_again_right_before_the_file_is_fetched() = runTest {
        var signature = "old"
        val setup = setup { request ->
            if (request.url == ROOT) respondFeed(feed(signature)) else respond(ByteReadChannel(BOOK), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, BOOK.size.toString()))
        }
        val locator = locator(setup)
        signature = "fresh"
        val sink = Sink()
        val outcome = assertIs<CatalogueDownloadOutcome.Complete>(setup.repository.download(locator, sink))
        assertEquals(BOOK.size.toLong(), outcome.bytes)
        assertEquals(BOOK.size.toLong(), outcome.declaredLength)
        assertContentEquals(BOOK, sink.bytes)
        assertEquals(listOf<Long?>(BOOK.size.toLong()), sink.starts)
        val urls = setup.engine.requestHistory.map { it.url.toString() }
        assertEquals(listOf(ROOT, ROOT, "https://books.example/files/one.epub?signature=fresh"), urls)
        assertEquals("application/epub+zip, */*;q=0.5", setup.engine.requestHistory.last().headers[HttpHeaders.Accept])
        setup.repository.dispose()
    }

    @Test fun the_second_file_of_a_type_is_found_by_its_place_in_the_catalogue() = runTest {
        val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK)) }
        assertIs<CatalogueDownloadOutcome.Complete>(setup.repository.download(locator(setup, 2), Sink()))
        assertEquals("https://books.example/files/two.epub", setup.engine.requestHistory.last().url.toString())
        setup.repository.dispose()
    }

    @Test fun an_unknown_length_is_passed_on_as_unknown() = runTest {
        val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK)) }
        val sink = Sink()
        val outcome = assertIs<CatalogueDownloadOutcome.Complete>(setup.repository.download(locator(setup), sink))
        assertNull(outcome.declaredLength)
        assertEquals(listOf<Long?>(null), sink.starts)
        setup.repository.dispose()
    }

    @Test fun transport_results_map_to_the_four_download_failures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"b\""), CatalogueDownloadFailure.SignInNeeded),
            Triple(HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Digest realm=\"b\""), CatalogueDownloadFailure.Refused),
            Triple(HttpStatusCode.Forbidden, headersOf(), CatalogueDownloadFailure.Refused),
            Triple(HttpStatusCode.NotFound, headersOf(), CatalogueDownloadFailure.Refused),
            Triple(HttpStatusCode.InternalServerError, headersOf(), CatalogueDownloadFailure.Connection),
            Triple(HttpStatusCode.TooManyRequests, headersOf(), CatalogueDownloadFailure.Connection),
        )
        for ((status, headers, expected) in cases) {
            val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond("no", status, headers) }
            val sink = Sink()
            assertEquals(CatalogueDownloadOutcome.Failed(expected), setup.repository.download(locator(setup), sink), "$status")
            assertTrue(sink.starts.isEmpty())
            setup.repository.dispose()
        }
    }

    @Test fun a_truncated_file_and_a_dropped_connection_are_connection_failures() = runTest {
        val truncated = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "9999")) }
        assertEquals(CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Connection), truncated.repository.download(locator(truncated), Sink()))
        truncated.repository.dispose()
        val dropped = setup { request -> if (request.url == ROOT) respondFeed() else throw RuntimeException("reset") }
        assertEquals(CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Connection), dropped.repository.download(locator(dropped), Sink()))
        dropped.repository.dispose()
    }

    @Test fun a_declared_size_over_the_ceiling_is_too_large() = runTest {
        val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (512L * 1024 * 1024 + 1).toString())) }
        val sink = Sink()
        assertEquals(CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.TooLarge), setup.repository.download(locator(setup), sink))
        assertTrue(sink.starts.isEmpty())
        setup.repository.dispose()
    }

    @Test fun a_file_the_catalogue_no_longer_lists_is_refused() = runTest {
        var listed = true
        val setup = setup { request -> if (request.url == ROOT) respondFeed(if (listed) feed() else EMPTY_FEED) else error("the file must not be requested") }
        val locator = locator(setup)
        listed = false
        assertEquals(CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.Refused), setup.repository.download(locator, Sink()))
        setup.repository.dispose()
    }

    @Test fun a_listing_that_now_asks_for_sign_in_is_sign_in_needed_and_recorded() = runTest {
        var locked = false
        val setup = setup { request ->
            if (locked) respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"b\"")) else respondFeed()
        }
        val locator = locator(setup)
        locked = true
        assertEquals(CatalogueDownloadOutcome.Failed(CatalogueDownloadFailure.SignInNeeded), setup.repository.download(locator, Sink()))
        assertEquals(CatalogueErrorKind.SignInNeeded, setup.access.get("a", "source").lastCheck.lastError)
        setup.repository.dispose()
    }

    @Test fun account_details_reach_the_catalogue_but_not_a_file_host_elsewhere() = runTest {
        val setup = setup(OpdsAccountDetails("reader", "secret")) { request ->
            when {
                request.url == ROOT -> respondFeed(feed(host = "https://cdn.example.net"))
                else -> respond(ByteReadChannel(BOOK))
            }
        }
        assertIs<CatalogueDownloadOutcome.Complete>(setup.repository.download(locator(setup), Sink()))
        val history = setup.engine.requestHistory
        assertNotNull(history[1].headers[HttpHeaders.Authorization])
        assertEquals("cdn.example.net", history.last().url.host)
        assertNull(history.last().headers[HttpHeaders.Authorization])
        setup.repository.dispose()
    }

    @Test fun a_failing_sink_comes_back_as_the_sinks_own_error() = runTest {
        class DiskFull : Exception()
        val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK)) }
        val sink = object : CatalogueFileSink { override suspend fun write(buffer: ByteArray, length: Int) = throw DiskFull() }
        assertIs<DiskFull>(assertIs<CatalogueDownloadOutcome.SinkFailed>(setup.repository.download(locator(setup), sink)).cause)
        setup.repository.dispose()
    }

    @Test fun turning_the_source_off_cancels_a_download_in_flight() = runTest {
        val writing = CompletableDeferred<Unit>()
        val setup = setup { request -> if (request.url == ROOT) respondFeed() else respond(ByteReadChannel(BOOK)) }
        val locator = locator(setup)
        val sink = object : CatalogueFileSink { override suspend fun write(buffer: ByteArray, length: Int) { writing.complete(Unit); awaitCancellation() } }
        var failure: Throwable? = null
        val work = launch { try { setup.repository.download(locator, sink) } catch (error: CancellationException) { failure = error; throw error } }
        writing.await()
        setup.repository.dispose()
        work.join()
        assertIs<CancellationException>(failure)
    }

    @Test fun a_session_that_is_no_longer_current_downloads_nothing() = runTest {
        var current = true
        val setup = setup(valid = { current }) { request -> if (request.url == ROOT) respondFeed() else error("the file must not be requested") }
        val locator = locator(setup)
        current = false
        assertFailsWith<CancellationException> { setup.repository.download(locator, Sink()) }
    }

    class HttpRequestDataLite(val url: String, val authorization: String?, val accept: String?)

    companion object {
        const val ROOT = "https://books.example/opds/"
        val SOURCE = ServerConfig("source", "Books", ServerType.Opds, ROOT, 0)
        val BOOK = ByteArray(3000) { (it % 7).toByte() }
        const val EMPTY_FEED = """{"metadata":{"title":"Books"},"publications":[],"links":[]}"""
        fun feed(signature: String = "s", host: String = "https://books.example") = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[
            {"href":"$host/files/one.epub?signature=$signature","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"},
            {"href":"$host/files/book.pdf","rel":"http://opds-spec.org/acquisition/open-access","type":"application/pdf"},
            {"href":"$host/files/two.epub","rel":"http://opds-spec.org/acquisition","type":"application/epub+zip"}]}],"links":[]}"""
    }
}
