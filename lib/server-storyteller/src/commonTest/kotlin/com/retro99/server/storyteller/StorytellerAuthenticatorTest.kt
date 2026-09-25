package com.retro99.server.storyteller

import com.github.michaelbull.result.getOrElse
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.server.api.ServerValidationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorytellerAuthenticatorTest {
    @Test
    fun `login does not treat server-managed session lifetime as a fixed local expiry`() = runTest {
        val httpClient = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/api/v2/token", request.url.encodedPath)
                respond(
                    content = """
                        {"access_token":"session-token","expires_in":999999999999999}
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        try {
            val credentials = authenticator(httpClient)
                .login("https://storyteller.example", "reader", "password")
                .getOrElse { error("Login unexpectedly failed") }

            assertEquals("session-token", credentials.accessToken)
            assertNull(credentials.expiresAt)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `health route validates Storyteller without checking protected routes`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val httpClient = HttpClient(
            MockEngine { request ->
                requestedPaths += request.url.encodedPath
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("https://storyteller.example/api/health", request.url.toString())
                respond(
                    content = """{"status":"healthy"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        try {
            val result = authenticator(httpClient).validateServer("https://storyteller.example/")
                .getOrElse { error("Validation unexpectedly failed") }

            assertTrue(result.isValid)
            assertEquals("Storyteller", result.serverName)
            assertNull(result.errorMessage)
            assertEquals(listOf("/api/health"), requestedPaths)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `auth-required v2 route confirms Storyteller when health route is unavailable`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val httpClient = HttpClient(
            MockEngine { request ->
                requestedPaths += request.url.encodedPath
                val response = when (request.url.encodedPath) {
                    "/api/health" -> HttpStatusCode.NotFound
                    "/api/v2/books" -> HttpStatusCode.Unauthorized
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }
                respond(
                    content = "{}",
                    status = response,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        try {
            val result = authenticator(httpClient).validateServer("https://storyteller.example")
                .getOrElse { error("Validation unexpectedly failed") }

            assertTrue(result.isValid)
            assertEquals("Storyteller", result.serverName)
            assertNull(result.errorMessage)
            assertEquals(listOf("/api/health", "/api/v2/books"), requestedPaths)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `method-not-allowed from protected v2 route confirms Storyteller`() = runTest {
        val httpClient = HttpClient(
            MockEngine { request ->
                val response = when (request.url.encodedPath) {
                    "/api/health" -> HttpStatusCode.NotFound
                    "/api/v2/books" -> HttpStatusCode.MethodNotAllowed
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }
                respond(content = "{}", status = response)
            },
        )

        try {
            val result = authenticator(httpClient).validateServer("https://storyteller.example")
                .getOrElse { error("Validation unexpectedly failed") }

            assertTrue(result.isValid)
            assertEquals("Storyteller", result.serverName)
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `missing health and books routes reject the server`() = runTest {
        val httpClient = HttpClient(
            MockEngine { request ->
                val response = when (request.url.encodedPath) {
                    "/api/health", "/api/v2/books" -> HttpStatusCode.NotFound
                    else -> error("Unexpected request path: ${request.url.encodedPath}")
                }
                respond(content = "{}", status = response)
            },
        )

        try {
            val result: ServerValidationResult = authenticator(httpClient)
                .validateServer("https://storyteller.example")
                .getOrElse { error("Validation unexpectedly failed") }

            assertFalse(result.isValid)
            assertNull(result.serverName)
            assertEquals("Server returned 404 Not Found", result.errorMessage)
        } finally {
            httpClient.close()
        }
    }

    private fun authenticator(httpClient: HttpClient) =
        StorytellerAuthenticator(httpClient, NoOpAnalytics)

    private object NoOpAnalytics : Analytics {
        override fun logException(throwable: Throwable, message: String?) = Unit

        override fun logEvent(event: AnalyticsEvent) = Unit

        override fun setUserId(userId: String?) = Unit
    }
}
