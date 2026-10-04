package com.retro99.server.implementation.source

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallationDeviceIdentityTest {
    @Test
    fun `installation UUID is generated once and persisted in preferences`() {
        val preferences = MemoryPreferences()
        val provider = InstallationDeviceIdentityImpl(preferences)

        val first = provider.getOrCreate()
        val second = provider.getOrCreate()

        assertTrue(first.id.matches(UUID_PATTERN.toRegex()))
        assertEquals(first.id, second.id)
        assertEquals(first.id, preferences.getStringOrNull(PreferencesKey.InstallationDeviceId))
        assertEquals(first.name, second.name)
    }

    private class MemoryPreferences : Preferences {
        private val strings = mutableMapOf<String, String>()

        override fun getStringOrNull(key: PreferencesKey): String? = strings[key.name]
        override fun putString(key: PreferencesKey, value: String) {
            strings[key.name] = value
        }
        override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = emptyFlow()
        override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue
        override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit
        override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> = emptyFlow()
        override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue
        override fun putLong(key: PreferencesKey, value: Long) = Unit
        override fun remove(key: PreferencesKey) {
            strings.remove(key.name)
        }
    }

    private companion object {
        const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    }
}
