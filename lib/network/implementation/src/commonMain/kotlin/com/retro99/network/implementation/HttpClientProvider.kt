package com.retro99.network.implementation

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

private const val CONNECT_TIMEOUT_MS = 30_000L
private const val REQUEST_TIMEOUT_MS = 5 * 60 * 1000L
private const val SOCKET_TIMEOUT_MS = 5 * 60 * 1000L

@Single
class HttpClientProvider(
    @Provided private val httpFactory: HttpClientEngineFactory<*>,
    @Provided private val json: Json,
) {
    fun provide(): HttpClient {
        return HttpClient(httpFactory) {
            install(ContentNegotiation) {
                json(json, contentType = ContentType.Application.Json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
        }
    }
}
