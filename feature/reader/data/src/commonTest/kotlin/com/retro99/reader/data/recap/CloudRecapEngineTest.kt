package com.retro99.reader.data.recap

import com.retro99.reader.domain.recap.RecapErrorCode
import com.retro99.reader.domain.recap.RecapInput
import com.retro99.reader.domain.recap.RecapResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CloudRecapEngineTest {

    private class FakeTokens(
        var token: String? = "jwt-1",
        var refreshed: String? = "jwt-2",
    ) : RecapAuthTokens {
        var refreshes = 0
        override fun isSignedIn() = token != null
        override fun observeSignedIn(): Flow<Boolean> = flowOf(token != null)
        override suspend fun accessToken() = token
        override suspend fun refreshedAccessToken(): String? {
            refreshes++
            return refreshed
        }
    }

    private val tokens = FakeTokens()
    private val requests = mutableListOf<HttpRequestData>()
    private val clock = TestClock(nowMs = 1_700_000_000_000)
    private val input = RecapInput("Excerpt text. ".repeat(20), "sl-SI", "The last sentence.")

    private fun engine(
        endpoint: RecapEndpoint = RecapEndpoint("https://project.supabase.co/", "pk-test"),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = CloudRecapEngine(
        endpoint = endpoint,
        auth = tokens,
        httpClient = HttpClient(MockEngine { request -> requests += request; handler(request) }) {
            install(HttpTimeout)
        },
        clock = clock,
    )

    private fun MockRequestHandleScope.json(
        status: HttpStatusCode,
        body: String,
        retryAfter: String? = null,
    ) = respond(
        body,
        status,
        if (retryAfter == null) {
            headersOf(HttpHeaders.ContentType, "application/json")
        } else {
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.RetryAfter to listOf(retryAfter),
            )
        },
    )

    @Test
    fun sendsTheContractRequest() = runTest {
        val result = engine { json(HttpStatusCode.OK, """{"kind":"recap","summary":" Ana left. "}""") }
            .generate(input)

        assertEquals(RecapResult.Success("Ana left.", null), result)
        val request = requests.single()
        assertEquals("https://project.supabase.co/functions/v1/generate-recap", request.url.toString())
        assertEquals("pk-test", request.headers["apikey"])
        assertEquals("Bearer jwt-1", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        assertEquals(input.excerpt, body["excerpt"]!!.jsonPrimitive.content)
        assertEquals("sl", body["language"]!!.jsonPrimitive.content)
        assertEquals("The last sentence.", body["lastSentence"]!!.jsonPrimitive.content)
        assertEquals(setOf("excerpt", "language", "lastSentence"), body.keys)
    }

    private suspend fun HttpRequestData.json() =
        Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

    @Test
    fun aLongExcerptIsUploadedInPartsThenGenerated() = runTest {
        // Far past the old 8,000-char cap, in 3-byte chars: nothing is cut.
        val long = "Dolga seja \u20ac branja. ".repeat(40_000)
        val result = engine { request ->
            if (request.json()["text"] != null && request.json()["language"] == null) {
                json(HttpStatusCode.Accepted, """{"stored":0}""")
            } else {
                json(HttpStatusCode.OK, """{"kind":"recap","summary":"Ana left."}""")
            }
        }.generate(input.copy(excerpt = long))

        assertEquals(RecapResult.Success("Ana left.", null), result)
        assertTrue(requests.size > 2)
        val bodies = requests.map { it.json() }
        requests.forEach { assertTrue(it.body.toByteArray().size < 256 * 1024) }
        val ids = bodies.map { it["upload"]!!.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(1, ids.size)
        bodies.forEachIndexed { index, body ->
            val upload = body["upload"]!!.jsonObject
            assertEquals(index, upload["index"]!!.jsonPrimitive.int)
            assertEquals(requests.size, upload["total"]!!.jsonPrimitive.int)
            assertNull(body["excerpt"])
        }
        assertEquals(long, bodies.joinToString("") { it["text"]!!.jsonPrimitive.content })
        assertEquals("sl", bodies.last()["language"]!!.jsonPrimitive.content)
        assertEquals("The last sentence.", bodies.last()["lastSentence"]!!.jsonPrimitive.content)
        assertNull(bodies.first()["language"])
    }

    @Test
    fun aRefusedPartStopsTheUpload() = runTest {
        val result = engine { json(HttpStatusCode.TooManyRequests, "{}", "3600") }
            .generate(input.copy(excerpt = "x".repeat(400_000)))

        assertEquals(RecapResult.Retryable(RecapErrorCode.RATE_LIMITED, 3600.seconds), result)
        assertEquals(1, requests.size)
    }

    @Test
    fun anIncompleteUploadIsRetriedLater() = runTest {
        val result = engine { request ->
            if (request.json()["language"] == null) {
                json(HttpStatusCode.Accepted, "{}")
            } else {
                json(HttpStatusCode.Conflict, """{"error":"Upload incomplete"}""")
            }
        }.generate(input.copy(excerpt = "x".repeat(400_000)))

        assertEquals(RecapResult.Retryable(RecapErrorCode.UNKNOWN), result)
    }

    @Test
    fun splitForUploadBoundsBytesAndLosesNothing() {
        val text = "a\"\n\u20ac\uD83D\uDE00".repeat(50_000)
        val parts = CloudRecapEngine.splitForUpload(text, maxBytes = 10_000)

        assertEquals(text, parts.joinToString(""))
        for (part in parts) {
            assertTrue(JsonPrimitive(part).toString().encodeToByteArray().size <= 10_000)
            assertFalse(part.last().isHighSurrogate())
        }
        assertEquals(listOf("short"), CloudRecapEngine.splitForUpload("short"))
    }

    @Test
    fun readsTheOptionalModel() = runTest {
        val result = engine {
            json(HttpStatusCode.OK, """{"kind":"recap","summary":"Ana left.","model":"hy3"}""")
        }.generate(input)

        assertEquals(RecapResult.Success("Ana left.", "hy3"), result)
    }

    @Test
    fun notEnough() = runTest {
        val result = engine { json(HttpStatusCode.OK, """{"kind":"not_enough","summary":null}""") }
            .generate(input)
        assertEquals(RecapResult.NotEnough, result)
    }

    @Test
    fun malformedSuccessIsRetryable() = runTest {
        val result = engine { json(HttpStatusCode.OK, """{"kind":"recap","summary":""}""") }
            .generate(input)
        assertEquals(RecapResult.Retryable(RecapErrorCode.BAD_RESPONSE), result)
    }

    @Test
    fun unsupportedLanguageIsOmittedAndTheLastSentenceCapped() = runTest {
        engine { json(HttpStatusCode.OK, """{"kind":"not_enough","summary":null}""") }
            .generate(input.copy(language = "ja", lastSentence = "x".repeat(500)))

        val body = Json.parseToJsonElement(requests.single().body.toByteArray().decodeToString()).jsonObject
        assertNull(body["language"])
        assertEquals(300, body["lastSentence"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun expiredTokenIsRefreshedOnce() = runTest {
        val result = engine { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer jwt-1") {
                json(HttpStatusCode.Unauthorized, """{"error":"Unauthorized"}""")
            } else {
                json(HttpStatusCode.OK, """{"kind":"recap","summary":"Ok."}""")
            }
        }.generate(input)

        assertEquals(RecapResult.Success("Ok.", null), result)
        assertEquals(1, tokens.refreshes)
        assertEquals(2, requests.size)
    }

    @Test
    fun secondUnauthorizedNeedsSignIn() = runTest {
        val result = engine { json(HttpStatusCode.Unauthorized, """{"error":"Unauthorized"}""") }
            .generate(input)

        assertEquals(RecapResult.AuthRequired, result)
        assertEquals(1, tokens.refreshes)
    }

    @Test
    fun noTokenNeedsSignInWithoutARequest() = runTest {
        tokens.token = null
        val result = engine { error("no request expected") }.generate(input)

        assertEquals(RecapResult.AuthRequired, result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun shortExcerptFailsWithoutARequest() = runTest {
        val result = engine { error("no request expected") }.generate(input.copy(excerpt = "  tiny  "))

        assertEquals(RecapResult.Permanent(RecapErrorCode.EXCERPT_TOO_SHORT), result)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun mapsErrorStatuses() = runTest {
        suspend fun mapped(status: Int, body: String = """{"error":"x"}""", retryAfter: String? = null) =
            engine { json(HttpStatusCode.fromValue(status), body, retryAfter) }.generate(input)

        assertEquals(RecapResult.Permanent(RecapErrorCode.BAD_REQUEST), mapped(400))
        assertEquals(RecapResult.Permanent(RecapErrorCode.BAD_REQUEST), mapped(405))
        assertEquals(RecapResult.Permanent(RecapErrorCode.BAD_REQUEST), mapped(413))
        assertEquals(RecapResult.Retryable(RecapErrorCode.UNKNOWN), mapped(409))
        assertEquals(
            RecapResult.Permanent(RecapErrorCode.EXCERPT_TOO_SHORT),
            mapped(422, """{"error":"Excerpt too short to summarise"}"""),
        )
        assertEquals(
            RecapResult.Permanent(RecapErrorCode.UNSUPPORTED_LANGUAGE),
            mapped(422, """{"error":"Unsupported language"}"""),
        )
        assertEquals(
            RecapResult.Retryable(RecapErrorCode.RATE_LIMITED, 3600.seconds),
            mapped(429, retryAfter = "3600"),
        )
        assertEquals(RecapResult.Retryable(RecapErrorCode.SERVICE_UNAVAILABLE), mapped(503))
        assertEquals(RecapResult.Retryable(RecapErrorCode.PROVIDER_ERROR), mapped(502))
        assertEquals(RecapResult.Retryable(RecapErrorCode.TIMEOUT), mapped(504))
        assertEquals(RecapResult.Retryable(RecapErrorCode.UNKNOWN), mapped(500))
        // A gateway 403 isn't fixed by signing in; it backs off.
        assertEquals(RecapResult.Retryable(RecapErrorCode.UNKNOWN), mapped(403))
    }

    @Test
    fun retryAfterAsAnHttpDate() = runTest {
        // 1_700_000_000_000 ms is Tue, 14 Nov 2023 22:13:20 GMT.
        val result = engine {
            json(HttpStatusCode.TooManyRequests, "{}", "Tue, 14 Nov 2023 22:14:20 GMT")
        }.generate(input)

        assertEquals(RecapResult.Retryable(RecapErrorCode.RATE_LIMITED, 60.seconds), result)
    }

    @Test
    fun transportFailureIsRetryable() = runTest {
        val result = engine { throw IllegalStateException("socket closed") }.generate(input)
        assertEquals(RecapResult.Retryable(RecapErrorCode.NETWORK), result)
    }

    @Test
    fun unconfiguredEndpointNeverCalls() = runTest {
        val result = engine(RecapEndpoint("", "")) { error("no request expected") }.generate(input)
        assertEquals(RecapResult.Retryable(RecapErrorCode.SERVICE_UNAVAILABLE), result)
        assertFalse(requests.isNotEmpty())
    }
}
