package com.retro99.opds.implementation.transport

import com.retro99.opds.api.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransportStatusTest {
    private val root = "https://example.org/root"
    private suspend fun status(code: Int, retry: String? = null): OpdsTransportError {
        val engine = MockEngine { respond("private failure body", HttpStatusCode.fromValue(code), if (retry == null) headersOf() else headersOf(HttpHeaders.RetryAfter, retry)) }
        val transport = KtorOpdsTransport(engine, root, nowMillis = { 1445412480000L })
        val error = (transport.fetch(OpdsRequest(root)) as OpdsFetchResult.Failure).error
        transport.close()
        return error
    }
    @Test fun forbidden() = runTest { assertEquals(OpdsTransportError.Code.FORBIDDEN, status(403).code) }
    @Test fun not_found() = runTest { assertEquals(OpdsTransportError.Code.NOT_FOUND, status(404).code) }
    @Test fun rate_limit_with_seconds() = runTest {
        val error = status(429, "120")
        assertEquals(OpdsTransportError.Code.RATE_LIMITED, error.code)
        assertEquals(120000L, error.retryAfterMillis)
    }
    @Test fun unavailable_with_http_date() = runTest {
        val error = status(503, "Wed, 21 Oct 2015 07:30:00 GMT")
        assertEquals(OpdsTransportError.Code.SERVICE_UNAVAILABLE, error.code)
        assertEquals(120000L, error.retryAfterMillis)
    }
    @Test fun past_invalid_and_overflow_retry_after_are_safe() = runTest {
        assertEquals(0L, status(503, "Wed, 21 Oct 2015 07:27:00 GMT").retryAfterMillis)
        for (value in listOf("-1", "invalid", "99999999999999999999999")) assertNull(status(429, value).retryAfterMillis)
    }
    @Test fun other_http_errors_remain_distinct() = runTest {
        assertEquals(OpdsTransportError.Code.CLIENT_ERROR, status(400).code)
        assertEquals(OpdsTransportError.Code.SERVER_ERROR, status(500).code)
    }
    private suspend fun network(error: Throwable): OpdsTransportError {
        val engine = MockEngine { throw error }
        val transport = KtorOpdsTransport(engine, root)
        val result = (transport.fetch(OpdsRequest(root)) as OpdsFetchResult.Failure).error
        assertFalse(result.toString().contains("SECRET"))
        transport.close()
        return result
    }
    @Test fun tls_failure() = runTest { assertEquals(OpdsTransportError.Code.TLS_UNTRUSTED, network(IOException("TLS handshake failed SECRET")).code) }
    @Test fun unreachable_host() = runTest { assertEquals(OpdsTransportError.Code.UNREACHABLE, network(IOException("Host unreachable SECRET")).code) }
    @Test fun timeout() = runTest { assertEquals(OpdsTransportError.Code.TIMEOUT, network(IOException("Socket timeout SECRET")).code) }
    @Test fun darwin_error_codes_are_classified_without_echoing_error_details() = runTest {
        assertEquals(OpdsTransportError.Code.TLS_UNTRUSTED, network(IOException("NSURLErrorDomain Code=-1202 SECRET")).code)
        assertEquals(OpdsTransportError.Code.TIMEOUT, network(IOException("NSURLErrorDomain Code=-1001 SECRET")).code)
    }
}
