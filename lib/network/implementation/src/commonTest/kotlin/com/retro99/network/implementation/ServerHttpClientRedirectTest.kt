package com.retro99.network.implementation

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins Ktor's redirect handling so a bump can't start leaking tokens. */
class ServerHttpClientRedirectTest {

    @Test
    fun crossHostRedirectDropsBearerToken() = runTest {
        val seen = mutableMapOf<String, String?>()
        val client = clientFor { request ->
            seen[request.url.host] = request.headers[HttpHeaders.Authorization]
            if (request.url.host == "books.example") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/x"))
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }

        client.get("https://books.example/api/v2/books")

        assertEquals("Bearer secret", seen["books.example"])
        assertNull(seen["evil.example"])
        client.close()
    }

    @Test
    fun httpsToHttpRedirectIsNotFollowed() = runTest {
        val hosts = mutableListOf<String>()
        val client = clientFor { request ->
            hosts += "${request.url.protocol.name}://${request.url.host}"
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://books.example/x"))
        }

        val response = client.get("https://books.example/api/v2/books")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(listOf("https://books.example"), hosts)
        client.close()
    }

    private fun clientFor(handler: MockRequestHandler) =
        ServerHttpClientFactory(MockFactory(handler), Json).create(tokenProvider = { "secret" })

    private class MockFactory(
        private val handler: MockRequestHandler,
    ) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
            MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
    }
}
