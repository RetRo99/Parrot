package com.retro99.settings.ui.servers

import androidx.lifecycle.ViewModelStore
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.server.ServerType
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.library.domain.grouping.AudiobookshelfPairingResult
import com.retro99.library.domain.grouping.LibrarySourceIdentityPairingRepository
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.NativeBookId
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ServerManagementPairingViewModelTest {

    @Test
    fun `create code uses active profile and selected ABS connection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = harness()
        try {
            advanceUntilIdle()

            // Given
            harness.viewModel.onIntent(ServerManagementIntent.OnPairingClick("abs-1"))

            // When
            harness.viewModel.onIntent(ServerManagementIntent.OnCreatePairingCodeClick)
            advanceUntilIdle()

            // Then
            assertEquals(LibraryProfileId("profile-a"), harness.pairingRepository.lastProfileId)
            assertEquals("abs-1", harness.pairingRepository.lastServerId)
            assertEquals("profile-a", harness.cloudAccountRepository.selectedProfileId)
            assertEquals("pairing-code", harness.viewModel.viewState.value.displayedPairingCode)
            assertEquals(
                AudiobookshelfPairingActionStatus.Completed,
                harness.viewModel.viewState.value.pairingActionStatus,
            )
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `import code binds selected ABS connection under active profile`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = harness()
        try {
            advanceUntilIdle()
            harness.viewModel.onIntent(ServerManagementIntent.OnPairingClick("abs-1"))
            harness.viewModel.onIntent(
                ServerManagementIntent.OnPairingCodeChanged("  imported-code  "),
            )

            // When
            harness.viewModel.onIntent(ServerManagementIntent.OnImportPairingCodeClick)
            advanceUntilIdle()

            // Then
            assertEquals("imported-code", harness.pairingRepository.lastImportedCode)
            assertEquals(LibraryProfileId("profile-a"), harness.pairingRepository.lastProfileId)
            assertNull(harness.viewModel.viewState.value.displayedPairingCode)
            assertEquals(
                AudiobookshelfPairingActionStatus.Completed,
                harness.viewModel.viewState.value.pairingActionStatus,
            )
            assertEquals(PairingAction.ImportCode, harness.viewModel.viewState.value.pairingAction)
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `changing cloud account clears displayed pairing code`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = harness()
        try {
            advanceUntilIdle()
            harness.viewModel.onIntent(ServerManagementIntent.OnPairingClick("abs-1"))
            harness.viewModel.onIntent(ServerManagementIntent.OnCreatePairingCodeClick)
            advanceUntilIdle()
            assertEquals("pairing-code", harness.viewModel.viewState.value.displayedPairingCode)

            // When
            harness.cloudAccountRepository.authState.value = signedIn("cloud-account-b")
            advanceUntilIdle()

            // Then
            assertNull(harness.viewModel.viewState.value.displayedPairingCode)
            assertNull(harness.viewModel.viewState.value.pairingServerId)
            assertNull(harness.viewModel.viewState.value.pairingActionStatus)
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `signing out clears displayed pairing code`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = harness()
        try {
            advanceUntilIdle()
            harness.viewModel.onIntent(ServerManagementIntent.OnPairingClick("abs-1"))
            harness.viewModel.onIntent(ServerManagementIntent.OnCreatePairingCodeClick)
            advanceUntilIdle()
            assertEquals("pairing-code", harness.viewModel.viewState.value.displayedPairingCode)

            // When
            harness.cloudAccountRepository.authState.value = CloudAuthState.SignedOut
            advanceUntilIdle()

            // Then
            assertNull(harness.viewModel.viewState.value.displayedPairingCode)
            assertNull(harness.viewModel.viewState.value.pairingServerId)
            assertNull(harness.viewModel.viewState.value.pairingActionStatus)
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `blocked unpair shows membership count and keeps paired status`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pairingRepository = FakePairingRepository().apply {
            status.value = LibrarySourceIdentityPairingStatus.Paired
            revokeResult = AudiobookshelfPairingResult(
                status = AudiobookshelfPairingActionStatus.HasSharedMemberships,
                conflictingNativeBookIds = setOf(NativeBookId("book-a"), NativeBookId("book-b")),
                blockingMembershipCount = 3,
            )
        }
        val harness = harness(pairingRepository)
        try {
            advanceUntilIdle()
            harness.viewModel.onIntent(ServerManagementIntent.OnPairingClick("abs-1"))

            // When
            harness.viewModel.onIntent(ServerManagementIntent.OnUnpairClick)
            advanceUntilIdle()

            // Then
            assertEquals(
                AudiobookshelfPairingActionStatus.HasSharedMemberships,
                harness.viewModel.viewState.value.pairingActionStatus,
            )
            assertEquals(3, harness.viewModel.viewState.value.pairingConflictCount)
            assertEquals(
                LibrarySourceIdentityPairingStatus.Paired,
                harness.viewModel.viewState.value.pairingStatuses["abs-1"],
            )
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `login action routes existing Storyteller server id`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val navigatedServerIds = mutableListOf<String?>()
        val harness = harness(
            serverType = ServerType.Storyteller,
            onNavigateToLogin = { serverId -> navigatedServerIds += serverId },
        )
        try {
            advanceUntilIdle()

            // When
            harness.viewModel.onIntent(ServerManagementIntent.OnLoginClick("abs-1"))

            // Then
            assertEquals(listOf<String?>("abs-1"), navigatedServerIds)
        } finally {
            harness.store.clear()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    private fun harness(
        pairingRepository: FakePairingRepository = FakePairingRepository(),
        serverType: ServerType = ServerType.Audiobookshelf,
        onNavigateToLogin: (String?) -> Unit = {},
    ): Harness {
        val serverRegistry = FakeServerRegistry(serverType)
        val cloudAccountRepository = FakeCloudAccountRepository()
        val store = ViewModelStore()
        val viewModel = ServerManagementViewModel(
            serverRegistry = serverRegistry,
            analytics = FakeAnalytics,
            pairingRepository = pairingRepository,
            cloudAccountRepository = cloudAccountRepository,
            userRegistry = FakeUserRegistry(),
            onNavigateToLogin = onNavigateToLogin,
        )
        store.put("server-management", viewModel)
        return Harness(viewModel, store, pairingRepository, cloudAccountRepository)
    }

    private data class Harness(
        val viewModel: ServerManagementViewModel,
        val store: ViewModelStore,
        val pairingRepository: FakePairingRepository,
        val cloudAccountRepository: FakeCloudAccountRepository,
    )
}

private class FakePairingRepository : LibrarySourceIdentityPairingRepository {
    val status = MutableStateFlow(LibrarySourceIdentityPairingStatus.Unpaired)
    var lastProfileId: LibraryProfileId? = null
    var lastServerId: String? = null
    var lastImportedCode: String? = null
    var createResult = AudiobookshelfPairingResult(
        status = AudiobookshelfPairingActionStatus.Completed,
        code = "pairing-code",
    )
    var bindResult = AudiobookshelfPairingResult(AudiobookshelfPairingActionStatus.Completed)
    var revokeResult = AudiobookshelfPairingResult(AudiobookshelfPairingActionStatus.Completed)

    override fun observeAudiobookshelfPairingStatus(
        profileId: LibraryProfileId,
        serverId: String,
    ): Flow<LibrarySourceIdentityPairingStatus> {
        return status
    }

    override suspend fun createAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult {
        record(profileId, serverId)
        return createResult
    }

    override suspend fun bindAudiobookshelfPairingCode(
        profileId: LibraryProfileId,
        serverId: String,
        code: String,
    ): AudiobookshelfPairingResult {
        record(profileId, serverId)
        lastImportedCode = code
        return bindResult
    }

    override suspend fun revokeAudiobookshelfPairing(
        profileId: LibraryProfileId,
        serverId: String,
    ): AudiobookshelfPairingResult {
        record(profileId, serverId)
        return revokeResult
    }

    private fun record(profileId: LibraryProfileId, serverId: String) {
        lastProfileId = profileId
        lastServerId = serverId
    }
}

private class FakeServerRegistry(serverType: ServerType) : ServerRegistry {
    private val server = ServerConfig(
        id = "abs-1",
        name = serverType.displayName,
        type = serverType,
        baseUrl = "http://audiobookshelf.test",
        addedAt = 1L,
    )
    private val servers = MutableStateFlow(listOf(server))
    private val authStates = MutableStateFlow(
        mapOf(
            server.id to ServerAuthState.Authenticated(
                serverId = server.id,
                username = "reader",
                authenticatedAt = 1L,
            ),
        ),
    )

    override fun observeAllServers(): Flow<List<ServerConfig>> = servers
    override suspend fun getAllServers(): List<ServerConfig> = servers.value
    override suspend fun addServer(name: String, type: ServerType, baseUrl: String) = server
    override suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ) = server
    override suspend fun updateServer(config: ServerConfig) = Unit
    override suspend fun removeServer(serverId: String) = Unit
    override suspend fun getServer(serverId: String): ServerConfig? =
        servers.value.firstOrNull { server -> server.id == serverId }
    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> = authStates
    override fun observeAuthState(serverId: String): Flow<ServerAuthState> =
        flowOf(authStates.value[serverId] ?: ServerAuthState.NotAuthenticated(serverId))
    override suspend fun isAuthenticated(serverId: String): Boolean = true
    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = flowOf(servers.value)
    override suspend fun getAuthenticatedServers(): List<ServerConfig> = servers.value
    override suspend fun saveCredentials(credentials: ServerCredentials) = Unit
    override suspend fun getCredentials(serverId: String): ServerCredentials? = null
    override suspend fun clearCredentials(serverId: String) = Unit
    override suspend fun clearAllCredentials() = Unit
    override suspend fun deactivateServer(serverId: String) = Unit
}

private class FakeUserRegistry : UserRegistry {
    private val profile = UserProfile("profile-a", "Reader", createdAt = 1L)

    override fun observeAllProfiles(): Flow<List<UserProfile>> = flowOf(listOf(profile))
    override suspend fun getAllProfiles(): List<UserProfile> = listOf(profile)
    override suspend fun createProfile(
        id: String?,
        name: String,
        avatarId: Int?,
    ): UserProfile = profile
    override suspend fun updateProfile(profile: UserProfile) = Unit
    override suspend fun deleteProfile(profileId: String) = Unit
    override suspend fun getProfile(profileId: String): UserProfile? =
        profile.takeIf { item -> item.id == profileId }
    override fun observeActiveProfile(): Flow<UserProfile?> = flowOf(profile)
    override suspend fun getActiveProfile(): UserProfile = profile
    override fun getActiveProfileId(): String = profile.id
    override suspend fun setActiveProfile(profileId: String) = Unit
    override suspend fun clearActiveProfile() = Unit
    override suspend fun hasProfiles(): Boolean = true
    override fun isProfileActive(): Boolean = true
}

private class FakeCloudAccountRepository : CloudAccountRepository {
    val authState = MutableStateFlow<CloudAuthState>(signedIn("cloud-account-a"))
    var selectedProfileId: String? = null

    override fun observeAuthState(): Flow<CloudAuthState> = authState
    override fun currentAuthState(): CloudAuthState = authState.value
    override suspend fun <T> withProfileSession(
        localProfileId: String,
        operation: suspend () -> T,
    ): T {
        selectedProfileId = localProfileId
        return operation()
    }
    override suspend fun register(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudRegistrationResult = error("Unused in this test")
    override suspend fun signIn(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudAccount = error("Unused in this test")

    override suspend fun signInWithGoogle(localProfileId: String): CloudAccount =
        error("Unused in this test")
    override suspend fun restoreSession(localProfileId: String): CloudAuthState = authState.value
    override suspend fun signOut(localProfileId: String) = Unit
    override suspend fun deleteAccount(localProfileId: String) = Unit
}

private object FakeAnalytics : Analytics {
    override fun logException(throwable: Throwable, message: String?) = Unit
    override fun logEvent(event: AnalyticsEvent) = Unit
    override fun setUserId(userId: String?) = Unit
}

private fun signedIn(accountId: String) = CloudAuthState.SignedIn(
    CloudAccount(id = accountId, email = "$accountId@example.test"),
)
