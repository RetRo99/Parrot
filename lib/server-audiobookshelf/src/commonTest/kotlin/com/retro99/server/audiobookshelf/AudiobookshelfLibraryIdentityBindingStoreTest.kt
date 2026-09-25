package com.retro99.server.audiobookshelf

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentityPairingException
import com.retro99.server.api.library.LibrarySourceIdentityPairingFailure
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudiobookshelfLibraryIdentityBindingStoreTest {
    @Test
    fun identityRemainsUnresolvedUntilAConnectionIsExplicitlyPaired() = runTest {
        val fixture = fixture()

        assertNull(fixture.store.currentIdentity(SERVER_ID))
        assertEquals(
            LibrarySourceIdentityPairingStatus.Unpaired,
            fixture.store.observePairingStatus(SERVER_ID).first(),
        )

        val pairingCode = fixture.store.createPairingCode(SERVER_ID)
        val identity = fixture.store.currentIdentity(SERVER_ID)

        assertNotNull(identity)
        assertEquals("abs-user-a", identity.accountId)
        assertEquals(LibrarySourceIdentityPairingStatus.Paired,
            fixture.store.observePairingStatus(SERVER_ID).first())
        assertTruePairingCode(pairingCode)
    }

    @Test
    fun pairingCodeCanBeImportedOnlyByTheSameCloudAndAudiobookshelfAccounts() = runTest {
        val source = fixture()
        val pairingCode = source.store.createPairingCode(SERVER_ID)
        val secondDevice = fixture()

        secondDevice.store.importPairingCode(SERVER_ID, pairingCode)

        assertEquals(
            source.store.currentIdentity(SERVER_ID),
            secondDevice.store.currentIdentity(SERVER_ID),
        )
    }

    @Test
    fun importRejectsDifferentCloudAccountWithoutPersistingBinding() = runTest {
        val source = fixture()
        val pairingCode = source.store.createPairingCode(SERVER_ID)
        val secondDevice = fixture(cloudAccountId = "cloud-user-b")

        val exception = assertFailsWith<LibrarySourceIdentityPairingException> {
            secondDevice.store.importPairingCode(SERVER_ID, pairingCode)
        }

        assertEquals(LibrarySourceIdentityPairingFailure.CloudAccountMismatch, exception.reason)
        assertNull(secondDevice.store.currentIdentity(SERVER_ID))
        assertEquals(emptyList(), secondDevice.serverRegistry.server.libraryIdentityBindings)
    }

    @Test
    fun importRejectsDifferentAudiobookshelfAccountWithoutPersistingBinding() = runTest {
        val source = fixture()
        val pairingCode = source.store.createPairingCode(SERVER_ID)
        val secondDevice = fixture(sourceAccountId = "abs-user-b")

        val exception = assertFailsWith<LibrarySourceIdentityPairingException> {
            secondDevice.store.importPairingCode(SERVER_ID, pairingCode)
        }

        assertEquals(LibrarySourceIdentityPairingFailure.SourceAccountMismatch, exception.reason)
        assertNull(secondDevice.store.currentIdentity(SERVER_ID))
        assertEquals(emptyList(), secondDevice.serverRegistry.server.libraryIdentityBindings)
    }

    @Test
    fun codeWithUnknownVersionIsRejected() = runTest {
        val fixture = fixture()
        val pairingCode = "parrot-abs-library-v1:" +
            "{\"version\":2,\"cloudAccountId\":\"cloud-user-a\"," +
            "\"sourceAccountId\":\"abs-user-a\",\"backendId\":" +
            "\"audiobookshelf-instance:test\"}"

        val exception = assertFailsWith<LibrarySourceIdentityPairingException> {
            fixture.store.importPairingCode(SERVER_ID, pairingCode)
        }

        assertEquals(
            LibrarySourceIdentityPairingFailure.UnsupportedCodeVersion,
            exception.reason,
        )
    }

    @Test
    fun outboundSyncAuthorizationRequiresTheBoundSignedInCloudAccount() = runTest {
        val fixture = fixture()
        val unpairedIdentity = SourceAccountIdentity.Portable(
            backendId = "$AUDIOBOOKSHELF_BACKEND_PREFIX$VALID_BACKEND_UUID",
            accountId = "abs-user-a",
        )

        assertFalse(fixture.store.isAuthorized(
            LibraryProfileId(PROFILE_ID),
            "cloud-user-a",
            LibraryAdapterId(AUDIOBOOKSHELF_ADAPTER_ID),
            unpairedIdentity,
        ))

        fixture.store.createPairingCode(SERVER_ID)
        val portableIdentity = fixture.store.currentIdentity(SERVER_ID)!!

        assertTrue(fixture.store.isAuthorized(
            LibraryProfileId(PROFILE_ID),
            "cloud-user-a",
            LibraryAdapterId(AUDIOBOOKSHELF_ADAPTER_ID),
            portableIdentity,
        ))

        fixture.cloudAccountRepository.authState = CloudAuthState.SignedOut
        assertFalse(fixture.store.isAuthorized(
            LibraryProfileId(PROFILE_ID),
            "cloud-user-a",
            LibraryAdapterId(AUDIOBOOKSHELF_ADAPTER_ID),
            portableIdentity,
        ))

        fixture.cloudAccountRepository.authState = CloudAuthState.SignedIn(
            CloudAccount(id = "cloud-user-b", email = null),
        )
        assertFalse(fixture.store.isAuthorized(
            LibraryProfileId(PROFILE_ID),
            "cloud-user-a",
            LibraryAdapterId(AUDIOBOOKSHELF_ADAPTER_ID),
            portableIdentity,
        ))

        fixture.cloudAccountRepository.authState = CloudAuthState.SignedIn(
            CloudAccount(id = "cloud-user-a", email = null),
        )
        fixture.serverRegistry.updateServer(
            fixture.serverRegistry.server.copy(libraryIdentityBindings = emptyList()),
        )
        assertFalse(fixture.store.isAuthorized(
            LibraryProfileId(PROFILE_ID),
            "cloud-user-a",
            LibraryAdapterId(AUDIOBOOKSHELF_ADAPTER_ID),
            portableIdentity,
        ))
    }

    @Test
    fun pairingCodeRejectsInvalidBackendIdentityAndUnknownFields() = runTest {
        val fixture = fixture()
        val invalidBackend = "parrot-abs-library-v1:" +
            "{\"version\":1,\"cloudAccountId\":\"cloud-user-a\"," +
            "\"sourceAccountId\":\"abs-user-a\",\"backendId\":" +
            "\"$AUDIOBOOKSHELF_BACKEND_PREFIX${VALID_BACKEND_UUID.dropLast(1)}x\"}"
        val unknownField = "parrot-abs-library-v1:" +
            "{\"version\":1,\"cloudAccountId\":\"cloud-user-a\"," +
            "\"sourceAccountId\":\"abs-user-a\",\"backendId\":" +
            "\"$AUDIOBOOKSHELF_BACKEND_PREFIX$VALID_BACKEND_UUID\"," +
            "\"extra\":true}"

        listOf(invalidBackend, unknownField).forEach { pairingCode ->
            val exception = assertFailsWith<LibrarySourceIdentityPairingException> {
                fixture.store.importPairingCode(SERVER_ID, pairingCode)
            }

            assertEquals(LibrarySourceIdentityPairingFailure.InvalidCode, exception.reason)
            assertEquals(emptyList(), fixture.serverRegistry.server.libraryIdentityBindings)
        }
    }

    private fun assertTruePairingCode(code: String) {
        kotlin.test.assertTrue(code.startsWith("parrot-abs-library-v1:"))
    }

    private fun fixture(
        cloudAccountId: String = "cloud-user-a",
        sourceAccountId: String = "abs-user-a",
    ): StoreFixture {
        val server = ServerConfig(
            id = SERVER_ID,
            name = "Audiobookshelf",
            type = ServerType.Audiobookshelf,
            baseUrl = "https://books.example",
            addedAt = 0L,
        )
        val serverRegistry = FakeServerRegistry(
            server = server,
            credentials = ServerCredentials(
                serverId = SERVER_ID,
                username = "reader",
                accessToken = "test-token",
                accountId = sourceAccountId,
            ),
        )
        val linkRepository = FakeCloudProfileLinkRepository(
            CloudProfileLink(
                localProfileId = PROFILE_ID,
                cloudUserId = cloudAccountId,
                syncEnabled = true,
            ),
        )
        val cloudAccountRepository = FakeCloudAccountRepository(
            authState = CloudAuthState.SignedIn(
                CloudAccount(id = cloudAccountId, email = "reader@example.com"),
            ),
        )
        val userRegistry = FakeUserRegistry(PROFILE_ID)
        return StoreFixture(
            store = AudiobookshelfLibraryIdentityBindingStore(
                serverRegistry = serverRegistry,
                cloudProfileLinkRepository = linkRepository,
                cloudAccountRepository = cloudAccountRepository,
                userRegistry = userRegistry,
            ),
            serverRegistry = serverRegistry,
            cloudAccountRepository = cloudAccountRepository,
        )
    }

    private data class StoreFixture(
        val store: AudiobookshelfLibraryIdentityBindingStore,
        val serverRegistry: FakeServerRegistry,
        val cloudAccountRepository: FakeCloudAccountRepository,
    )

    private class FakeCloudAccountRepository(
        var authState: CloudAuthState,
    ) : CloudAccountRepository {
        override fun observeAuthState(): Flow<CloudAuthState> = MutableStateFlow(authState)

        override fun currentAuthState(): CloudAuthState = authState

        override suspend fun <T> withProfileSession(
            localProfileId: String,
            operation: suspend () -> T,
        ): T = operation()

        override suspend fun register(
            localProfileId: String,
            email: String,
            password: String,
        ): CloudRegistrationResult = error("Registration is not used in pairing tests")

        override suspend fun signIn(
            localProfileId: String,
            email: String,
            password: String,
        ): CloudAccount = error("Sign-in is not used in pairing tests")

        override suspend fun signInWithGoogle(localProfileId: String): CloudAccount =
            error("Sign-in is not used in pairing tests")

        override suspend fun restoreSession(localProfileId: String): CloudAuthState = authState

        override suspend fun signOut(localProfileId: String) {
            authState = CloudAuthState.SignedOut
        }

        override suspend fun deleteAccount(localProfileId: String): Unit =
            error("Account deletion is not used in pairing tests")
    }

    private class FakeServerRegistry(
        server: ServerConfig,
        credentials: ServerCredentials,
    ) : ServerRegistry {
        private val servers = MutableStateFlow(listOf(server))
        private val authState = MutableStateFlow<ServerAuthState>(
            ServerAuthState.Authenticated(SERVER_ID, credentials.username, 0L),
        )
        private var currentCredentials: ServerCredentials? = credentials

        val server: ServerConfig
            get() = servers.value.single()

        override fun observeAllServers(): Flow<List<ServerConfig>> = servers

        override suspend fun getAllServers(): List<ServerConfig> = servers.value

        override suspend fun addServer(
            name: String,
            type: ServerType,
            baseUrl: String,
        ): ServerConfig = error("Adding servers is not used in pairing tests")

        override suspend fun addServerWithId(
            id: String,
            name: String,
            type: ServerType,
            baseUrl: String,
        ): ServerConfig = error("Adding servers is not used in pairing tests")

        override suspend fun updateServer(config: ServerConfig) {
            servers.value = listOf(config)
        }

        override suspend fun removeServer(serverId: String): Unit =
            error("Removing servers is not used in pairing tests")

        override suspend fun getServer(serverId: String): ServerConfig? =
            servers.value.firstOrNull { server -> server.id == serverId }

        override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> =
            authState.map { state -> mapOf(state.serverId to state) }

        override fun observeAuthState(serverId: String): Flow<ServerAuthState> = authState

        override suspend fun isAuthenticated(serverId: String): Boolean =
            authState.value is ServerAuthState.Authenticated

        override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> =
            servers.map { serverList ->
                serverList.filter { serverConfig ->
                    authState.value is ServerAuthState.Authenticated && serverConfig.id == SERVER_ID
                }
            }

        override suspend fun getAuthenticatedServers(): List<ServerConfig> =
            observeAuthenticatedServers().first()

        override suspend fun saveCredentials(credentials: ServerCredentials) {
            currentCredentials = credentials
        }

        override suspend fun getCredentials(serverId: String): ServerCredentials? =
            currentCredentials?.takeIf { credentials -> credentials.serverId == serverId }

        override suspend fun clearCredentials(serverId: String) {
            currentCredentials = null
            authState.value = ServerAuthState.NotAuthenticated(serverId)
        }

        override suspend fun clearAllCredentials() {
            currentCredentials = null
            authState.value = ServerAuthState.NotAuthenticated(SERVER_ID)
        }

        override suspend fun deactivateServer(serverId: String) = clearCredentials(serverId)
    }

    private class FakeCloudProfileLinkRepository(
        private val link: CloudProfileLink,
    ) : CloudProfileLinkRepository {
        override suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink? =
            link.takeIf { value -> value.localProfileId == localProfileId }

        override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? =
            link.takeIf { value -> value.cloudUserId == cloudUserId }

        override fun observeForLocalProfile(localProfileId: String): Flow<CloudProfileLink?> =
            MutableStateFlow(getInitialLink(localProfileId))

        override suspend fun link(
            localProfileId: String,
            cloudUserId: String,
        ): CloudProfileLinkResult = error("Linking accounts is not used in pairing tests")

        override suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean): Unit =
            error("Changing sync is not used in pairing tests")

        override suspend fun setAutoBackupEnabled(localProfileId: String, enabled: Boolean): Unit =
            error("Changing backup is not used in pairing tests")

        override suspend fun setUploadAttestation(
            localProfileId: String,
            cloudUserId: String,
            attestation: UploadAttestationRecord,
        ): Unit = error("Attestation is not used in pairing tests")

        override suspend fun deactivate(localProfileId: String): Unit =
            error("Deactivation is not used in pairing tests")

        override suspend fun unlink(localProfileId: String): Unit =
            error("Unlinking is not used in pairing tests")

        private fun getInitialLink(localProfileId: String) =
            link.takeIf { value -> value.localProfileId == localProfileId }
    }

    private class FakeUserRegistry(profileId: String) : UserRegistry {
        private val activeProfile = MutableStateFlow(
            UserProfile(profileId, "Test profile", createdAt = 0L),
        )

        override fun observeAllProfiles(): Flow<List<UserProfile>> =
            activeProfile.map { profile -> listOfNotNull(profile) }

        override suspend fun getAllProfiles(): List<UserProfile> =
            listOfNotNull(activeProfile.value)

        override suspend fun createProfile(
            id: String?,
            name: String,
            avatarId: Int?,
        ): UserProfile = error("Profile creation is not used in pairing tests")

        override suspend fun updateProfile(profile: UserProfile): Unit =
            error("Profile updates are not used in pairing tests")

        override suspend fun deleteProfile(profileId: String): Unit =
            error("Profile deletion is not used in pairing tests")

        override suspend fun getProfile(profileId: String): UserProfile? =
            activeProfile.value?.takeIf { profile -> profile.id == profileId }

        override fun observeActiveProfile(): Flow<UserProfile?> = activeProfile

        override suspend fun getActiveProfile(): UserProfile? = activeProfile.value

        override fun getActiveProfileId(): String? = activeProfile.value?.id

        override suspend fun setActiveProfile(profileId: String): Unit =
            error("Profile switching is not used in pairing tests")

        override suspend fun clearActiveProfile(): Unit =
            error("Profile clearing is not used in pairing tests")

        override suspend fun hasProfiles(): Boolean = true

        override fun isProfileActive(): Boolean = true
    }

    private companion object {
        const val PROFILE_ID = "profile-a"
        const val SERVER_ID = "abs-connection-a"
        const val AUDIOBOOKSHELF_ADAPTER_ID = "audiobookshelf"
        const val AUDIOBOOKSHELF_BACKEND_PREFIX = "audiobookshelf-instance:"
        const val VALID_BACKEND_UUID = "4b108e2a-d131-4d20-9fc2-0a3fbb65d3a9"
    }
}
