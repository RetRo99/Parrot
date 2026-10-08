package com.retro99.parrot.initializer

import com.retro99.server.api.*
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
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
    // Plan §10.7: catalogue pictures have their own path, so a catalogue on a library server's
    // address no longer takes that server's covers out of the global loader.
    @Test fun a_library_server_keeps_its_token_when_a_catalogue_shares_its_origin_in_either_registration_order() = runBlocking {
        val opds = source("opds", ServerType.Opds, "https://catalogue.example/opds/")
        val library = source("library", ServerType.Storyteller, "https://catalogue.example")
        checkSources(listOf(library, opds), "library-token")
        checkSources(listOf(opds, library), "library-token")
    }
    @Test fun a_library_server_on_another_port_or_scheme_is_not_matched() = runBlocking {
        checkSources(listOf(source("library", ServerType.Storyteller, "https://catalogue.example:8443")), null)
        checkSources(listOf(source("library", ServerType.Storyteller, "http://catalogue.example")), null)
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
            }, CatalogueImageSource(NoCatalogues, NoUsers, NoAccounts))
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

// The global loader's token choice does not touch catalogue pictures; these are never called.
private object NoCatalogues : CatalogueRepositoryProvider {
    override fun observeRepositories(): Flow<List<ServerCatalogueRepository>> = error("unused")
    override suspend fun getRepositories(): List<ServerCatalogueRepository> = error("unused")
    override suspend fun getRepository(sourceId: String): ServerCatalogueRepository? = error("unused")
}

private object NoAccounts : OpdsCredentialStore {
    override fun get(profileId: String, sourceId: String): OpdsAccountDetails? = error("unused")
    override suspend fun save(profileId: String, sourceId: String, details: OpdsAccountDetails) = error("unused")
    override suspend fun remove(profileId: String, sourceId: String) = error("unused")
    override fun accessGeneration(profileId: String, sourceId: String): Long = error("unused")
}

private object NoUsers : UserRegistry {
    override fun observeAllProfiles(): Flow<List<UserProfile>> = error("unused")
    override suspend fun getAllProfiles(): List<UserProfile> = error("unused")
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile = error("unused")
    override suspend fun updateProfile(profile: UserProfile) = error("unused")
    override suspend fun deleteProfile(profileId: String) = error("unused")
    override suspend fun getProfile(profileId: String): UserProfile? = error("unused")
    override fun observeActiveProfile(): Flow<UserProfile?> = error("unused")
    override suspend fun getActiveProfile(): UserProfile? = error("unused")
    override fun getActiveProfileId(): String? = error("unused")
    override suspend fun setActiveProfile(profileId: String) = error("unused")
    override suspend fun clearActiveProfile() = error("unused")
    override suspend fun hasProfiles(): Boolean = error("unused")
    override fun isProfileActive(): Boolean = error("unused")
}
