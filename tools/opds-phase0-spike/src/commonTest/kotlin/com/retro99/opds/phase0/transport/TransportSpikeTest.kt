package com.retro99.opds.phase0.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Phase 0 spike for the transport choice (plan §4 "HTTP security and
 * lifecycle"). Demonstrates, on a Ktor `MockEngine` client shared across both
 * targets:
 *
 * 1. engine auto-follow is OFF; the loop resolves redirects itself with a
 *    hard bound (5 hops planned in §4),
 * 2. credentials are attached by the caller only on the initial trusted-origin
 *    request and never re-attached on a redirect hop — not even when that hop
 *    returns to the same origin — and cross-origin hops demonstrably carry
 *    none, satisfying "no credentials cross-origin or on any redirect hop",
 * 3. the returned document reports its own URL, which must become the base
 *    for relative-link resolution once the response body is parsed.
 *
 * Phase 1 owns the real transport (isolated client, size limits, Retry-After,
 * cache keys, logging hygiene); this is the recorded, testable shape.
 */
class TransportSpikeTest {

    private companion object {
        const val FEED_BODY =
            """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
            <id>urn:uuid:to</id><title>t</title><updated>2026-10-08T00:00:00Z</updated></feed>"""
        const val CREDENTIAL = "Basic c3Bpa2U6cGluZw=="
    }

    @Test
    fun `redirect_chain_bounded_credentialfree_hops_effective_URL_reported`() = runTest {
        var hop = 0
        val engine = MockEngine { _ ->
            when (hop++) {
                0 -> respond(
                    FEED_BODY,
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location to listOf("https://cdn.example.org/feed")),
                )
                1 -> respond(
                    FEED_BODY,
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location to listOf("https://catalogue.example.org/feed?v=2")),
                )
                2 -> respond(
                    FEED_BODY,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/atom+xml")),
                )
                else -> fail("transport issued more requests than the chain expected")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }

        val document = fetchBounded(
            client,
            startUrl = "https://catalogue.example.org/feed",
            trustedOrigin = TrustedOrigin("https", "catalogue.example.org"),
            credential = CREDENTIAL,
        )

        assertEquals("https://catalogue.example.org/feed?v=2", document.url)
        assertEquals("feed", document.rootLocalName)

        val hops = engine.requestHistory
        assertEquals(3, hops.size)
        // Credential attached only to the explicitly trusted origin of hop 0.
        // Credential attached only to the explicitly trusted origin of hop 0.
        assertEquals(CREDENTIAL, hops[0].headers[HttpHeaders.Authorization])
        // Cross-origin redirect hop carries nothing.
        assertNull(hops[1].headers[HttpHeaders.Authorization], "cross-origin hop must carry no credentials")
        // And the redirect back to the origin still carries none: policy is
        // enforced per manually followed URL, not per source origin.
        assertNull(hops[2].headers[HttpHeaders.Authorization], "redirect hop back to the origin still carries none")
    }

    @Test
    fun `redirect_limit_is_hard`() = runTest {
        var hop = 0
        val engine = MockEngine { _ ->
            when (hop++) {
                in 0..5 -> respond(
                    "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location to listOf("https://a.test/h$hop")),
                )
                else -> fail("transport issued more requests than the bound allows")
            }
        }
        val client = HttpClient(engine) { followRedirects = false }

        assertFailsWith<TransportException> {
            fetchBounded(
                client,
                startUrl = "https://a.test/h0",
                trustedOrigin = TrustedOrigin("https", "a.test"),
                credential = CREDENTIAL,
            )
        }
        // The initial request plus five redirect hops, then the loop refuses.
        assertEquals(6, engine.requestHistory.size)
    }

    // ---- the demonstrated transport loop --------------------------------------

    class TransportException(why: String) : IllegalStateException(why)

    /** Scheme+host(+port) pair that may receive credentials (§4 origin rule). */
    data class TrustedOrigin(val scheme: String, val host: String)

    /** A fetched document with the URL that actually resolved it (§4 base rule). */
    class FetchedDocument(val url: String, val rootLocalName: String)

    /**
     * Minimal demonstration loop: follows redirects with a hard bound, applies
     * credentials only to the trusted origin of a manually followed URL, and
     * reports the effective final URL rather than the requested one.
     */
    private suspend fun fetchBounded(
        client: HttpClient,
        startUrl: String,
        trustedOrigin: TrustedOrigin,
        credential: String,
        maxRedirects: Int = 5,
    ): FetchedDocument {
        var currentUrl: String = startUrl
        var isRedirectHop = false
        var attempts = 0
        while (true) {
            if (++attempts > maxRedirects + 1) throw TransportException("more than $maxRedirects redirects")
            // Credentials only on a manually followed origin URL, never on a redirect hop.
            val attachCredentials = !isRedirectHop && isTrustedOrigin(currentUrl, trustedOrigin)
            val response = client.request(currentUrl) {
                if (attachCredentials) {
                    headers.append(HttpHeaders.Authorization, credential)
                }
            }
            when (response.status.value) {
                in 300..399 -> {
                    val location = response.headers[HttpHeaders.Location]
                        ?: throw TransportException("redirect without a Location header")
                    currentUrl = com.retro99.opds.phase0.rfc3986.ReferenceResolver
                        .resolve(currentUrl.substringBeforeLast('#'), location)
                    isRedirectHop = true
                }
                else -> {
                    val body = response.bodyAsText()
                    return FetchedDocument(
                        url = currentUrl,
                        rootLocalName = rootLocalNameOf(body),
                    )
                }
            }
        }
    }

    private fun isTrustedOrigin(url: String, trusted: TrustedOrigin): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme.isEmpty() || scheme == url || scheme.length + 3 > url.length) return false
        val authority = url.removePrefix("$scheme://").substringBefore('/')
        val host = authority.substringBefore(':').lowercase()
        val portString = authority.substringAfter(':', "")
        val defaultPort = if (trusted.scheme == "https") "443" else "80"
        val isDefaultPort = portString.isEmpty() || portString == defaultPort
        return scheme == trusted.scheme && host == trusted.host.lowercase() && isDefaultPort
    }

    /** Spike-only root-element name of an XML/Atom body (after any declaration). */
    private fun rootLocalNameOf(body: String): String = body
        .substringAfter("?>", body)
        .substringAfter('<', missingDelimiterValue = "")
        .takeWhile { it.isLetterOrDigit() }
}
