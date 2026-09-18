package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CloudProfileLinkDataRepositoryTest {
    private val preferences = FakePreferences()
    private val repository = CloudProfileLinkDataRepository(preferences)

    @Test
    fun `linking a local profile twice keeps the original account`() = runTest {
        val firstResult = repository.link("profile-a", "account-a")
        val secondResult = repository.link("profile-a", "account-b")

        assertIs<CloudProfileLinkResult.Linked>(firstResult)
        val conflict = assertIs<CloudProfileLinkResult.LocalProfileAlreadyLinked>(secondResult)
        assertEquals("account-a", conflict.link.cloudUserId)
    }

    @Test
    fun `linking an account to a second local profile is rejected`() = runTest {
        repository.link("profile-a", "account-a")

        val result = repository.link("profile-b", "account-a")

        val conflict = assertIs<CloudProfileLinkResult.CloudAccountAlreadyLinked>(result)
        assertEquals("profile-a", conflict.link.localProfileId)
    }

    @Test
    fun `sync state changes are persisted`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setSyncEnabled("profile-a", enabled = true)
        repository.markInitialMergeCompleted("profile-a")

        val link = repository.getForLocalProfile("profile-a")

        assertEquals(true, link?.syncEnabled)
        assertEquals(true, link?.initialMergeCompleted)
    }

    @Test
    fun `conflicting account does not change the existing link state`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setSyncEnabled("profile-a", enabled = true)
        repository.markInitialMergeCompleted("profile-a")

        val result = repository.link("profile-a", "account-b")

        assertIs<CloudProfileLinkResult.LocalProfileAlreadyLinked>(result)
        val link = repository.getForLocalProfile("profile-a")
        assertEquals("account-a", link?.cloudUserId)
        assertEquals(true, link?.syncEnabled)
        assertEquals(true, link?.initialMergeCompleted)
    }
}

private class FakePreferences : Preferences {
    private val values = mutableMapOf<String, Any>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name] as? String

    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> =
        flowOf(getStringOrNull(key))

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean =
        values[key.name] as? Boolean ?: defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) {
        values[key.name] = value
    }

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(getBoolean(key, defaultValue))

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long =
        values[key.name] as? Long ?: defaultValue

    override fun putLong(key: PreferencesKey, value: Long) {
        values[key.name] = value
    }

    override fun remove(key: PreferencesKey) {
        values.remove(key.name)
    }
}
