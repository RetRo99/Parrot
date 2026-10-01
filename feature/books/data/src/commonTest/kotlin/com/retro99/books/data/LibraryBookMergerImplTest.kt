package com.retro99.books.data

import com.retro99.database.api.library.LibraryBookMergeDatabase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryBookMergerImplTest {

    private val fileStore = InMemoryFileStore()
    private val preferences = MapPreferences()
    private val mergeDatabase = object : LibraryBookMergeDatabase {
        val merges = mutableListOf<Pair<String, String>>()

        override suspend fun mergeLibraryBook(fromId: String, intoId: String): List<String> {
            merges += fromId to intoId
            return listOf("/library/redundant.epub")
        }
    }
    private val classUnderTest = LibraryBookMergerImpl(
        mergeDatabase = mergeDatabase,
        fileStore = fileStore,
        preferences = preferences,
        userRegistry = FixedUserRegistry,
    )

    @Test
    fun `merge deletes the redundant files and moves continue reading`() = runTest {
        // Given
        fileStore.files["/library/redundant.epub"] = byteArrayOf(1)
        preferences.putString(
            CURRENTLY_READING_KEY,
            """{"serverId":"local","bookUuid":"from","bookType":"ebook","bookTitle":"T"}""",
        )

        // When
        classUnderTest.merge(fromId = "from", intoId = "into")

        // Then
        assertEquals(listOf("from" to "into"), mergeDatabase.merges)
        assertTrue(fileStore.files.isEmpty())
        val stored = preferences.getStringOrNull(CURRENTLY_READING_KEY).orEmpty()
        assertTrue("\"bookUuid\":\"into\"" in stored, stored)
        assertTrue("\"bookTitle\":\"T\"" in stored, stored)
    }

    @Test
    fun `merge leaves continue reading for another server alone`() = runTest {
        // Given
        val storyteller = """{"serverId":"server-1","bookUuid":"from","bookType":"ebook"}"""
        preferences.putString(CURRENTLY_READING_KEY, storyteller)

        // When
        classUnderTest.merge(fromId = "from", intoId = "into")

        // Then
        assertEquals(storyteller, preferences.getStringOrNull(CURRENTLY_READING_KEY))
    }

    @Test
    fun `merge renames dismissed resume prompts that name the merged book`() = runTest {
        // Given
        preferences.putString(
            DISMISSED_KEY,
            "[\"library:from|storyteller:st|2026-10-01T10:00:00Z\"," +
                "\"storyteller:st|library:from|2026-10-01T11:00:00Z\"," +
                "\"library:other|storyteller:st|2026-10-01T12:00:00Z\"]",
        )

        // When
        classUnderTest.merge(fromId = "from", intoId = "into")

        // Then
        assertEquals(
            "[\"library:into|storyteller:st|2026-10-01T10:00:00Z\"," +
                "\"storyteller:st|library:into|2026-10-01T11:00:00Z\"," +
                "\"library:other|storyteller:st|2026-10-01T12:00:00Z\"]",
            preferences.getStringOrNull(DISMISSED_KEY),
        )
    }

    private class MapPreferences : Preferences {
        private val values = mutableMapOf<String, Any>()
        override fun getStringOrNull(key: PreferencesKey) = values[key.name] as? String
        override fun putString(key: PreferencesKey, value: String) {
            values[key.name] = value
        }
        override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = emptyFlow()
        override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit
        override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
            emptyFlow()
        override fun getLong(key: PreferencesKey, defaultValue: Long) = defaultValue
        override fun putLong(key: PreferencesKey, value: Long) = Unit
        override fun remove(key: PreferencesKey) {
            values.remove(key.name)
        }
    }

    private object FixedUserRegistry : UserRegistry {
        override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()
        override suspend fun getAllProfiles(): List<UserProfile> = emptyList()
        override suspend fun createProfile(id: String?, name: String, avatarId: Int?) =
            error("Unused")
        override suspend fun updateProfile(profile: UserProfile) = Unit
        override suspend fun deleteProfile(profileId: String) = Unit
        override suspend fun getProfile(profileId: String): UserProfile? = null
        override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
        override suspend fun getActiveProfile(): UserProfile? = null
        override fun getActiveProfileId(): String = PROFILE_ID
        override suspend fun setActiveProfile(profileId: String) = Unit
        override suspend fun clearActiveProfile() = Unit
        override suspend fun hasProfiles() = true
        override fun isProfileActive() = true
    }

    private companion object {
        const val PROFILE_ID = "profile-1"
        val CURRENTLY_READING_KEY = PreferencesKey.UserScoped(PROFILE_ID, "CurrentlyReading")
        val DISMISSED_KEY = PreferencesKey.UserScoped(PROFILE_ID, "DismissedLinkedResume")
    }
}
