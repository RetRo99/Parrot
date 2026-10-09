package com.retro99.settings.data

import com.retro99.server.api.*
import com.retro99.server.implementation.*
import com.retro99.preferences.api.*
import com.retro99.user.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueLogoutTest {
    @Test fun settings_logout_all_removes_accounts_cancels_work_and_keeps_sources() = runTest {
        val preferences = MemoryPreferences()
        val accountStore = OpdsCredentialStoreImpl(preferences)
        val workCancelled = mutableListOf<String>()
        val work = object : CatalogueWorkController { override suspend fun cancel(profileId: String, sourceId: String) { workCancelled += sourceId } }
        val registry = ServerRegistryImpl(preferences, TestUser(), emptyList(), accountStore, CatalogueAccessStoreImpl(preferences), listOf(work))
        val source = registry.addServerWithId("source", "Books", ServerType.Opds, "https://books.example/opds/")
        accountStore.save("a", source.id, OpdsAccountDetails("patron", ""))
        SettingsDataRepository(registry).logout(null)
        assertEquals(source, registry.getServer(source.id))
        assertNull(accountStore.get("a", source.id))
        assertEquals(listOf(source.id), workCancelled)
    }
}

private class MemoryPreferences : Preferences {
    private val values = mutableMapOf<String, String>()
    override fun getStringOrNull(key: PreferencesKey) = values[key.name]
    override fun putString(key: PreferencesKey, value: String) { values[key.name] = value }
    override fun observeStringOrNull(key: PreferencesKey) = flowOf(getStringOrNull(key))
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = error("unused")
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean) = flowOf(defaultValue)
    override fun getLong(key: PreferencesKey, defaultValue: Long) = defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = error("unused")
    override fun remove(key: PreferencesKey) { values.remove(key.name) }
}
private class TestUser : UserRegistry {
    private val user = UserProfile("a", "A", createdAt = 0)
    override fun observeAllProfiles() = flowOf(listOf(user))
    override suspend fun getAllProfiles() = listOf(user)
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile = error("unused")
    override suspend fun updateProfile(profile: UserProfile) = error("unused")
    override suspend fun deleteProfile(profileId: String) = error("unused")
    override suspend fun getProfile(profileId: String) = user.takeIf { it.id == profileId }
    override fun observeActiveProfile(): Flow<UserProfile?> = flowOf(user)
    override suspend fun getActiveProfile() = user
    override fun getActiveProfileId() = user.id
    override suspend fun setActiveProfile(profileId: String) = error("unused")
    override suspend fun clearActiveProfile() = error("unused")
    override suspend fun hasProfiles() = true
    override fun isProfileActive() = true
}
