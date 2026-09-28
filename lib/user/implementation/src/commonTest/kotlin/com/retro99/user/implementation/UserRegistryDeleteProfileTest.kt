package com.retro99.user.implementation

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.putObject
import com.retro99.user.api.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserRegistryDeleteProfileTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun deletingActiveProfileActivatesMostRecentlyUsedRemainingProfileFirst() = runTest {
        val toDelete = UserProfile(id = "delete-target", name = "Disposable", createdAt = 1L, lastActiveAt = 300L)
        val fallback = UserProfile(id = "fallback-target", name = "Remaining", createdAt = 2L, lastActiveAt = 200L)
        val other = UserProfile(id = "older-target", name = "Other", createdAt = 3L, lastActiveAt = 100L)
        val preferences = FakePreferences().apply {
            putObject(PreferencesKey.UserProfiles, listOf(toDelete, fallback, other))
            putString(PreferencesKey.ActiveProfileId, toDelete.id)
        }
        val registry = UserRegistryImpl(preferences)
        val observedActiveProfileIds = mutableListOf<String?>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registry.observeActiveProfile().collect { profile ->
                observedActiveProfileIds += profile?.id
            }
        }

        registry.deleteProfile(toDelete.id)

        assertEquals(fallback.id, registry.getActiveProfileId())
        assertEquals(fallback.id, preferences.getStringOrNull(PreferencesKey.ActiveProfileId))
        assertEquals(fallback.id, registry.getActiveProfile()?.id)
        assertTrue((registry.getActiveProfile()?.lastActiveAt ?: 0L) > (fallback.lastActiveAt ?: 0L))
        assertNull(registry.getProfile(toDelete.id))
        assertEquals(other, registry.getProfile(other.id))
        assertFalse(null in observedActiveProfileIds)
        assertEquals(fallback.id, observedActiveProfileIds.last())
        observer.cancel()
    }

    @Test
    fun deletingInactiveProfileKeepsCurrentActiveProfile() = runTest {
        val active = UserProfile(id = "active-target", name = "Active", createdAt = 1L, lastActiveAt = 200L)
        val toDelete = UserProfile(id = "delete-target", name = "Disposable", createdAt = 2L, lastActiveAt = 100L)
        val preferences = FakePreferences().apply {
            putObject(PreferencesKey.UserProfiles, listOf(active, toDelete))
            putString(PreferencesKey.ActiveProfileId, active.id)
        }
        val registry = UserRegistryImpl(preferences)

        registry.deleteProfile(toDelete.id)

        assertEquals(active.id, registry.getActiveProfileId())
        assertEquals(active, registry.getActiveProfile())
        assertNull(registry.getProfile(toDelete.id))
    }

    @Test
    fun deletingTheOnlyActiveProfileIsRejectedWithoutClearingItsActiveContext() = runTest {
        val active = UserProfile(id = "only-target", name = "Active", createdAt = 1L)
        val preferences = FakePreferences().apply {
            putObject(PreferencesKey.UserProfiles, listOf(active))
            putString(PreferencesKey.ActiveProfileId, active.id)
        }
        val registry = UserRegistryImpl(preferences)

        registry.deleteProfile(active.id)

        assertEquals(active.id, registry.getActiveProfileId())
        assertEquals(active.id, registry.getProfile(active.id)?.id)
    }
}

private class FakePreferences : Preferences {
    private val values = mutableMapOf<String, String>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name]

    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(values[key.name])

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean =
        values[key.name]?.toBooleanStrictOrNull() ?: defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) {
        values[key.name] = value.toString()
    }

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(getBoolean(key, defaultValue))

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long =
        values[key.name]?.toLongOrNull() ?: defaultValue

    override fun putLong(key: PreferencesKey, value: Long) {
        values[key.name] = value.toString()
    }

    override fun remove(key: PreferencesKey) {
        values.remove(key.name)
    }
}
