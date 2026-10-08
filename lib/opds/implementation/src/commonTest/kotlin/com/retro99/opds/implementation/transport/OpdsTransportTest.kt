package com.retro99.opds.implementation.transport

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpdsTransportTest {
    private val root = "https://catalogue.example.org/root"
    private val basic = OpdsCredentials.Basic("reader", "")
    private fun request(url: String = root, credentials: OpdsCredentials = basic, http: Boolean = false) =
        OpdsRequest(url, credentials = credentials, allowCleartext = http)
    private fun failure(result: OpdsFetchResult) = (result as OpdsFetchResult.Failure).error

    @Test fun negotiates_both_versions_and_publications_with_empty_basic_password() = runTest {
        val engine = MockEngine { respond("feed", headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }
        val transport = KtorOpdsTransport(engine, root)
        val response = transport.fetch(request()) as OpdsFetchResult.Response
        assertEquals(root, response.effectiveUrl)
        assertEquals("feed", response.body.decodeToString())
        val headers = engine.requestHistory.single().headers
        assertEquals("Basic cmVhZGVyOg==", headers[HttpHeaders.Authorization])
        for (type in listOf("application/opds+json", "application/opds-publication+json", "application/atom+xml")) assertTrue(headers[HttpHeaders.Accept].orEmpty().contains(type))
        transport.close()
    }
    @Test fun cross_origin_cover_and_download_never_receive_credentials() = runTest {
        val engine = MockEngine { respond("") }
        val transport = KtorOpdsTransport(engine, root)
        for (url in listOf("https://covers.example.org/cover", "https://files.example.org/book", "https://catalogue.example.org:8443/root")) {
            assertIs<OpdsFetchResult.Response>(transport.fetch(request(url)))
        }
        assertTrue(engine.requestHistory.all { it.headers[HttpHeaders.Authorization] == null })
        transport.close()
    }
    @Test fun credentials_are_absent_on_all_redirect_hops_including_return_to_origin() = runTest {
        var hop = 0
        val engine = MockEngine { when (hop++) {
            0 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example.org/next"))
            1 -> respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "$root?final"))
            else -> respond("done")
        } }
        val transport = KtorOpdsTransport(engine, root)
        val response = transport.fetch(request()) as OpdsFetchResult.Response
        assertEquals("$root?final", response.effectiveUrl)
        assertEquals(3, engine.requestHistory.size)
        assertNotNull(engine.requestHistory.first().headers[HttpHeaders.Authorization])
        assertTrue(engine.requestHistory.drop(1).all { it.headers[HttpHeaders.Authorization] == null })
        transport.close()
    }
    @Test fun five_redirects_allowed_but_sixth_refused() = runTest {
        for (redirects in listOf(5, 6)) {
            var hop = 0
            val engine = MockEngine { if (hop++ < redirects) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/hop/$hop")) else respond("done") }
            val transport = KtorOpdsTransport(engine, root)
            val result = transport.fetch(request())
            if (redirects == 5) assertIs<OpdsFetchResult.Response>(result) else assertEquals(OpdsTransportError.Code.REDIRECT_LIMIT, failure(result).code)
            assertEquals(OpdsBudgets.MAX_REDIRECTS + 1, engine.requestHistory.size)
            transport.close()
        }
    }
    @Test fun redirect_loop_is_a_distinct_error() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, root)) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.REDIRECT_LOOP, failure(transport.fetch(request())).code)
        assertEquals(1, engine.requestHistory.size)
        transport.close()
    }
    @Test fun same_host_different_scheme_cannot_receive_password() = runTest {
        val engine = MockEngine { fail("must not issue an HTTP request with a password") }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.PASSWORD_OVER_HTTP, failure(transport.fetch(request("http://catalogue.example.org/root", http = true))).code)
        assertTrue(engine.requestHistory.isEmpty())
        transport.close()
    }
    @Test fun basic_401_needs_sign_in_and_tracks_root_context() = runTest {
        val engine = MockEngine { respond("private auth body", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"private, realm\"")) }
        val transport = KtorOpdsTransport(engine, root)
        val rootError = failure(transport.fetch(request(credentials = OpdsCredentials.Anonymous)))
        assertEquals(OpdsTransportError.Code.SIGN_IN_NEEDED, rootError.code)
        assertTrue(rootError.isCatalogueRoot)
        assertFalse(failure(transport.fetch(request("https://catalogue.example.org/section"))).isCatalogueRoot)
        transport.close()
    }
    @Test fun unsupported_challenges_never_trigger_auth_document_discovery() = runTest {
        for (challenge in listOf("Digest realm=\"private\"", "Bearer", "SomethingElse", "")) {
            val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, challenge)) }
            val transport = KtorOpdsTransport(engine, root)
            assertEquals(OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED, failure(transport.fetch(request())).code)
            assertEquals(1, engine.requestHistory.size)
            transport.close()
        }
    }
    @Test fun basic_challenge_is_recognized_among_multiple_challenges_not_inside_realm() = runTest {
        for ((challenge, expected) in listOf(
            "Digest realm=\"Basic\", nonce=\"x\"" to OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED,
            "Digest realm=\"private, Basic\", Basic realm=\"other\"" to OpdsTransportError.Code.SIGN_IN_NEEDED)) {
            val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, challenge)) }
            val transport = KtorOpdsTransport(engine, root)
            assertEquals(expected, failure(transport.fetch(request())).code)
            transport.close()
        }
    }
    @Test fun source_clients_never_share_cookies_or_default_auth() = runTest {
        val engineA = MockEngine { respond("", headers = headersOf(HttpHeaders.SetCookie, "session=SECRET; Path=/")) }
        val engineB = MockEngine { respond("") }
        val a = KtorOpdsTransport(engineA, root)
        val b = KtorOpdsTransport(engineB, root)
        a.fetch(request())
        b.fetch(request(credentials = OpdsCredentials.Anonymous))
        a.fetch(request(credentials = OpdsCredentials.Anonymous))
        assertNull(engineB.requestHistory.single().headers[HttpHeaders.Cookie])
        assertNull(engineB.requestHistory.single().headers[HttpHeaders.Authorization])
        assertNull(engineA.requestHistory.last().headers[HttpHeaders.Cookie])
        assertNull(engineA.requestHistory.last().headers[HttpHeaders.Authorization])
        a.close(); b.close()
    }
    @Test fun explicit_default_port_is_same_origin_and_validators_cannot_inject_headers() = runTest {
        val engine = MockEngine { respond("feed") }
        val transport = KtorOpdsTransport(engine, root)
        transport.fetch(request("https://catalogue.example.org:443/root", OpdsCredentials.Anonymous).copy(cacheValidators = mapOf(HttpHeaders.IfNoneMatch to "\"v1\"", HttpHeaders.Authorization to "SECRET")))
        assertNull(engine.requestHistory.last().headers[HttpHeaders.Authorization])
        assertEquals("\"v1\"", engine.requestHistory.last().headers[HttpHeaders.IfNoneMatch])
        transport.fetch(request("https://catalogue.example.org:443/root"))
        assertNotNull(engine.requestHistory.last().headers[HttpHeaders.Authorization])
        transport.close()
    }
    @Test fun redirected_root_401_retains_root_context_and_multiple_header_challenges() = runTest {
        var hop = 0
        val engine = MockEngine { if (hop++ == 0) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/moved")) else respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate to listOf("Digest realm=\"private\"", "bAsIc realm=\"reader\""))) }
        val transport = KtorOpdsTransport(engine, root)
        val error = failure(transport.fetch(request()))
        assertTrue(error.isCatalogueRoot)
        assertEquals(OpdsTransportError.Code.SIGN_IN_NEEDED, error.code)
        assertNull(engine.requestHistory.last().headers[HttpHeaders.Authorization])
        transport.close()
    }
    @Test fun redirects_without_location_fail_clearly() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Found) }
        val transport = KtorOpdsTransport(engine, root)
        assertEquals(OpdsTransportError.Code.REDIRECT_MISSING_LOCATION, failure(transport.fetch(request())).code)
        transport.close()
    }
}
