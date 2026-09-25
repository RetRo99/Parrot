package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.getOrElse
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.server.api.ServerCredentials
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookshelfAuthenticatorTest {
    @Test
    fun `login retains account id and does not log credentials`() = runTest {
        val analytics = RecordingAnalytics()
        val httpClient = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                respond(
                    content = """
                        {
                          "user": {
                            "id": "abs-user-id",
                            "username": "reader",
                            "token": "test-access-token"
                          }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json()
            }
        }

        try {
            val classUnderTest = AudiobookshelfAuthenticator(httpClient, analytics)

            val credentials = classUnderTest.login(
                baseUrl = "https://audiobookshelf.example",
                username = "reader",
                password = "test-password",
            ).getOrElse { error("Audiobookshelf login unexpectedly failed") }

            assertEquals("abs-user-id", credentials.accountId)
            assertEquals("test-access-token", credentials.accessToken)
            assertTrue(analytics.loggedExceptions.isEmpty())
            assertTrue(analytics.loggedEvents.isEmpty())
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `credentials saved before account id support still deserialize`() {
        val credentials = Json.decodeFromString<ServerCredentials>(
            """
                {
                  "serverId": "server-id",
                  "username": "reader",
                  "accessToken": "saved-token",
                  "refreshToken": null,
                  "expiresAt": null
                }
            """.trimIndent(),
        )

        assertNull(credentials.accountId)
    }

    private class RecordingAnalytics : Analytics {
        val loggedExceptions = mutableListOf<Throwable>()
        val loggedEvents = mutableListOf<AnalyticsEvent>()

        override fun logException(throwable: Throwable, message: String?) {
            loggedExceptions += throwable
        }

        override fun logEvent(event: AnalyticsEvent) {
            loggedEvents += event
        }

        override fun setUserId(userId: String?) = Unit
    }
}
