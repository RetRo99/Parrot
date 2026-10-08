package com.retro99.auth.domain.usecase

import com.retro99.server.api.*
import com.retro99.database.api.DatabaseCleaner
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueLogoutEntryPointTest {
    @Test fun catalogue_profile_logout_never_calls_destructive_database_cleaner() = runTest {
        val registry = LogoutRegistry(listOf(source(ServerType.Opds), source(ServerType.Storyteller), source(ServerType.Local)))
        var deleted = false
        val cleaner = object : DatabaseCleaner { override suspend fun clearAllData() { deleted = true } }
        assertTrue(LogoutUseCase(registry, cleaner).logoutAll().isOk)
        assertFalse(deleted, "Downloaded books must survive catalogue sign-out")
        assertEquals(setOf("opds", "storyteller"), registry.cleared.toSet())
        assertEquals(3, registry.getAllServers().size)
    }
    @Test fun library_only_logout_keeps_existing_cleaner_behavior() = runTest {
        val registry = LogoutRegistry(listOf(source(ServerType.Storyteller), source(ServerType.Local)))
        var deleted = false
        val cleaner = object : DatabaseCleaner { override suspend fun clearAllData() { deleted = true } }
        LogoutUseCase(registry, cleaner).logoutAll()
        assertTrue(deleted)
        assertEquals(listOf("storyteller"), registry.cleared)
    }
    @Test fun catalogue_only_is_configured_not_an_authenticated_library_account() = runTest {
        val sources = listOf(source(ServerType.Opds))
        assertTrue(hasConfiguredRemoteSetup(sources))
        val registry = LogoutRegistry(sources)
        assertFalse(ObserveHasAuthenticatedRemoteServersUseCase(registry)().first())
    }
    private fun source(type: ServerType) = ServerConfig(type.identifier, type.displayName, type, "https://books.example/", 0)
}

private class LogoutRegistry(private val sources: List<ServerConfig>) : ServerRegistry {
    val cleared = mutableListOf<String>()
    override suspend fun getAllServers() = sources
    override fun observeAllServers() = flowOf(sources)
    // Even a faulty legacy auth provider must not count OPDS as a library session.
    override fun observeAuthenticatedServers() = flowOf(sources)
    override suspend fun getAuthenticatedServers() = sources
    override suspend fun clearCredentials(serverId: String) { cleared += serverId }
    override suspend fun clearAllCredentials() = error("unused")
    override suspend fun getServer(serverId: String) = sources.find { it.id == serverId }
    override suspend fun addServer(name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
    override suspend fun addServerWithId(id: String, name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
    override suspend fun updateServer(config: ServerConfig) = error("unused")
    override suspend fun removeServer(serverId: String) = error("Must keep sources")
    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = error("unused")
    override fun observeAuthState(serverId: String): Flow<ServerAuthState> = error("unused")
    override suspend fun isAuthenticated(serverId: String) = false
    override suspend fun saveCredentials(credentials: ServerCredentials) = error("unused")
    override suspend fun getCredentials(serverId: String): ServerCredentials? = null
    override suspend fun deactivateServer(serverId: String) = error("Must keep sources enabled")
}
