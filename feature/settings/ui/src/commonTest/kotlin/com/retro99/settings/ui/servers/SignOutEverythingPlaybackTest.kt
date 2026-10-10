package com.retro99.settings.ui.servers

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.catalogue.ui.settings.CatalogueSettingsGateway
import com.retro99.catalogue.ui.settings.CatalogueSettingsSource
import com.retro99.catalogue.ui.add.CatalogueValidation
import com.retro99.server.api.CatalogueAccessProvider
import com.retro99.server.api.CatalogueSourceStatus
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.settings.domain.SettingsRepository
import com.retro99.settings.domain.usecase.LogoutUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Sign out of everything" must silence the audio of every server it signs out of, and leave
 * a local book reading: the local library is never signed out (QA-BUG-0049, decision 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutEverythingPlaybackTest {

    private val local = ServerConfig(LOCAL_SERVER_ID, "This device", ServerType.Local, "", 0)
    private val storyteller =
        ServerConfig("storyteller", "Storyteller", ServerType.Storyteller, "https://s.example/", 0)
    private val audiobookshelf =
        ServerConfig("abs", "Audiobookshelf", ServerType.Audiobookshelf, "https://a.example/", 0)

    @BeforeTest
    fun setUp() {
        // BaseViewModel's viewModelScope is a main-dispatcher scope.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun signing_out_of_everything_stops_the_audio_of_every_remote_server() = runTest {
        val stopped = mutableListOf<String>()
        val registry = FakeServerRegistry(listOf(local, storyteller, audiobookshelf))

        viewModel(registry) { serverId, _ -> stopped += serverId }
            .onIntent(ServerManagementIntent.OnSignOutEverything)

        assertEquals(listOf("storyteller", "abs"), stopped)
        assertEquals(listOf("storyteller", "abs"), registry.cleared)
    }

    @Test
    fun signing_out_of_everything_never_stops_a_local_book() = runTest {
        val stopped = mutableListOf<String>()
        val registry = FakeServerRegistry(listOf(local))

        viewModel(registry) { serverId, _ -> stopped += serverId }
            .onIntent(ServerManagementIntent.OnSignOutEverything)

        assertEquals(emptyList(), stopped)
        assertEquals(emptyList(), registry.cleared)
    }

    private fun viewModel(
        registry: FakeServerRegistry,
        stopPlaybackForServer: (String, DiagnosticContext) -> Unit,
    ) = ServerManagementViewModel(
        serverRegistry = registry,
        analytics = RecordingAnalytics(),
        catalogueAccessProvider = object : CatalogueAccessProvider {
            override fun observeSources(): Flow<List<CatalogueSourceStatus>> = emptyFlow()
        },
        catalogueSettings = UnusedCatalogueSettings,
        logoutAll = LogoutUseCase(FakeSettingsRepository(registry)),
        onNavigateToLogin = { _, _ -> },
        stopPlaybackForServer = stopPlaybackForServer,
    )

    private class FakeSettingsRepository(
        private val registry: ServerRegistry,
    ) : SettingsRepository {
        override suspend fun logout(serverId: String?) {
            if (serverId != null) {
                registry.clearCredentials(serverId)
            } else {
                registry.getAllServers()
                    .filter { it.type != ServerType.Local }
                    .forEach { registry.clearCredentials(it.id) }
            }
        }
    }

    private class FakeServerRegistry(private val servers: List<ServerConfig>) : ServerRegistry {
        val cleared = mutableListOf<String>()
        override fun observeAllServers(): Flow<List<ServerConfig>> = flowOf(servers)
        override suspend fun getAllServers(): List<ServerConfig> = servers
        override suspend fun clearCredentials(serverId: String) { cleared += serverId }
        override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = flowOf(emptyMap())
        override suspend fun getServer(serverId: String) = servers.find { it.id == serverId }
        override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = flowOf(servers)
        override suspend fun getAuthenticatedServers(): List<ServerConfig> = servers
        override suspend fun addServer(name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
        override suspend fun addServerWithId(id: String, name: String, type: ServerType, baseUrl: String): ServerConfig = error("unused")
        override suspend fun updateServer(config: ServerConfig) = error("unused")
        override suspend fun removeServer(serverId: String) = error("unused")
        override fun observeAuthState(serverId: String): Flow<ServerAuthState> = error("unused")
        override suspend fun isAuthenticated(serverId: String) = false
        override suspend fun saveCredentials(credentials: ServerCredentials) = error("unused")
        override suspend fun getCredentials(serverId: String): ServerCredentials? = null
        override suspend fun clearAllCredentials() = error("unused")
        override suspend fun deactivateServer(serverId: String) = error("unused")
    }

    private object UnusedCatalogueSettings : CatalogueSettingsGateway {
        override fun observeSource(sourceId: String): Flow<CatalogueSettingsSource?> = error("unused")
        override suspend fun existingAddresses(profileId: String, sourceId: String): Set<String> = error("unused")
        override suspend fun updateAddress(
            source: CatalogueSettingsSource,
            address: String,
            account: OpdsAccountDetails?,
            validation: CatalogueValidation,
        ) = error("unused")
        override suspend fun saveAccount(source: CatalogueSettingsSource, account: OpdsAccountDetails) = error("unused")
        override suspend fun removeAccount(source: CatalogueSettingsSource) = error("unused")
        override suspend fun setEnabled(source: CatalogueSettingsSource, enabled: Boolean) = error("unused")
        override suspend fun countBooks(source: CatalogueSettingsSource): Long = error("unused")
        override suspend fun removeCatalogue(source: CatalogueSettingsSource) = error("unused")
        override suspend fun retry(source: CatalogueSettingsSource) = error("unused")
    }

    private class RecordingAnalytics : Analytics {
        override fun logException(throwable: Throwable, message: String?) = Unit
        override fun logException(throwable: Throwable, context: DiagnosticContext) = Unit
        override fun logBreadcrumb(context: DiagnosticContext) = Unit
        override fun logEvent(event: AnalyticsEvent) = Unit
        override fun setUserId(userId: String?) = Unit
    }
}
