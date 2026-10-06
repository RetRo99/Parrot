package com.retro99.cloud.implementation

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudSessionManagerTest {
    private val preferences = FakePreferences()
    private val manager = CloudSessionManager(preferences)

    @Test
    fun `missing session returns null`() = runTest {
        assertNull(manager.forProfile("profile-a").loadSessionOrNull())
    }

    @Test
    fun `invalid session clears session and account index`() = runTest {
        val accountKey = PreferencesKey.CloudSessionAccount("profile-a")
        val sessionKey = PreferencesKey.CloudSession("profile-a", "account-a")
        preferences.putString(accountKey, "account-a")
        preferences.putString(sessionKey, "invalid")

        assertNull(manager.forProfile("profile-a").loadSessionOrNull())
        assertNull(preferences.getStringOrNull(accountKey))
        assertNull(preferences.getStringOrNull(sessionKey))
    }

    @Test
    fun `loading one profile does not touch another profile`() = runTest {
        val profileBAccountKey = PreferencesKey.CloudSessionAccount("profile-b")
        val profileBSessionKey = PreferencesKey.CloudSession("profile-b", "account-b")
        preferences.putString(profileBAccountKey, "account-b")
        preferences.putString(profileBSessionKey, "invalid")

        assertNull(manager.forProfile("profile-a").loadSessionOrNull())

        assertEquals("account-b", preferences.getStringOrNull(profileBAccountKey))
        assertEquals("invalid", preferences.getStringOrNull(profileBSessionKey))
    }

    @Test
    fun `changing accounts removes the previous account tokens`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.saveSession(session("account-a"))

        profileManager.saveSession(session("account-b"))

        assertNull(
            preferences.getStringOrNull(PreferencesKey.CloudSession("profile-a", "account-a")),
        )
        assertEquals("account-b", profileManager.loadSessionOrNull()?.user?.id)
        profileManager.deleteSession()
        assertNull(manager.storedCloudAccountId("profile-a"))
        assertNull(
            preferences.getStringOrNull(PreferencesKey.CloudSession("profile-a", "account-b")),
        )
    }

    @Test
    fun `invalidated profile manager ignores later session saves`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.invalidate()

        profileManager.saveSession(
            UserSession(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
                user = UserInfo(
                    aud = "authenticated",
                    id = "account-a",
                ),
            ),
        )

        assertNull(
            preferences.getStringOrNull(
                PreferencesKey.CloudSessionAccount("profile-a"),
            ),
        )
    }

    @Test
    fun `invalidated manager cannot delete a replacement session`() = runTest {
        val previousManager = manager.forProfile("profile-a")
        previousManager.saveSession(session("account-a"))
        previousManager.invalidate()
        val replacementManager = manager.forProfile("profile-a")
        replacementManager.saveSession(session("account-b"))

        previousManager.deleteSession()
        previousManager.invalidate(clearStoredSession = true)

        assertEquals("account-b", replacementManager.loadSessionOrNull()?.user?.id)
        assertEquals("account-b", manager.storedCloudAccountId("profile-a"))
    }

    @Test
    fun `invalidation can clear stored session and prevent subsequent saves`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.saveSession(session("account-a"))

        profileManager.invalidate(clearStoredSession = true)
        profileManager.saveSession(session("account-b"))

        assertNull(manager.storedCloudAccountId("profile-a"))
        assertNull(manager.forProfile("profile-a").loadSessionOrNull())
        assertNull(
            preferences.getStringOrNull(PreferencesKey.CloudSession("profile-a", "account-a")),
        )
        assertNull(
            preferences.getStringOrNull(PreferencesKey.CloudSession("profile-a", "account-b")),
        )
    }

    @Test
    fun `sdk deletion retains account identity without retaining credentials`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.saveSession(session("account-a"))

        profileManager.deleteSession()

        assertNull(profileManager.loadSessionOrNull())
        assertNull(manager.storedCloudAccountId("profile-a"))
        assertEquals("account-a", manager.reauthenticationAccountId("profile-a"))
        assertNull(manager.reauthenticationAccountId("profile-b"))
        profileManager.deleteSession()
        assertEquals("account-a", manager.reauthenticationAccountId("profile-a"))
    }

    @Test
    fun `signing in clears the previous reauthentication requirement`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.saveSession(session("account-a"))
        profileManager.deleteSession()

        profileManager.saveSession(session("account-b"))

        assertNull(manager.reauthenticationAccountId("profile-a"))
        assertEquals("account-b", profileManager.loadSessionOrNull()?.user?.id)
    }

    @Test
    fun `explicit sign out clears reauthentication and blocks late sdk writes`() = runTest {
        val profileManager = manager.forProfile("profile-a")
        profileManager.saveSession(session("account-a"))
        profileManager.deleteSession()

        profileManager.invalidate(clearStoredSession = true)
        profileManager.saveSession(session("account-a"))
        profileManager.deleteSession()

        assertNull(manager.reauthenticationAccountId("profile-a"))
        assertNull(manager.storedCloudAccountId("profile-a"))
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

private class FakePreferences : Preferences {
    private val values = mutableMapOf<String, String>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name]

    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> =
        flowOf(getStringOrNull(key))

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(defaultValue)

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue

    override fun putLong(key: PreferencesKey, value: Long) = Unit

    override fun remove(key: PreferencesKey) {
        values.remove(key.name)
    }
}
