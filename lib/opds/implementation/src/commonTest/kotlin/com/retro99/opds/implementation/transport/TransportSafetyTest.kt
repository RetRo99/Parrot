package com.retro99.opds.implementation.transport

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransportSafetyTest {
    private val root = "https://catalogue.example.org/root"
    private fun request(url: String = root, http: Boolean = false) = OpdsRequest(url, credentials = OpdsCredentials.Anonymous, allowCleartext = http)
    private fun code(result: OpdsFetchResult) = (result as OpdsFetchResult.Failure).error.code

    @Test fun unsafe_targets_are_rejected_before_engine_access() = runTest {
        val engine = MockEngine { fail("unsafe URL reached engine") }
        val transport = KtorOpdsTransport(engine, root)
        for (url in listOf("file:///private/file", "javascript:alert(1)", "data:text/plain,feed")) assertEquals(OpdsTransportError.Code.UNSUPPORTED_SCHEME, code(transport.fetch(request(url))))
        for (url in listOf("https://user:secret@example.org/root", "https://user@example.org/root", "https://@example.org/root", "not a URL")) assertEquals(OpdsTransportError.Code.MALFORMED_URL, code(transport.fetch(request(url))))
        assertTrue(engine.requestHistory.isEmpty())
        transport.close()
    }
    @Test fun anonymous_plain_http_requires_explicit_permission() = runTest {
        val engine = MockEngine { respond("feed") }
        val transport = KtorOpdsTransport(engine, "http://home.lan/root")
        assertEquals(OpdsTransportError.Code.CLEARTEXT_NOT_ALLOWED, code(transport.fetch(request("http://home.lan/root"))))
        assertIs<OpdsFetchResult.Response>(transport.fetch(request("http://home.lan/root", true)))
        transport.close()
    }
    @Test fun redirect_targets_get_the_same_safety_checks() = runTest {
        for ((target, expected) in listOf(
            "file:///private/feed" to OpdsTransportError.Code.UNSUPPORTED_SCHEME,
            "data:text/plain,feed" to OpdsTransportError.Code.UNSUPPORTED_SCHEME,
            "javascript:alert(1)" to OpdsTransportError.Code.UNSUPPORTED_SCHEME,
            "https://user:secret@example.org/feed" to OpdsTransportError.Code.MALFORMED_URL,
            "http://catalogue.example.org/feed" to OpdsTransportError.Code.REDIRECT_SCHEME_DOWNGRADE)) {
            val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target)) }
            val transport = KtorOpdsTransport(engine, root)
            assertEquals(expected, code(transport.fetch(request(http = true))))
            assertEquals(1, engine.requestHistory.size)
            transport.close()
        }
    }
    @Test fun cross_origin_local_network_is_flagged_not_blocked() = runTest {
        for (host in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "localhost", "[::1]", "[fd00::1]")) {
            val engine = MockEngine { respond("feed") }
            val transport = KtorOpdsTransport(engine, root)
            val response = transport.fetch(request("https://$host/feed")) as OpdsFetchResult.Response
            assertTrue(response.crossOriginPrivateNetwork, host)
            assertEquals(1, engine.requestHistory.size)
            transport.close()
        }
        val engine = MockEngine { respond("feed") }
        val local = KtorOpdsTransport(engine, "https://192.168.1.1/root")
        assertFalse((local.fetch(request("https://192.168.1.1/feed")) as OpdsFetchResult.Response).crossOriginPrivateNetwork)
        local.close()
    }
    @Test fun private_network_flag_survives_redirects() = runTest {
        var hop = 0
        val engine = MockEngine { if (hop++ == 0) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://10.0.0.1/feed")) else respond("feed") }
        val transport = KtorOpdsTransport(engine, root)
        assertTrue((transport.fetch(request()) as OpdsFetchResult.Response).crossOriginPrivateNetwork)
        transport.close()
    }
    @Test fun response_budget_is_enforced_without_content_length_and_stops_stream() = runTest {
        var written = 0L
        val channel = ByteChannel()
        val writer = launch {
            try {
                val chunk = ByteArray(8192) { 65 }
                while (written < OpdsBudgets.MAX_RESPONSE_BYTES * 3) {
                    channel.writeFully(chunk)
                    channel.flush()
                    written += chunk.size
                }
            } catch (_: ClosedByteChannelException) {
                // Expected: the bounded reader closes the still-writing response stream.
                assertTrue(channel.isClosedForWrite)
            } finally { channel.close() }
        }
        val engine = MockEngine { respond(channel, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(transport.fetch(request())))
        writer.cancelAndJoin()
        assertTrue(written < OpdsBudgets.MAX_RESPONSE_BYTES * 3, "transport consumed the entire oversized body")
        transport.close()
    }
    @Test fun response_budget_accepts_exact_boundary_and_rejects_declared_oversize() = runTest {
        val engine = MockEngine { respond(ByteArray(OpdsBudgets.MAX_RESPONSE_BYTES.toInt())) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsBudgets.MAX_RESPONSE_BYTES.toInt(), (transport.fetch(request()) as OpdsFetchResult.Response).body.size)
        transport.close()
        val huge = MockEngine { respond("small", headers = headersOf(HttpHeaders.ContentLength, (OpdsBudgets.MAX_RESPONSE_BYTES + 1).toString())) }
        val other = KtorOpdsTransport(huge, root)
        assertEquals(OpdsTransportError.Code.RESPONSE_TOO_LARGE, code(other.fetch(request())))
        other.close()
    }
    @Test fun logs_never_include_credentials_paths_queries_or_search_text() = runTest {
        val logs = mutableListOf<String>()
        val engine = MockEngine { respond("feed") }
        val transport = KtorOpdsTransport(engine, root, log = { logs += it })
        transport.fetch(OpdsRequest("https://catalogue.example.org/PRIVATE_PATH?token=SECRET_QUERY&query=PRIVATE_SEARCH", credentials = OpdsCredentials.Basic("PRIVATE_USER", "PRIVATE_PASSWORD")))
        assertTrue(logs.isNotEmpty())
        for (secret in listOf("PRIVATE_PATH", "SECRET_QUERY", "PRIVATE_SEARCH", "PRIVATE_USER", "PRIVATE_PASSWORD", "Authorization", "Basic", "https://")) assertTrue(logs.none { secret in it })
        transport.close()
    }
    @Test fun cancellation_cancels_request_and_is_not_an_error_result() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val transport = KtorOpdsTransport(engine, root)
        val job = async { transport.fetch(request()) }
        started.await()
        job.cancel()
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        cancelled.await()
        transport.close()
    }
}
