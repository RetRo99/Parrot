package com.retro99.parrot.initializer

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.retro99.base.AppInitializer
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.bearerAuth
import io.ktor.http.Url
import org.koin.core.annotation.Single

private const val CONNECT_TIMEOUT_MS = 30_000L
private const val REQUEST_TIMEOUT_MS = 5 * 60 * 1000L
private const val SOCKET_TIMEOUT_MS = 5 * 60 * 1000L

private class CoilAuthConfig {
    var resolveTokenForUrl: (suspend (Url) -> String?)? = null
}

private val ServerAuthPlugin = createClientPlugin("ServerAuthPlugin", ::CoilAuthConfig) {
    val resolver = pluginConfig.resolveTokenForUrl
    onRequest { request, _ ->
        resolver?.invoke(request.url.build())?.let { token ->
            request.bearerAuth(token)
        }
    }
}

@Single(binds = [AppInitializer::class])
class CoilInitializer(
    private val httpClient: HttpClient,
    private val serverRegistry: ServerRegistry,
    private val serverTokenProvider: ServerTokenProvider,
) : AppInitializer {

    override fun initialize() {
        val imageClient = HttpClient(httpClient.engine) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            install(ServerAuthPlugin) {
                resolveTokenForUrl = ::resolveTokenForUrl
            }
        }

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(imageClient))
                }
                .build()
        }
    }

    private suspend fun resolveTokenForUrl(url: Url): String? {
        val servers = serverRegistry.getAllServers()
        val matchingServer = servers.firstOrNull { server ->
            isSameOrigin(server.baseUrl, url)
        } ?: return null
        return serverTokenProvider.getToken(matchingServer.id)
    }
}

/**
 * Scheme, host and port must all match, so a token for an https server is
 * never sent over http or to another port on the same host.
 */
internal fun isSameOrigin(serverBaseUrl: String, url: Url): Boolean {
    val base = runCatching { Url(serverBaseUrl) }.getOrNull() ?: return false
    // Url.port already falls back to the scheme's default port.
    return base.protocol.name.equals(url.protocol.name, ignoreCase = true) &&
        base.host.equals(url.host, ignoreCase = true) &&
        base.port == url.port
}
