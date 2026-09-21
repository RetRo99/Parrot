package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

        assertFailsWith<IllegalStateException> {
            classUnderTest("reader@example.com", "password")
        }

        assertNull(pendingAuthenticationRepository.get("profile-a"))
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
                initialMergeCompleted = false,
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
                initialMergeCompleted = false,
            ),
        )
        val classUnderTest = EnableCloudSyncUseCase(
            profileLinkRepository = profileLinkRepository,
            userRegistry = userRegistry,
        )

        val result = classUnderTest()

        assertEquals(true, result.syncEnabled)
        assertEquals(false, result.initialMergeCompleted)
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

        classUnderTest("reader@example.com", "password")

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
    var onRegister: suspend () -> CloudRegistrationResult = {
        CloudRegistrationResult.SignedIn(
            CloudAccount(
                id = "cloud-account",
                email = "reader@example.com",
            ),
        )
    }
    var onRestore: suspend () -> Unit = {}

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
    ): CloudRegistrationResult = onRegister()

    override suspend fun signIn(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudAccount = error("Not used")

    override suspend fun restoreSession(localProfileId: String): CloudAuthState {
        onRestore()
        return authState
    }

    override suspend fun signOut(localProfileId: String) = Unit
}

private class FakeCloudProfileLinkRepository : CloudProfileLinkRepository {
    private val links = mutableListOf<CloudProfileLink>()

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
            initialMergeCompleted = false,
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

    override suspend fun markInitialMergeCompleted(localProfileId: String) = Unit
}

private class FakePendingCloudAuthenticationRepository : PendingCloudAuthenticationRepository {
    private val authentications = mutableMapOf<String, PendingCloudAuthentication>()

    override suspend fun get(localProfileId: String): PendingCloudAuthentication? {
        return authentications[localProfileId]
    }

    override suspend fun save(authentication: PendingCloudAuthentication) {
        authentications[authentication.localProfileId] = authentication
    }

    override suspend fun clear(localProfileId: String) {
        authentications.remove(localProfileId)
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
