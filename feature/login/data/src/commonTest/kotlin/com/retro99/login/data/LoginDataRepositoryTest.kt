package com.retro99.login.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.server.ServerType
import com.retro99.login.data.oauth.StorytellerOAuthSessionLauncher
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerAuthenticator
import com.retro99.server.api.ServerAuthenticatorFactory
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginDataRepositoryTest {

    @Test
    fun `password reauthentication replaces credentials under existing server id`() = runTest {
        // Given
        val existingServer = storytellerServer()
        val serverRegistry = FakeServerRegistry(existingServer)
        serverRegistry.savedCredentials[existingServer.id] = ServerCredentials(
            serverId = existingServer.id,
            username = "old-reader",
            accessToken = "old-test-token",
        )
        val authenticator = FakeStorytellerAuthenticator()
        val classUnderTest = createRepository(serverRegistry, authenticator)

        // When
        val result = classUnderTest.login(
            serverType = ServerType.Storyteller,
            serverUrl = "https://changed.example",
            username = "reader",
            password = "test-password",
            existingServerId = existingServer.id,
        )

        // Then
        assertTrue(result.isOk)
        assertEquals(existingServer.baseUrl, authenticator.lastBaseUrl)
        assertEquals(1, authenticator.loginCount)
        assertEquals(0, serverRegistry.addServerCount)
        assertTrue(serverRegistry.updatedServers.isEmpty())
        assertEquals(existingServer, serverRegistry.servers[existingServer.id])
        assertEquals(setOf(existingServer.id), serverRegistry.savedCredentials.keys)
        assertEquals(
            existingServer.id,
            serverRegistry.savedCredentials[existingServer.id]?.serverId,
        )
        assertEquals("reader", serverRegistry.savedCredentials[existingServer.id]?.username)
        assertEquals(
            "new-test-token",
            serverRegistry.savedCredentials[existingServer.id]?.accessToken,
        )
    }

    @Test
    fun `reauthentication fails before login when saved server id does not exist`() = runTest {
        // Given
        val authenticator = FakeStorytellerAuthenticator()
        val serverRegistry = FakeServerRegistry()
        val classUnderTest = createRepository(serverRegistry, authenticator)

        // When
        val result = classUnderTest.login(
            serverType = ServerType.Storyteller,
            serverUrl = "https://storyteller.example",
            username = "reader",
            password = "test-password",
            existingServerId = "missing-server",
        )

        // Then
        assertFalse(result.isOk)
        assertEquals(0, authenticator.loginCount)
        assertEquals(0, serverRegistry.addServerCount)
        assertTrue(serverRegistry.savedCredentials.isEmpty())
    }

    @Test
    fun `reauthentication refuses a non Storyteller server`() = runTest {
        // Given
        val existingServer = storytellerServer().copy(type = ServerType.Audiobookshelf)
        val authenticator = FakeStorytellerAuthenticator()
        val serverRegistry = FakeServerRegistry(existingServer)
        val classUnderTest = createRepository(serverRegistry, authenticator)

        // When
        val result = classUnderTest.login(
            serverType = ServerType.Storyteller,
            serverUrl = existingServer.baseUrl,
            username = "reader",
            password = "test-password",
            existingServerId = existingServer.id,
        )

        // Then
        assertFalse(result.isOk)
        assertEquals(0, authenticator.loginCount)
        assertEquals(0, serverRegistry.addServerCount)
        assertTrue(serverRegistry.savedCredentials.isEmpty())
        assertEquals(existingServer, serverRegistry.servers[existingServer.id])
    }

    private fun createRepository(
        serverRegistry: FakeServerRegistry,
        authenticator: FakeStorytellerAuthenticator,
    ) = LoginDataRepository(
        authenticatorFactory = object : ServerAuthenticatorFactory {
            override fun create(serverType: ServerType): ServerAuthenticator {
                assertEquals(ServerType.Storyteller, serverType)
                return authenticator
            }
        },
        storytellerOAuthSessionLauncher = object : StorytellerOAuthSessionLauncher {
            override suspend fun requestAppToken(serverUrl: String): AppResult<String> =
                error("OAuth is not used by password reauthentication tests")
        },
        analytics = FakeAnalytics,
        serverRegistry = serverRegistry,
    )
}

private fun storytellerServer() = ServerConfig(
    id = "storyteller-1",
    name = "Storyteller",
    type = ServerType.Storyteller,
    baseUrl = "https://storyteller.example",
    addedAt = 1L,
)

private class FakeStorytellerAuthenticator : ServerAuthenticator {
    override val serverType: ServerType = ServerType.Storyteller
    var lastBaseUrl: String? = null
    var loginCount: Int = 0

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        lastBaseUrl = baseUrl
        loginCount += 1
        return Ok(
            ServerCredentials(
                serverId = "",
                username = username,
                accessToken = "new-test-token",
            ),
        )
    }

    override suspend fun refreshToken(
        baseUrl: String,
        refreshToken: String,
    ): AppResult<ServerCredentials> = Err(AppError.AuthError("Unused in this test"))

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> =
        Ok(ServerValidationResult(true, null, "Storyteller", null))
}

private object FakeAnalytics : Analytics {
    override fun logException(throwable: Throwable, message: String?) = Unit
    override fun logEvent(event: AnalyticsEvent) = Unit
    override fun setUserId(userId: String?) = Unit
}

private class FakeServerRegistry(vararg initialServers: ServerConfig) : ServerRegistry {
    val servers = initialServers.associateByTo(mutableMapOf()) { server -> server.id }
    val savedCredentials = mutableMapOf<String, ServerCredentials>()
    val updatedServers = mutableListOf<ServerConfig>()
    var addServerCount: Int = 0

    override fun observeAllServers(): Flow<List<ServerConfig>> = flowOf(servers.values.toList())
    override suspend fun getAllServers(): List<ServerConfig> = servers.values.toList()

    override suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig {
        addServerCount += 1
        return storytellerServer().copy(
            id = "new-server",
            name = name,
            type = type,
            baseUrl = baseUrl,
        )
    }

    override suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = ServerConfig(id, name, type, baseUrl, addedAt = 1L)

    override suspend fun updateServer(config: ServerConfig) {
        updatedServers += config
        servers[config.id] = config
    }

    override suspend fun removeServer(serverId: String) {
        servers.remove(serverId)
        savedCredentials.remove(serverId)
    }

    override suspend fun getServer(serverId: String): ServerConfig? = servers[serverId]

    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = flowOf(emptyMap())
    override fun observeAuthState(serverId: String): Flow<ServerAuthState> =
        flowOf(ServerAuthState.NotAuthenticated(serverId))
    override suspend fun isAuthenticated(serverId: String): Boolean =
        savedCredentials.containsKey(serverId)
    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = flowOf(emptyList())
    override suspend fun getAuthenticatedServers(): List<ServerConfig> = emptyList()

    override suspend fun saveCredentials(credentials: ServerCredentials) {
        savedCredentials[credentials.serverId] = credentials
    }

    override suspend fun getCredentials(serverId: String): ServerCredentials? =
        savedCredentials[serverId]

    override suspend fun clearCredentials(serverId: String) {
        savedCredentials.remove(serverId)
    }

    override suspend fun clearAllCredentials() {
        savedCredentials.clear()
    }

    override suspend fun deactivateServer(serverId: String) {
        clearCredentials(serverId)
    }
}
