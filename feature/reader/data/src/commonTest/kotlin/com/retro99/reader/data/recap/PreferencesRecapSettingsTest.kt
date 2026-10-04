package com.retro99.reader.data.recap

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesRecapSettingsTest {
    private val preferences = RecapTestPreferences()
    private val registry = RecapTestUserRegistry()
    private val get = GetUserPreferenceUseCase(preferences, registry)
    private val save = SaveUserPreferenceUseCase(preferences, registry)
    private val database = FakeSessionRecapDatabase()
    private val clock = TestClock()
    private val account = MutableStateFlow<String?>("account")
    private val auth = object : RecapAuthTokens {
        override fun accountId() = account.value
        override fun observeAccountId() = account
        override fun isSignedIn() = account.value != null
        override fun observeSignedIn() = account.map { it != null }
        override suspend fun accessToken() = "test-token"
        override suspend fun refreshedAccessToken() = "test-token"
    }

    private fun settings(client: HttpClient) = PreferencesRecapSettings(
        get, save, ObserveUserPreferenceUseCase(preferences, registry), database, auth,
        CloudRecapEngine(RecapEndpoint("https://fixture.invalid", "fixture-key"), auth, client, clock),
    )

    private fun consent() {
        save(PreferencesKey.CloudRecapConsentAccount, "account")
        save(PreferencesKey.CloudStoredRecapsEnabled, true)
    }

    @Test
    fun restoringOrOfflineAuthenticationDoesNotDestroyPendingOrAbandonedText() = runTest {
        consent()
        account.value = null
        val client = HttpClient(MockEngine { error("Recovery must not call the cloud") })
        try {
            val settings = settings(client)
            assertFalse(settings.isCloudRecapsEnabled())
            assertTrue(settings.isConsentGiven("account"))
            database.put(pendingRow("pending").copy(cloudAccountId = "account"))
            database.put(pendingRow("abandoned", status = "CAPTURING", bookUuid = "other-book").copy(
                cloudAccountId = "account", pageAdvances = 4,
                startTotalProgression = 0.1, endTotalProgression = 0.2,
            ))
            val recorder = RecapSessionRecorderImpl(database, settings,
                RecapDiagnostics(RecordingAnalytics()), {}, clock, StandardTestDispatcher(testScheduler))

            recorder.recoverAbandoned()

            for (id in listOf("pending", "abandoned")) {
                assertEquals("PENDING", database[id]!!.status)
                assertTrue(database[id]!!.excerpt != null)
                assertNull(database[id]!!.lastError)
            }
        } finally { client.close() }
    }

    @Test
    fun consentCannotBeUsedByAnotherAccountOrAfterWithdrawal() = runTest {
        consent()
        val client = HttpClient(MockEngine { error("No cloud request expected") })
        try {
            val settings = settings(client)
            assertFalse(settings.isConsentGiven("different-account"))
            account.value = "different-account"
            assertFalse(settings.isConsentGiven("account"))
            account.value = null
            database.queueCloudWithdrawal("account", clock.nowMs)
            assertFalse(settings.isConsentGiven("account"))
        } finally { client.close() }
    }

    @Test
    fun withdrawalAfterASlowEnableIsTheFinalLocalAndServerState() = runTest {
        val releaseEnable = CompletableDeferred<Unit>()
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            requests += body
            if (requests.size == 1) releaseEnable.await()
            respond("{\"ok\":true}")
        })
        try {
            val settings = settings(client)
            val enable = async { settings.setCloudRecapsEnabled(true) }
            runCurrent()
            val disable = async { settings.setCloudRecapsEnabled(false) }
            runCurrent()
            assertFalse(disable.isCompleted)
            releaseEnable.complete(Unit)
            enable.await()
            disable.await()

            assertEquals(2, requests.size)
            assertTrue(requests.first().contains("\"enabled\":true"))
            assertTrue(requests.last().contains("\"enabled\":false"))
            assertEquals(false, get<Boolean>(PreferencesKey.CloudStoredRecapsEnabled))
            assertFalse(settings.isConsentGiven("account"))
            assertFalse(settings.isCloudRecapsEnabled())
        } finally { client.close() }
    }
}

private class RecapTestPreferences : Preferences {
    private val values = MutableStateFlow<Map<String, Any>>(emptyMap())
    override fun getStringOrNull(key: PreferencesKey) = values.value[key.name] as? String
    override fun putString(key: PreferencesKey, value: String) { values.value += key.name to value }
    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = values.map { it[key.name] as? String }
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = values.value[key.name] as? Boolean ?: defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) { values.value += key.name to value }
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean) = values.map { it[key.name] as? Boolean ?: defaultValue }
    override fun getLong(key: PreferencesKey, defaultValue: Long) = values.value[key.name] as? Long ?: defaultValue
    override fun putLong(key: PreferencesKey, value: Long) { values.value += key.name to value }
    override fun remove(key: PreferencesKey) { values.value -= key.name }
}

private class RecapTestUserRegistry : UserRegistry {
    override fun getActiveProfileId() = "profile"
    override fun isProfileActive() = true
    override fun observeActiveProfile(): Flow<UserProfile?> = flowOf(null)
    override fun observeAllProfiles(): Flow<List<UserProfile>> = error("Not used")
    override suspend fun getAllProfiles(): List<UserProfile> = error("Not used")
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile = error("Not used")
    override suspend fun updateProfile(profile: UserProfile) = error("Not used")
    override suspend fun deleteProfile(profileId: String) = error("Not used")
    override suspend fun getProfile(profileId: String): UserProfile? = error("Not used")
    override suspend fun getActiveProfile(): UserProfile? = error("Not used")
    override suspend fun setActiveProfile(profileId: String) = error("Not used")
    override suspend fun clearActiveProfile() = error("Not used")
    override suspend fun hasProfiles(): Boolean = error("Not used")
}
