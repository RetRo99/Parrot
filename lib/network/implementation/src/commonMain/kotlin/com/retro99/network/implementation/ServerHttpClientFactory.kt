package com.retro99.network.implementation

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

private const val CONNECT_TIMEOUT_MS = 30_000L // 30 seconds
private const val REQUEST_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes for large downloads
private const val SOCKET_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes for large downloads

/**
 * Factory for creating HttpClient instances with per-server authentication.
 * Each server gets its own HttpClient configured with its specific token provider.
 */
@Single
class ServerHttpClientFactory(
    @Provided private val httpFactory: HttpClientEngineFactory<*>,
    @Provided private val json: Json,
) {
    /**
     * Creates an HttpClient configured for a specific server.
     *
     * @param tokenProvider A suspend function that returns the current auth token for this server
     * @param tokenRefresher Optional suspend function to refresh the token when it expires
     */
    fun create(
        tokenProvider: suspend () -> String?,
        tokenRefresher: (suspend () -> String?)? = null,
    ): HttpClient {
        return HttpClient(httpFactory) {
            install(ContentNegotiation) {
                json(json, contentType = ContentType.Application.Json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            install(Auth) {
                bearer {
                    sendWithoutRequest { true }
                    loadTokens {
                        val token = tokenProvider()
                        token?.let { BearerTokens(it, "") }
                    }
                    if (tokenRefresher != null) {
                        refreshTokens {
                            val token = tokenRefresher()
                            token?.let { BearerTokens(it, "") }
                        }
                    }
                }
            }
        }
    }
}
