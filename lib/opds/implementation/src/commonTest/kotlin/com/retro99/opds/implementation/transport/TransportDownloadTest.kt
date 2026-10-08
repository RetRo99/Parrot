package com.retro99.opds.implementation.transport

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransportDownloadTest {
    private val root = "https://catalogue.example.org/opds"
    private val file = "https://catalogue.example.org/files/book.epub"
    private val account = OpdsCredentials.Basic("reader", "secret")

    private class RecordingSink : OpdsDownloadSink {
        val starts = mutableListOf<Long?>()
        val chunks = mutableListOf<ByteArray>()
        val bytes: ByteArray get() = ByteArray(chunks.sumOf { it.size }).also { all ->
            var offset = 0
            chunks.forEach { chunk -> chunk.copyInto(all, offset); offset += chunk.size }
        }
        override suspend fun start(declaredLength: Long?) { starts += declaredLength }
        override suspend fun write(buffer: ByteArray, length: Int) { chunks += buffer.copyOf(length) }
    }

    private fun request(url: String = file, credentials: OpdsCredentials = OpdsCredentials.Anonymous) =
        OpdsRequest(url, OPDS_DOWNLOAD_ACCEPT_MEDIA_TYPES, credentials)
    private fun code(result: OpdsDownloadResult) = assertIs<OpdsDownloadResult.Failure>(result).error.code
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test fun the_ceiling_for_one_file_is_512_mebibytes() {
        assertEquals(512L * 1024 * 1024, OpdsBudgets.MAX_DOWNLOAD_BYTES)
        assertTrue(OpdsBudgets.MAX_DOWNLOAD_BYTES > OpdsBudgets.MAX_RESPONSE_BYTES)
    }

    @Test fun a_file_larger_than_the_feed_budget_reaches_the_sink_in_chunks() = runTest {
        val size = OpdsBudgets.MAX_RESPONSE_BYTES.toInt() + 1
        val body = payload(size)
        val engine = MockEngine { respond(ByteReadChannel(body), HttpStatusCode.OK, headers {
            append(HttpHeaders.ContentType, "application/epub+zip")
            append(HttpHeaders.ContentLength, size.toString())
        }) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        val result = assertIs<OpdsDownloadResult.Complete>(transport.download(request(), sink))
        assertEquals(size.toLong(), result.bytes)
        assertEquals(size.toLong(), result.declaredLength)
        assertEquals("application/epub+zip", result.contentType)
        assertEquals(file, result.effectiveUrl)
        assertContentEquals(body, sink.bytes)
        assertEquals(listOf<Long?>(size.toLong()), sink.starts)
        assertTrue(sink.chunks.size > 1, "streamed, not delivered whole")
        assertEquals("application/epub+zip, */*;q=0.5", engine.requestHistory.single().headers[HttpHeaders.Accept])
        transport.close()
    }

    @Test fun a_missing_content_length_is_normal() = runTest {
        val body = payload(70_000)
        val engine = MockEngine { respond(ByteReadChannel(body)) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        val result = assertIs<OpdsDownloadResult.Complete>(transport.download(request(), sink))
        assertNull(result.declaredLength)
        assertEquals(70_000L, result.bytes)
        assertEquals(listOf<Long?>(null), sink.starts)
        assertContentEquals(body, sink.bytes)
        transport.close()
    }

    @Test fun fewer_bytes_than_declared_is_a_length_mismatch() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(payload(1_000)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "4000")) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        assertEquals(OpdsTransportError.Code.LENGTH_MISMATCH, code(transport.download(request(), sink)))
        assertEquals(listOf<Long?>(4_000L), sink.starts)
        transport.close()
    }

    @Test fun a_declared_length_over_the_ceiling_stops_before_any_byte() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(payload(10)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5000")) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(transport.download(request(), sink, maxBytes = 4_999)))
        assertTrue(sink.starts.isEmpty())
        assertTrue(sink.chunks.isEmpty())
        transport.close()
    }

    @Test fun an_undeclared_file_over_the_ceiling_stops_at_the_ceiling() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(payload(200_000))) }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(transport.download(request(), sink, maxBytes = 100_000)))
        assertTrue(sink.bytes.size <= 100_000, "nothing past the ceiling is written")
        transport.close()
    }

    @Test fun a_file_exactly_at_the_ceiling_is_accepted() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(payload(100_000))) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(100_000L, assertIs<OpdsDownloadResult.Complete>(transport.download(request(), RecordingSink(), maxBytes = 100_000)).bytes)
        transport.close()
    }

    @Test fun statuses_map_to_the_same_codes_as_feeds() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"books\""), OpdsTransportError.Code.SIGN_IN_NEEDED),
            Triple(HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Digest realm=\"books\""), OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED),
            Triple(HttpStatusCode.Forbidden, headersOf(), OpdsTransportError.Code.FORBIDDEN),
            Triple(HttpStatusCode.NotFound, headersOf(), OpdsTransportError.Code.NOT_FOUND),
            Triple(HttpStatusCode.InternalServerError, headersOf(), OpdsTransportError.Code.SERVER_ERROR),
        )
        for ((status, headers, expected) in cases) {
            val engine = MockEngine { respond("<html>no</html>", status, headers) }
            val transport = KtorOpdsTransport(engine, root)
            val sink = RecordingSink()
            val failure = assertIs<OpdsDownloadResult.Failure>(transport.download(request(), sink))
            assertEquals(expected, failure.error.code)
            assertEquals(status.value, failure.error.status)
            assertTrue(sink.starts.isEmpty(), "an error page is never written to the file")
            transport.close()
        }
    }

    @Test fun credentials_go_to_the_configured_https_origin_only() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "catalogue.example.org") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.net/signed/book.epub?token=abc"))
            else respond(ByteReadChannel(payload(10)))
        }
        val transport = KtorOpdsTransport(engine, root)
        val sink = RecordingSink()
        val result = assertIs<OpdsDownloadResult.Complete>(transport.download(request(credentials = account), sink))
        assertEquals("https://cdn.example.net/signed/book.epub?token=abc", result.effectiveUrl)
        assertEquals(2, engine.requestHistory.size)
        assertNotNull(engine.requestHistory[0].headers[HttpHeaders.Authorization])
        assertNull(engine.requestHistory[1].headers[HttpHeaders.Authorization])
        assertEquals(1, sink.starts.size, "only the final response starts the file")
        transport.close()
    }

    @Test fun a_link_on_another_origin_gets_no_credentials() = runTest {
        val engine = MockEngine { respond(ByteReadChannel(payload(10))) }
        val transport = KtorOpdsTransport(engine, root)
        assertIs<OpdsDownloadResult.Complete>(transport.download(request("https://files.example.net/book.epub", account), RecordingSink()))
        assertNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
        transport.close()
    }

    @Test fun a_password_is_never_sent_over_http() = runTest {
        val engine = MockEngine { fail("must not reach the engine") }
        val transport = KtorOpdsTransport(engine, "http://home.lan/opds")
        assertEquals(OpdsTransportError.Code.PASSWORD_OVER_HTTP, code(transport.download(request("http://home.lan/book.epub", account), RecordingSink())))
        transport.close()
    }

    @Test fun redirects_keep_their_safety_rules() = runTest {
        for ((target, expected) in listOf(
            "http://catalogue.example.org/book.epub" to OpdsTransportError.Code.REDIRECT_SCHEME_DOWNGRADE,
            "file:///private/book.epub" to OpdsTransportError.Code.UNSUPPORTED_SCHEME,
        )) {
            val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target)) }
            val transport = KtorOpdsTransport(engine, root)
            assertEquals(expected, code(transport.download(request(), RecordingSink())))
            transport.close()
        }
        val loop = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, file)) }
        val transport = KtorOpdsTransport(loop, root)
        assertEquals(OpdsTransportError.Code.REDIRECT_LOOP, code(transport.download(request(), RecordingSink())))
        transport.close()
    }

    @Test fun a_failing_sink_is_reported_as_the_sinks_own_error() = runTest {
        class DiskFull : Exception("no space")
        val engine = MockEngine { respond(ByteReadChannel(payload(200_000))) }
        val transport = KtorOpdsTransport(engine, root)
        var written = 0
        val sink = object : OpdsDownloadSink {
            override suspend fun write(buffer: ByteArray, length: Int) { if (written > 0) throw DiskFull(); written += length }
        }
        assertIs<DiskFull>(assertIs<OpdsDownloadResult.SinkFailure>(transport.download(request(), sink)).cause)
        val refusing = object : OpdsDownloadSink {
            override suspend fun start(declaredLength: Long?) { throw DiskFull() }
            override suspend fun write(buffer: ByteArray, length: Int) = fail("nothing is written after start refused")
        }
        assertIs<DiskFull>(assertIs<OpdsDownloadResult.SinkFailure>(transport.download(request(), refusing)).cause)
        transport.close()
    }

    @Test fun a_network_failure_is_a_network_error() = runTest {
        val engine = MockEngine { throw RuntimeException("connection reset") }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.UNREACHABLE, code(transport.download(request(), RecordingSink())))
        transport.close()
    }

    @Test fun cancellation_propagates_from_engine_and_sink() = runTest {
        val entered = CompletableDeferred<Unit>()
        val engine = MockEngine { entered.complete(Unit); awaitCancellation() }
        val transport = KtorOpdsTransport(engine, root)
        val job = launch { transport.download(request(), RecordingSink()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        transport.close()

        val writing = CompletableDeferred<Unit>()
        val second = KtorOpdsTransport(MockEngine { respond(ByteReadChannel(payload(10))) }, root)
        val sink = object : OpdsDownloadSink {
            override suspend fun write(buffer: ByteArray, length: Int) { writing.complete(Unit); awaitCancellation() }
        }
        val sinkJob = launch { second.download(request(), sink) }
        writing.await()
        sinkJob.cancelAndJoin()
        assertTrue(sinkJob.isCancelled)
        second.close()
    }
}
