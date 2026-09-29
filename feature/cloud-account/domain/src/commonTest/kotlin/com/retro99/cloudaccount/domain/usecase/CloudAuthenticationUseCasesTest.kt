package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudAccountException
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CloudAuthenticationUseCasesTest {
    private val userRegistry = FakeUserRegistry()
    private val accountRepository = FakeCloudAccountRepository()
    private val pendingAuthenticationRepository = FakePendingCloudAuthenticationRepository()
    private val profileLinkRepository = FakeCloudProfileLinkRepository()

    @Test
    fun `registration rejects authentication completed for a different profile`() = runTest {
        val classUnderTest = RegisterCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onRegister = {
            userRegistry.switchTo("profile-b")
            CloudRegistrationResult.SignedIn(account)
        }

        assertFailsWith<IllegalArgumentException> {
            classUnderTest("reader@example.com", "password", tosAccepted = false)
        }

        assertFailsWith<IllegalStateException> {
            classUnderTest("reader@example.com", "password", tosAccepted = true)
        }

        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `google sign in preserves the originating profile`() = runTest {
        val classUnderTest = SignInCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onGoogleSignIn = { account }

        val result = classUnderTest.signInWithGoogle()

        assertEquals(account, result)
        val pendingAuthentication = pendingAuthenticationRepository.get("profile-a")
        assertEquals(account.id, pendingAuthentication?.cloudUserId)
        assertEquals(account.email, pendingAuthentication?.email)
    }

    @Test
    fun `sign in reports pending local authentication persistence failure with safe context`() = runTest {
        val classUnderTest = SignInCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        val persistenceFailure = IllegalStateException("private preference payload")
        accountRepository.onSignIn = { account }
        pendingAuthenticationRepository.onSave = { authentication ->
            pendingAuthenticationRepository.add(authentication)
            throw persistenceFailure
        }

        val error = assertFailsWith<CloudAccountException.LocalStatePersistence> {
            classUnderTest("reader@example.com", "password")
        }

        assertEquals("Cloud account state could not be saved on this device", error.message)
        assertEquals(persistenceFailure, error.cause)
        assertEquals(1, accountRepository.signOutCount)
        assertEquals(CloudAuthState.SignedOut, accountRepository.authState)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `sign in cancellation during local persistence is not wrapped`() = runTest {
        val classUnderTest = SignInCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onSignIn = { account }
        pendingAuthenticationRepository.onSave = { authentication ->
            pendingAuthenticationRepository.add(authentication)
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            classUnderTest("reader@example.com", "password")
        }
        assertEquals(1, accountRepository.signOutCount)
        assertEquals(CloudAuthState.SignedOut, accountRepository.authState)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `registration local persistence failure is bounded and typed`() = runTest {
        val classUnderTest = RegisterCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onRegister = {
            CloudRegistrationResult.SignedIn(account).also {
                accountRepository.authState = CloudAuthState.SignedIn(account)
            }
        }
        pendingAuthenticationRepository.onSave = { authentication ->
            pendingAuthenticationRepository.add(authentication)
            throw IllegalStateException("private preference payload")
        }

        val error = assertFailsWith<CloudAccountException.LocalStatePersistence> {
            classUnderTest("reader@example.com", "password", tosAccepted = true)
        }

        assertEquals("Cloud account state could not be saved on this device", error.message)
        assertEquals(1, accountRepository.signOutCount)
        assertEquals(CloudAuthState.SignedOut, accountRepository.authState)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `auth persistence failure still clears pending record when session rollback throws`() = runTest {
        val classUnderTest = SignInCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onSignIn = { account }
        accountRepository.onSignOut = { throw IllegalStateException("rollback failed") }
        pendingAuthenticationRepository.onSave = { authentication ->
            pendingAuthenticationRepository.add(authentication)
            throw IllegalStateException("persistence failed")
        }

        val error = assertFailsWith<CloudAccountException.LocalStatePersistence> {
            classUnderTest("reader@example.com", "password")
        }

        assertEquals(true, error.cleanupFailed)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
        assertEquals(CloudAuthState.SignedIn(account), accountRepository.authState)
    }

    @Test
    fun `restored signed in session can continue profile linking`() = runTest {
        val classUnderTest = RestoreCloudSessionUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)

        val result = classUnderTest()

        assertIs<CloudAuthState.SignedIn>(result)
        val pendingAuthentication = pendingAuthenticationRepository.get("profile-a")
        assertEquals("profile-a", pendingAuthentication?.localProfileId)
        assertEquals(account.id, pendingAuthentication?.cloudUserId)
        assertEquals(account.email, pendingAuthentication?.email)
    }

    @Test
    fun `enabling auto backup requires checkbox acceptance and records it before enabling`() = runTest {
        profileLinkRepository.addLink(
            CloudProfileLink(
                localProfileId = "profile-a",
                cloudUserId = account.id,
                syncEnabled = false,
            ),
        )
        val attestationRepository = FakeUploadRightsAttestationRepository()
        val classUnderTest = SetAutoBackupEnabledUseCase(
            profileLinkRepository = profileLinkRepository,
            uploadRightsAttestationRepository = attestationRepository,
            userRegistry = userRegistry,
        )

        assertFailsWith<IllegalStateException> {
            classUnderTest(enabled = true)
        }
        assertEquals(false, profileLinkRepository.getForLocalProfile("profile-a")?.autoBackupEnabled)
        assertEquals(0, attestationRepository.recordCount)

        val enabledLink = classUnderTest(enabled = true, rightsAttested = true)

        assertEquals(true, enabledLink.autoBackupEnabled)
        assertEquals(1, attestationRepository.recordCount)
    }

    @Test
    fun `restoration keeps an existing link without pending authentication`() = runTest {
        val classUnderTest = RestoreCloudSessionUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)
        profileLinkRepository.addLink(
            CloudProfileLink(
                localProfileId = "profile-a",
                cloudUserId = account.id,
                syncEnabled = false,
            ),
        )

        val result = classUnderTest()

        assertIs<CloudAuthState.SignedIn>(result)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `enabling cloud sync enables the active profile link`() = runTest {
        profileLinkRepository.addLink(
            CloudProfileLink(
                localProfileId = "profile-a",
                cloudUserId = account.id,
                syncEnabled = false,
            ),
        )
        val classUnderTest = EnableCloudSyncUseCase(
            profileLinkRepository = profileLinkRepository,
            userRegistry = userRegistry,
        )

        val result = classUnderTest()

        assertEquals(true, result.syncEnabled)
    }

    @Test
    fun `deleting cloud account removes the remote account before unlinking the profile`() = runTest {
        profileLinkRepository.addLink(
            CloudProfileLink(
                localProfileId = "profile-a",
                cloudUserId = account.id,
                syncEnabled = true,
            ),
        )
        val events = mutableListOf<String>()
        accountRepository.onDeleteAccount = { events += "delete-account" }
        profileLinkRepository.onUnlink = { events += "unlink-profile" }
        val classUnderTest = DeleteCloudAccountUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )

        classUnderTest()

        assertEquals(listOf("delete-account", "unlink-profile"), events)
        assertNull(profileLinkRepository.getForLocalProfile("profile-a"))
    }

    @Test
    fun `registration awaiting verification preserves the originating profile`() = runTest {
        val classUnderTest = RegisterCloudAccountUseCase(
            accountRepository = accountRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.onRegister = {
            CloudRegistrationResult.AwaitingEmailVerification("reader@example.com")
        }

        classUnderTest("reader@example.com", "password", tosAccepted = true)

        val pendingAuthentication = pendingAuthenticationRepository.get("profile-a")
        assertEquals("profile-a", pendingAuthentication?.localProfileId)
        assertNull(pendingAuthentication?.cloudUserId)
        assertEquals("reader@example.com", pendingAuthentication?.email)
    }

    @Test
    fun `verified account can link from pending verification`() = runTest {
        val classUnderTest = LinkCloudAccountUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)
        pendingAuthenticationRepository.save(
            PendingCloudAuthentication(
                localProfileId = "profile-a",
                cloudUserId = null,
                email = "reader@example.com",
                createdAt = 0L,
            ),
        )

        val result = classUnderTest()

        assertIs<CloudProfileLinkResult.Linked>(result)
        assertEquals(account.id, result.link.cloudUserId)
        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    @Test
    fun `linking rejects a pending authentication for a different cloud account`() = runTest {
        val classUnderTest = LinkCloudAccountUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)
        pendingAuthenticationRepository.save(
            PendingCloudAuthentication(
                localProfileId = "profile-a",
                cloudUserId = "other-account",
                email = "reader@example.com",
                createdAt = 0L,
            ),
        )

        assertFailsWith<IllegalStateException> {
            classUnderTest()
        }
    }

    @Test
    fun `linking rejects a pending email that does not match the signed in account`() = runTest {
        val classUnderTest = LinkCloudAccountUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)
        pendingAuthenticationRepository.save(
            PendingCloudAuthentication(
                localProfileId = "profile-a",
                cloudUserId = null,
                email = "other@example.com",
                createdAt = 0L,
            ),
        )

        assertFailsWith<IllegalStateException> {
            classUnderTest()
        }
    }

    @Test
    fun `restoration rejects authentication completed for a different profile`() = runTest {
        val classUnderTest = RestoreCloudSessionUseCase(
            accountRepository = accountRepository,
            profileLinkRepository = profileLinkRepository,
            pendingAuthenticationRepository = pendingAuthenticationRepository,
            userRegistry = userRegistry,
        )
        accountRepository.authState = CloudAuthState.SignedIn(account)
        accountRepository.onRestore = { userRegistry.switchTo("profile-b") }

        assertFailsWith<IllegalStateException> {
            classUnderTest()
        }

        assertNull(pendingAuthenticationRepository.get("profile-a"))
    }

    private companion object {
        val account = CloudAccount(
            id = "cloud-account",
            email = "reader@example.com",
        )
    }
}

private class FakeCloudAccountRepository : CloudAccountRepository {
    var authState: CloudAuthState = CloudAuthState.SignedOut
    var signOutCount: Int = 0
    var onSignOut: suspend () -> Unit = { authState = CloudAuthState.SignedOut }
    var onRegister: suspend () -> CloudRegistrationResult = {
        CloudRegistrationResult.SignedIn(
            CloudAccount(
                id = "cloud-account",
                email = "reader@example.com",
            ),
        )
    }
    var onGoogleSignIn: suspend () -> CloudAccount = { error("Not used") }
    var onSignIn: suspend () -> CloudAccount = { error("Not used") }
    var onRestore: suspend () -> Unit = {}
    var onDeleteAccount: suspend () -> Unit = {}

    override fun observeAuthState(): Flow<CloudAuthState> = flowOf(authState)

    override fun currentAuthState(): CloudAuthState = authState

    override suspend fun <T> withProfileSession(
        localProfileId: String,
        operation: suspend () -> T,
    ): T = operation()

    override suspend fun register(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudRegistrationResult = onRegister().also { result ->
        if (result is CloudRegistrationResult.SignedIn) {
            authState = CloudAuthState.SignedIn(result.account)
        }
    }

    override suspend fun signIn(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudAccount = onSignIn().also { authState = CloudAuthState.SignedIn(it) }

    override suspend fun signInWithGoogle(localProfileId: String): CloudAccount = onGoogleSignIn().also {
        authState = CloudAuthState.SignedIn(it)
    }

    override suspend fun restoreSession(localProfileId: String): CloudAuthState {
        onRestore()
        return authState
    }

    override suspend fun signOut(localProfileId: String) {
        signOutCount++
        onSignOut()
    }

    override suspend fun deleteAccount(localProfileId: String) = onDeleteAccount()
}

private class FakeCloudProfileLinkRepository : CloudProfileLinkRepository {
    private val links = mutableListOf<CloudProfileLink>()
    var onUnlink: suspend () -> Unit = {}

    fun addLink(link: CloudProfileLink) {
        links += link
    }

    override suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink? {
        return links.firstOrNull { link -> link.localProfileId == localProfileId }
    }

    override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? {
        return links.firstOrNull { link -> link.cloudUserId == cloudUserId }
    }

    override suspend fun link(
        localProfileId: String,
        cloudUserId: String,
    ): CloudProfileLinkResult {
        val link = CloudProfileLink(
            localProfileId = localProfileId,
            cloudUserId = cloudUserId,
            syncEnabled = false,
        )
        links += link
        return CloudProfileLinkResult.Linked(link)
    }

    override suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean) {
        val index = links.indexOfFirst { link -> link.localProfileId == localProfileId }
        if (index >= 0) {
            links[index] = links[index].copy(syncEnabled = enabled)
        }
    }

    override suspend fun setAutoBackupEnabled(localProfileId: String, enabled: Boolean) {
        val index = links.indexOfFirst { link -> link.localProfileId == localProfileId }
        if (index >= 0) {
            links[index] = links[index].copy(autoBackupEnabled = enabled)
        }
    }

    override suspend fun setUploadAttestation(
        localProfileId: String,
        cloudUserId: String,
        attestation: UploadAttestationRecord,
    ) {
        val index = links.indexOfFirst { link ->
            link.localProfileId == localProfileId && link.cloudUserId == cloudUserId
        }
        if (index >= 0) links[index] = links[index].copy(uploadAttestation = attestation)
    }

    override fun observeForLocalProfile(localProfileId: String): Flow<CloudProfileLink?> {
        return flowOf(links.firstOrNull { link -> link.localProfileId == localProfileId })
    }

    override suspend fun deactivate(localProfileId: String) = Unit

    override suspend fun unlink(localProfileId: String) {
        onUnlink()
        links.removeAll { link -> link.localProfileId == localProfileId }
    }
}

private class FakeUploadRightsAttestationRepository : UploadRightsAttestationRepository {
    var recordCount = 0

    override suspend fun current(localProfileId: String): UploadAttestationRecord? = null

    override suspend fun record(localProfileId: String): UploadAttestationRecord {
        recordCount++
        return UploadAttestationRecord(
            attestedAt = "now",
            tosVersion = "tos-v1",
            attestationVersion = "rights-v1",
        )
    }

    override suspend fun requiresReattestation(localProfileId: String): Boolean = true
}

private class FakePendingCloudAuthenticationRepository : PendingCloudAuthenticationRepository {
    private val authentications = mutableMapOf<String, PendingCloudAuthentication>()
    var onSave: suspend (PendingCloudAuthentication) -> Unit = { authentication ->
        authentications[authentication.localProfileId] = authentication
    }

    override suspend fun get(localProfileId: String): PendingCloudAuthentication? {
        return authentications[localProfileId]
    }

    override suspend fun save(authentication: PendingCloudAuthentication) {
        onSave(authentication)
    }

    override suspend fun clear(localProfileId: String) {
        onClear(localProfileId)
        authentications.remove(localProfileId)
    }

    var onClear: suspend (String) -> Unit = {}

    fun add(authentication: PendingCloudAuthentication) {
        authentications[authentication.localProfileId] = authentication
    }
}

private class FakeUserRegistry : UserRegistry {
    private val profiles = listOf(
        UserProfile(
            id = "profile-a",
            name = "Profile A",
            createdAt = 0L,
        ),
        UserProfile(
            id = "profile-b",
            name = "Profile B",
            createdAt = 0L,
        ),
    )
    private val activeProfile = MutableStateFlow(profiles.first())

    fun switchTo(profileId: String) {
        activeProfile.value = profiles.first { profile -> profile.id == profileId }
    }

    override fun observeAllProfiles(): Flow<List<UserProfile>> = flowOf(profiles)

    override suspend fun getAllProfiles(): List<UserProfile> = profiles

    override suspend fun createProfile(
        id: String?,
        name: String,
        avatarId: Int?,
    ): UserProfile = error("Not used")

    override suspend fun updateProfile(profile: UserProfile) = Unit

    override suspend fun deleteProfile(profileId: String) = Unit

    override suspend fun getProfile(profileId: String): UserProfile? {
        return profiles.firstOrNull { profile -> profile.id == profileId }
    }

    override fun observeActiveProfile(): Flow<UserProfile?> = activeProfile

    override suspend fun getActiveProfile(): UserProfile = activeProfile.value

    override fun getActiveProfileId(): String = activeProfile.value.id

    override suspend fun setActiveProfile(profileId: String) {
        switchTo(profileId)
    }

    override suspend fun clearActiveProfile() = Unit

    override suspend fun hasProfiles(): Boolean = true

    override fun isProfileActive(): Boolean = true
}
