package com.retro99.opds.implementation.stress

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

/** Downloads and pages that go wrong on the way: stalls, cuts, lies about length, redirect games, throttling. */
class TransportFailureStressTest {
    private val root = "https://catalogue.example.org/opds"
    private val file = "https://catalogue.example.org/files/book.epub"
    private val account = OpdsCredentials.Basic("reader", "secret")
    private val now = 1445412480000L // Wed, 21 Oct 2015 07:28:00 GMT

    private class CountingSink : OpdsDownloadSink {
        var started = 0
        var bytes = 0L
        override suspend fun start(declaredLength: Long?) { started++ }
        override suspend fun write(buffer: ByteArray, length: Int) { bytes += length }
    }

    private fun download(url: String = file, credentials: OpdsCredentials = OpdsCredentials.Anonymous) =
        OpdsRequest(url, OPDS_DOWNLOAD_ACCEPT_MEDIA_TYPES, credentials)
    private fun code(result: OpdsDownloadResult) = assertIs<OpdsDownloadResult.Failure>(result).error.code
    private fun code(result: OpdsFetchResult) = assertIs<OpdsFetchResult.Failure>(result).error.code

    // --- a download that stalls ---------------------------------------------------------

    @Test fun a_download_has_no_deadline_for_the_whole_file_but_a_silent_connection_times_out() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(ByteArray(10))) }
        val transport = KtorOpdsTransport(engine, root)

        transport.download(download(), CountingSink())
        transport.fetch(OpdsRequest(root))

        val (fileTimeouts, pageTimeouts) = engine.requestHistory.map { assertNotNull(it.getCapabilityOrNull(HttpTimeoutCapability)) }
        // A large file may take longer than any fixed limit; 30 seconds without a byte ends it.
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, fileTimeouts.requestTimeoutMillis)
        assertEquals(30_000L, fileTimeouts.socketTimeoutMillis)
        assertEquals(15_000L, fileTimeouts.connectTimeoutMillis)
        // A page has both.
        assertEquals(30_000L, pageTimeouts.requestTimeoutMillis)
        assertEquals(30_000L, pageTimeouts.socketTimeoutMillis)
        transport.close()
    }

    @Test fun a_download_that_stalls_after_some_bytes_ends_as_a_timeout_when_the_connection_gives_up() = runTest {
        // What a platform engine does after the socket timeout: the body fails.
        val body = ByteChannel()
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000000")) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = CountingSink()
        val result = async { transport.download(download(), sink) }
        body.writeFully(ByteArray(70_000))
        body.flush()
        testScheduler.runCurrent()
        assertFalse(result.isCompleted, "it waits for the rest")

        body.close(IOException("Socket timeout has expired"))

        assertEquals(OpdsTransportError.Code.TIMEOUT, code(result.await()))
        assertTrue(sink.bytes <= 70_000)
        transport.close()
    }

    @Test fun a_stalled_download_can_be_cancelled() = runTest {
        val body = ByteChannel()
        val engine = MockEngine { respond(body) }
        val transport = KtorOpdsTransport(engine, root)
        val job = launch { transport.download(download(), CountingSink()) }
        body.writeFully(ByteArray(10))
        body.flush()
        testScheduler.runCurrent()
        assertTrue(job.isActive)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        transport.close()
    }

    // --- a download that is cut mid-file ------------------------------------------------

    @Test fun a_connection_that_drops_mid_file_is_a_network_failure_whether_or_not_a_length_was_declared() = runTest {
        for (declared in listOf<String?>("1000000", null)) {
            val body = ByteChannel()
            val headers = if (declared == null) headersOf() else headersOf(HttpHeaders.ContentLength, declared)
            val transport = KtorOpdsTransport(MockEngine { respond(body, HttpStatusCode.OK, headers) }, root)
            val sink = CountingSink()
            val result = async { transport.download(download(), sink) }
            body.writeFully(ByteArray(70_000))
            body.flush()
            testScheduler.runCurrent()

            body.close(IOException("Connection reset by peer"))

            assertEquals(OpdsTransportError.Code.UNREACHABLE, code(result.await()), "declared: $declared")
            transport.close()
        }
    }

    @Test fun a_file_that_ends_early_and_cleanly_is_a_length_mismatch_when_its_length_was_declared() = runTest {
        val transport = KtorOpdsTransport(MockEngine { respond(ByteReadChannel(ByteArray(999_999)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000000")) }, root)

        assertEquals(OpdsTransportError.Code.LENGTH_MISMATCH, code(transport.download(download(), CountingSink())))
        transport.close()
    }

    // --- a download that sends more than it declared --------------------------------------

    @Test fun more_bytes_than_declared_is_a_length_mismatch_and_the_surplus_is_never_written() = runTest {
        // One byte too many, and far too many.
        for (sent in listOf(1_001, 5_000_000)) {
            val transport = KtorOpdsTransport(MockEngine { respond(ByteReadChannel(ByteArray(sent)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) }, root)
            val sink = CountingSink()

            assertEquals(OpdsTransportError.Code.LENGTH_MISMATCH, code(transport.download(download(), sink)), "sent: $sent")

            assertTrue(sink.bytes <= 1_000, "wrote ${sink.bytes} of a file declared as 1000")
            transport.close()
        }
    }

    @Test fun an_endless_file_stops_at_the_ceiling_without_reading_on() = runTest {
        var produced = 0L
        val body = ByteChannel()
        val producer = launch {
            val chunk = ByteArray(64 * 1024)
            try {
                while (true) { body.writeFully(chunk); body.flush(); produced += chunk.size }
            } catch (_: ClosedByteChannelException) {
                // The reader closed the stream.
            } catch (_: IOException) {
                // The same, from another platform's channel.
            }
        }
        val transport = KtorOpdsTransport(MockEngine { respond(body) }, root)
        val sink = CountingSink()
        val ceiling = 1024L * 1024

        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(transport.download(download(), sink, maxBytes = ceiling)))

        // It came back although the body never ends, and nothing past the ceiling reached the file.
        // (How far the mock connection had buffered ahead says nothing about the transport.)
        producer.cancelAndJoin()
        assertTrue(sink.bytes <= ceiling)
        assertTrue(produced >= ceiling)
        transport.close()
    }

    @Test fun an_endless_page_stops_at_the_page_budget_without_reading_on() = runTest {
        var produced = 0L
        val body = ByteChannel()
        val producer = launch {
            val chunk = ByteArray(64 * 1024) { 65 }
            try {
                while (true) { body.writeFully(chunk); body.flush(); produced += chunk.size }
            } catch (_: ClosedByteChannelException) {
            } catch (_: IOException) {
            }
        }
        val transport = KtorOpdsTransport(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }, root)

        val result = transport.fetch(OpdsRequest(root))

        // It came back although the body never ends, with a failure that carries no body.
        producer.cancelAndJoin()
        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(result))
        assertTrue(produced >= OpdsBudgets.MAX_RESPONSE_BYTES)
        transport.close()
    }

    // --- redirects ----------------------------------------------------------------------

    @Test fun a_download_that_redirects_in_a_loop_stops_and_writes_nothing() = runTest {
        // Two addresses that point at each other, then a chain that never repeats.
        val pingPong = MockEngine { request ->
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, if (request.url.encodedPath.endsWith("/a")) "/files/b" else "/files/a"))
        }
        var hop = 0
        val endless = MockEngine { respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "/files/hop-${++hop}")) }

        for ((engine, expected, requests) in listOf(
            Triple(pingPong, OpdsTransportError.Code.REDIRECT_LOOP, 2),
            Triple(endless, OpdsTransportError.Code.REDIRECT_LIMIT, OpdsBudgets.MAX_REDIRECTS + 1),
        )) {
            val transport = KtorOpdsTransport(engine, root)
            val sink = CountingSink()

            assertEquals(expected, code(transport.download(download("https://catalogue.example.org/files/a", account), sink)))

            assertEquals(requests, engine.requestHistory.size)
            assertEquals(0, sink.started)
            transport.close()
        }
    }

    @Test fun a_download_redirected_off_the_catalogue_carries_no_account_details_and_never_gets_them_back() = runTest {
        // catalogue -> another host -> back to the catalogue -> the file.
        val hops = listOf(
            "https://cdn.example.net/signed/book.epub?token=abc",
            "https://catalogue.example.org/files/final.epub",
        )
        val engine = MockEngine { request ->
            when (val index = hops.indexOf(request.url.toString())) {
                -1 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, hops[0]))
                0 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, hops[index + 1]))
                else -> respond(ByteReadChannel(ByteArray(10)))
            }
        }
        val transport = KtorOpdsTransport(engine, root)

        assertIs<OpdsDownloadResult.Complete>(transport.download(download(credentials = account), CountingSink()))

        val sent = engine.requestHistory.map { it.url.host to it.headers[HttpHeaders.Authorization] }
        assertEquals(3, sent.size)
        assertNotNull(sent[0].second, "the catalogue's own address gets the account details")
        assertEquals("cdn.example.net" to null, sent[1])
        assertEquals("catalogue.example.org" to null, sent[2], "back on the catalogue's host after leaving it: still nothing")
        assertTrue(engine.requestHistory.none { it.headers[HttpHeaders.Cookie] != null })
        assertTrue(engine.requestHistory.drop(1).none { it.url.user != null || it.url.password != null })
        transport.close()
    }

    @Test fun a_redirect_to_the_same_host_on_another_port_or_over_http_carries_no_account_details() = runTest {
        for (target in listOf("https://catalogue.example.org:8443/files/book.epub", "https://sub.catalogue.example.org/files/book.epub", "https://catalogue.example.org.evil.example/book.epub")) {
            val engine = MockEngine { request ->
                if (request.url.toString() == file) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target)) else respond(ByteReadChannel(ByteArray(10)))
            }
            val transport = KtorOpdsTransport(engine, root)

            assertIs<OpdsDownloadResult.Complete>(transport.download(download(credentials = account), CountingSink()))

            assertNull(engine.requestHistory.last().headers[HttpHeaders.Authorization], target)
            transport.close()
        }
        // Down to http: refused, and the http address is never asked.
        val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://catalogue.example.org/files/book.epub")) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.REDIRECT_SCHEME_DOWNGRADE, code(transport.download(download(credentials = account), CountingSink())))
        assertEquals(1, engine.requestHistory.size)
        transport.close()
    }

    @Test fun account_details_written_into_a_link_are_refused_before_any_request() = runTest {
        val engine = MockEngine { fail("must not reach the engine") }
        val transport = KtorOpdsTransport(engine, root)

        assertEquals(OpdsTransportError.Code.MALFORMED_URL, code(transport.download(download("https://reader:secret@catalogue.example.org/files/book.epub"), CountingSink())))
        assertEquals(OpdsTransportError.Code.MALFORMED_URL, code(transport.fetch(OpdsRequest("https://reader:secret@other.example/opds"))))
        transport.close()
    }

    @Test fun an_address_with_no_host_or_an_unclosed_bracket_is_refused_and_does_not_become_localhost() = runTest {
        val engine = MockEngine { fail("must not reach the engine") }
        val transport = KtorOpdsTransport(engine, root)

        for (address in listOf("https://", "https:///opds", "https://:8443/opds", "https://[::1/opds", "https://::1]/opds", "http://", "https://?q=1", "https://#top")) {
            assertEquals(OpdsTransportError.Code.MALFORMED_URL, code(transport.fetch(OpdsRequest(address, allowCleartext = true))), address)
            assertEquals(OpdsTransportError.Code.MALFORMED_URL, code(transport.download(download(address).copy(allowCleartext = true), CountingSink())), address)
        }
        transport.close()
    }

    @Test fun bracketed_and_plain_hosts_with_and_without_a_port_are_still_asked() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(ByteArray(1))) }
        val transport = KtorOpdsTransport(engine, root)

        val addresses = listOf("https://[2001:db8::1]/opds", "https://[2001:db8::1]:8443/opds", "https://books.example:8443/opds", "https://books.example")
        addresses.forEach { address -> assertIs<OpdsFetchResult.Response>(transport.fetch(OpdsRequest(address)), address) }

        assertEquals(addresses.size, engine.requestHistory.size)
        transport.close()
    }

    // --- throttling ---------------------------------------------------------------------

    @Test fun retry_after_in_seconds_as_a_date_and_huge_is_read_safely_for_pages_and_files() = runTest {
        data class Case(val status: Int, val header: String?, val expected: Long?)
        val cases = listOf(
            Case(429, "120", 120_000L),
            Case(503, "120", 120_000L),
            Case(429, "0", 0L),
            Case(429, "Wed, 21 Oct 2015 07:30:00 GMT", 120_000L),
            Case(503, "Wed, 21 Oct 2015 07:30:00 GMT", 120_000L),
            // A date already past: now, not a negative wait.
            Case(429, "Wed, 21 Oct 2015 07:00:00 GMT", 0L),
            // Huge: the largest number of seconds that still fits is kept as it is; the caller decides.
            Case(429, (Long.MAX_VALUE / 1000).toString(), Long.MAX_VALUE / 1000 * 1000),
            // Past what fits, negative, or not a number: no value, never an overflow.
            Case(429, (Long.MAX_VALUE / 1000 + 1).toString(), null),
            Case(503, Long.MAX_VALUE.toString(), null),
            Case(503, "99999999999999999999999999999999", null),
            Case(429, "-5", null),
            Case(429, "soon", null),
            Case(503, "", null),
            Case(429, null, null),
        )
        for (case in cases) {
            val headers = if (case.header == null) headersOf() else headersOf(HttpHeaders.RetryAfter, case.header)
            val engine = MockEngine { respond("slow down", HttpStatusCode.fromValue(case.status), headers) }
            val transport = KtorOpdsTransport(engine, root, nowMillis = { now })
            val expectedCode = if (case.status == 429) OpdsTransportError.Code.RATE_LIMITED else OpdsTransportError.Code.SERVICE_UNAVAILABLE

            val page = assertIs<OpdsFetchResult.Failure>(transport.fetch(OpdsRequest(root))).error
            val sink = CountingSink()
            val fileResult = assertIs<OpdsDownloadResult.Failure>(transport.download(download(), sink)).error

            for (error in listOf(page, fileResult)) {
                assertEquals(expectedCode, error.code, "$case")
                assertEquals(case.expected, error.retryAfterMillis, "$case")
                assertTrue((error.retryAfterMillis ?: 0) >= 0, "$case")
            }
            assertEquals(0, sink.started, "the error page is never written to the file")
            // Nothing asks again by itself.
            assertEquals(2, engine.requestHistory.size)
            transport.close()
        }
    }

    @Test fun a_far_future_retry_date_gives_a_wait_that_is_large_and_not_negative() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "Fri, 31 Dec 9999 23:59:59 GMT")) }
        val transport = KtorOpdsTransport(engine, root, nowMillis = { now })

        val wait = assertIs<OpdsFetchResult.Failure>(transport.fetch(OpdsRequest(root))).error.retryAfterMillis

        assertTrue(wait == null || wait > 0, "wait: $wait")
        transport.close()
    }
}
