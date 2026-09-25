package com.retro99.cloud.implementation

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, SupabaseInternal::class)
class SupabaseClientProviderTest {
    private lateinit var userRegistry: FakeUserRegistry

    @BeforeTest
    fun setup() {
        userRegistry = FakeUserRegistry()
    }

    @Test
    fun `profile operation catches up after registry changes`() = runTest {
        val provider = unconfiguredProvider()
        provider.withProfileSession("profile-a") { }
        userRegistry.switchTo("profile-b")

        val result = provider.withProfileSession("profile-b") {
            "profile-b"
        }

        assertEquals("profile-b", result)
    }

    @Test
    fun `rollback restores original profile after active profile changes`() = runTest {
        val preferences = ProviderFakePreferences()
        val sessionManager = CloudSessionManager(preferences)
        val configuredProvider = configuredProvider(sessionManager)
        val originalSession = session("account-a")

        configuredProvider.withProfileSession("profile-a") {
            configuredProvider.client.auth.sessionManager.saveSession(session("account-b"))
            userRegistry.switchTo("profile-b")

            configuredProvider.replaceCurrentProfileSession("profile-a", originalSession)

            assertEquals(
                "account-a",
                sessionManager.forProfile("profile-a").loadSession()?.user?.id,
            )
            assertEquals("profile-b", userRegistry.getActiveProfileId())
        }
    }

    @Test
    fun `existing observer follows replacement client for the same profile`() = runTest {
        val configuredProvider = configuredProvider(CloudSessionManager(ProviderFakePreferences()))
        configuredProvider.withProfileSession("profile-a") { }
        val statuses = mutableListOf<SessionStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            configuredProvider.observeActiveSessionState().collect { state ->
                statuses.add(state.status)
            }
        }
        runCurrent()
        assertIs<SessionStatus.NotAuthenticated>(statuses.last())
        val originalClient = configuredProvider.client

        configuredProvider.withProfileSession("profile-a") {
            configuredProvider.replaceCurrentProfileSession("profile-a", null)
        }
        runCurrent()
        assertNotSame(originalClient, configuredProvider.client)
        configuredProvider.withProfileSession("profile-a") {
            configuredProvider.client.auth.importSession(session("account-a"), autoRefresh = false)
        }
        runCurrent()

        val authenticated = assertIs<SessionStatus.Authenticated>(statuses.last())
        assertEquals("account-a", authenticated.session.user?.id)
    }

    @Test
    fun `profile switch hides previous account before the session lock is released`() = runTest {
        val configuredProvider = configuredProvider(CloudSessionManager(ProviderFakePreferences()))
        val states = mutableListOf<CloudSessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            configuredProvider.observeActiveSessionState().collect { state -> states.add(state) }
        }

        configuredProvider.withProfileSession("profile-a") {
            configuredProvider.client.auth.importSession(session("account-a"), autoRefresh = false)
            userRegistry.switchTo("profile-b")
            runCurrent()

            assertIs<SessionStatus.Initializing>(configuredProvider.currentSessionState().status)
            assertNull(configuredProvider.currentSessionState().accountId)
            assertIs<SessionStatus.Initializing>(states.last().status)
            assertFailsWith<IllegalStateException> { configuredProvider.client }
        }
        configuredProvider.withProfileSession("profile-b") {
            val state = configuredProvider.currentSessionState()
            assertIs<SessionStatus.NotAuthenticated>(state.status)
        }
    }

    @Test
    fun `restoration returns authentication for the requested profile`() = runTest {
        val configuredProvider = configuredProvider(CloudSessionManager(ProviderFakePreferences()))
        val auth = configuredProvider.withProfileSession("profile-a") {
            configuredProvider.client.auth
        }
        auth.importSession(session("account-a"), autoRefresh = false)

        val restoredState = configuredProvider.restoreSession("profile-a")

        assertIs<SessionStatus.Authenticated>(restoredState.status)
        assertEquals("account-a", restoredState.accountId)
        assertTrue(restoredState.isAuthenticatedAs("account-a"))
    }

    @Test
    fun `restoration does not wait for a pinned profile operation`() = runTest {
        val configuredProvider = configuredProvider(CloudSessionManager(ProviderFakePreferences()))
        val lockAcquired = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val pinnedOperation = async {
            configuredProvider.withProfileSession("profile-a") {
                lockAcquired.complete(Unit)
                releaseLock.await()
            }
        }
        lockAcquired.await()

        try {
            val restoration = async { configuredProvider.restoreSession("profile-a") }
            runCurrent()

            assertTrue(restoration.isCompleted)
            assertIs<SessionStatus.NotAuthenticated>(restoration.await().status)
        } finally {
            releaseLock.complete(Unit)
            pinnedOperation.await()
        }
    }

    @Test
    fun `restoration distinguishes revoked sessions from explicit sign out`() = runTest {
        val sessionManager = CloudSessionManager(ProviderFakePreferences())
        val configuredProvider = configuredProvider(sessionManager)
        configuredProvider.withProfileSession("profile-a") {
            val auth = configuredProvider.client.auth
            auth.importSession(session("account-a"), autoRefresh = false)
            auth.clearSession()
        }
        userRegistry.switchTo("profile-b")
        configuredProvider.withProfileSession("profile-b") {
            assertNull(configuredProvider.currentSessionState().accountId)
        }
        userRegistry.switchTo("profile-a")
        configuredProvider.withProfileSession("profile-a") {
            val revokedState = configuredProvider.currentSessionState()
            assertIs<SessionStatus.NotAuthenticated>(revokedState.status)
            assertEquals("account-a", revokedState.accountId)

            configuredProvider.invalidateCurrentProfileCredentials("profile-a")
            configuredProvider.replaceCurrentProfileSession("profile-a", null)

            assertNull(configuredProvider.currentSessionState().accountId)
            assertNull(sessionManager.reauthenticationAccountId("profile-a"))
        }
    }

    private fun unconfiguredProvider(): SupabaseClientProvider {
        return SupabaseClientProvider(
            configuration = CloudConfiguration(
                supabaseUrl = "",
                publishableKey = "",
            ),
            cloudSessionManager = CloudSessionManager(ProviderFakePreferences()),
            userRegistry = userRegistry,
            authCodeVerifierCache = MemoryCodeVerifierCache(),
            authLifecycleCallbacksEnabled = false,
        )
    }

    private fun configuredProvider(sessionManager: CloudSessionManager): SupabaseClientProvider {
        return SupabaseClientProvider(
            configuration = CloudConfiguration(
                supabaseUrl = "https://example.supabase.co",
                publishableKey = "test-key",
            ),
            cloudSessionManager = sessionManager,
            userRegistry = userRegistry,
            authCodeVerifierCache = MemoryCodeVerifierCache(),
            authLifecycleCallbacksEnabled = false,
        )
    }

    private fun session(accountId: String): UserSession {
        return UserSession(
            accessToken = "access-token-$accountId",
            refreshToken = "refresh-token-$accountId",
            expiresIn = 3600,
            tokenType = "bearer",
            user = UserInfo(
                aud = "authenticated",
                id = accountId,
            ),
        )
    }
}

internal class ProviderFakePreferences : Preferences {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun getStringOrNull(key: PreferencesKey): String? = values.value[key.name]

    override fun putString(key: PreferencesKey, value: String) {
        values.value = values.value + (key.name to value)
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(null)

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(defaultValue)

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue

    override fun putLong(key: PreferencesKey, value: Long) = Unit

    override fun remove(key: PreferencesKey) {
        values.value = values.value - key.name
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

    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile =
        error("Not used")

    override suspend fun updateProfile(profile: UserProfile) = Unit

    override suspend fun deleteProfile(profileId: String) = Unit

    override suspend fun getProfile(profileId: String): UserProfile? =
        profiles.firstOrNull { profile -> profile.id == profileId }

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
