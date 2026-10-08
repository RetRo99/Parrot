package com.retro99.parrot.initializer

import com.retro99.server.api.*
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CoverAuthCatalogueTest {
    private fun source(id: String, type: ServerType, address: String) = ServerConfig(id, id, type, address, 0)

    @Test fun opds_is_never_selected_for_bearer_auth() = runBlocking {
        checkSources(listOf(source("opds", ServerType.Opds, "https://catalogue.example/opds")), null)
    }
    @Test fun shared_origin_never_receives_another_servers_token_in_either_registration_order() = runBlocking {
        val opds = source("opds", ServerType.Opds, "https://catalogue.example/opds/")
        val library = source("library", ServerType.Storyteller, "https://catalogue.example")
        checkSources(listOf(library, opds), null)
        checkSources(listOf(opds, library), null)
    }
    @Test fun unrelated_catalogue_does_not_change_existing_cover_auth() = runBlocking {
        checkSources(listOf(source("opds", ServerType.Opds, "https://other.example/opds"), source("library", ServerType.Storyteller, "https://catalogue.example")), "library-token")
    }
    private suspend fun checkSources(sources: List<ServerConfig>, expected: String?) {
        val calls = mutableListOf<String>()
        val client = HttpClient()
        try {
            val initializer = CoilInitializer(client, CoverRegistry(sources), object : ServerTokenProvider {
                override suspend fun refreshToken(serverId: String): String? = error("unused")
                override suspend fun getToken(serverId: String): String? {
                    calls += serverId
                    return "$serverId-token"
                }
            })
            assertEquals(expected, initializer.resolveTokenForUrl(Url("https://catalogue.example/cover")))
            assertEquals(if (expected == null) emptyList() else listOf("library"), calls)
        } finally { client.close() }
    }
}

private class CoverRegistry(private val sources: List<ServerConfig>) : ServerRegistry {
    override suspend fun getAllServers() = sources
    override fun observeAllServers() = flowOf(sources)
    override suspend fun getServer(serverId: String) = sources.find { it.id == serverId }
    override suspend fun addServer(name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
    override suspend fun addServerWithId(id: String, name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
    override suspend fun updateServer(config: ServerConfig) = error("unused")
    override suspend fun removeServer(serverId: String) = error("unused")
    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = error("unused")
    override fun observeAuthState(serverId: String): Flow<ServerAuthState> = error("unused")
    override suspend fun isAuthenticated(serverId: String): Boolean = error("unused")
    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = error("unused")
    override suspend fun getAuthenticatedServers(): List<ServerConfig> = error("unused")
    override suspend fun saveCredentials(credentials: ServerCredentials) = error("unused")
    override suspend fun getCredentials(serverId: String): ServerCredentials? = error("unused")
    override suspend fun clearCredentials(serverId: String) = error("unused")
    override suspend fun clearAllCredentials() = error("unused")
    override suspend fun deactivateServer(serverId: String) = error("unused")
}
