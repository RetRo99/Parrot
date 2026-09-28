package com.retro99.user.implementation

import co.touchlab.kermit.Logger
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.putObject
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Single(binds = [UserRegistry::class])
class UserRegistryImpl(
    private val preferences: Preferences,
) : UserRegistry {

    private val logger = Logger.withTag("UserRegistry")

    private val mutex = Mutex()

    // In-memory cache backed by preferences
    private val _profiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    private val _activeProfileId = MutableStateFlow<String?>(null)

    init {
        loadFromPreferences()
    }

    private fun loadFromPreferences() {
        // Load profiles
        val profiles = preferences.getObject<List<UserProfile>>(PreferencesKey.UserProfiles)
        if (profiles != null) {
            _profiles.value = profiles.associateBy { it.id }
            logger.d { "Loaded ${profiles.size} profiles from preferences" }
        }

        // Load active profile
        val activeId = preferences.getStringOrNull(PreferencesKey.ActiveProfileId)
        _activeProfileId.value = activeId
        logger.d { "Loaded active profile state" }
    }

    // ==================== Profile Management ====================

    override fun observeAllProfiles(): Flow<List<UserProfile>> {
        return _profiles.map { it.values.toList().sortedBy { profile -> profile.name } }
    }

    override suspend fun getAllProfiles(): List<UserProfile> {
        return _profiles.value.values.toList()
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile = mutex.withLock {
        val profileId = id ?: Uuid.random().toString()
        val profile = UserProfile(
            id = profileId,
            name = name,
            avatarId = avatarId,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            lastActiveAt = null,
        )

        _profiles.update { it + (profile.id to profile) }
        persistProfiles()

        logger.d { "Created profile" }
        profile
    }

    override suspend fun updateProfile(profile: UserProfile) = mutex.withLock {
        _profiles.update { it + (profile.id to profile) }
        persistProfiles()
        logger.d { "Updated profile" }
    }

    override suspend fun deleteProfile(profileId: String) = mutex.withLock {
        val profile = _profiles.value[profileId]
        if (profile == null) {
            logger.w { "Attempted to delete a profile that is not registered" }
            return@withLock
        }

        if (_activeProfileId.value == profileId) {
            val fallbackProfile = _profiles.value.values
                .asSequence()
                .filterNot { it.id == profileId }
                .maxWithOrNull(
                    compareBy<UserProfile> { it.lastActiveAt ?: Long.MIN_VALUE }
                        .thenBy { it.createdAt },
                )
            if (fallbackProfile == null) {
                logger.w { "Attempted to delete the only active profile" }
                return@withLock
            }

            val activatedFallback = fallbackProfile.copy(
                lastActiveAt = Clock.System.now().toEpochMilliseconds(),
            )
            _profiles.update { it + (activatedFallback.id to activatedFallback) }
            persistProfiles()
            _activeProfileId.value = activatedFallback.id
            persistActiveProfile()
            logger.d { "Activated a remaining profile before active-profile deletion" }
        }

        // Clear user-scoped preferences
        clearUserPreferences(profileId)

        // Remove profile from registry
        _profiles.update { it - profileId }
        persistProfiles()

        logger.d { "Deleted profile" }
        // Note: Database file deletion should be handled by DatabaseProvider
    }

    override suspend fun getProfile(profileId: String): UserProfile? {
        return _profiles.value[profileId]
    }

    // ==================== Active Profile ====================

    override fun observeActiveProfile(): Flow<UserProfile?> {
        return combine(_activeProfileId, _profiles) { activeId, profiles ->
            activeId?.let { profiles[it] }
        }
    }

    override suspend fun getActiveProfile(): UserProfile? {
        val activeId = _activeProfileId.value ?: return null
        return _profiles.value[activeId]
    }

    override fun getActiveProfileId(): String? {
        return _activeProfileId.value
    }

    override suspend fun setActiveProfile(profileId: String) = mutex.withLock {
        val profile = _profiles.value[profileId]
        if (profile == null) {
            logger.w { "Attempted to activate a profile that is not registered" }
            return@withLock
        }

        _activeProfileId.value = profileId
        persistActiveProfile()

        // Update lastActiveAt
        val updatedProfile = profile.copy(
            lastActiveAt = Clock.System.now().toEpochMilliseconds()
        )
        _profiles.update { it + (profileId to updatedProfile) }
        persistProfiles()

        logger.d { "Activated profile" }
    }

    override suspend fun clearActiveProfile() = mutex.withLock {
        _activeProfileId.value = null
        persistActiveProfile()
        logger.d { "Cleared active profile" }
    }

    // ==================== Convenience ====================

    override suspend fun hasProfiles(): Boolean {
        return _profiles.value.isNotEmpty()
    }

    override fun isProfileActive(): Boolean {
        return _activeProfileId.value != null
    }

    // ==================== Persistence ====================

    private fun persistProfiles() {
        val profilesList = _profiles.value.values.toList()
        preferences.putObject(PreferencesKey.UserProfiles, profilesList)
        logger.d { "Persisted ${profilesList.size} profiles" }
    }

    private fun persistActiveProfile() {
        val activeId = _activeProfileId.value
        if (activeId != null) {
            preferences.putString(PreferencesKey.ActiveProfileId, activeId)
        } else {
            preferences.remove(PreferencesKey.ActiveProfileId)
        }
    }

    /**
     * Clear all user-scoped preferences for a profile.
     * This removes servers, credentials, and other user-specific settings.
     */
    private fun clearUserPreferences(userId: String) {
        // List of keys that are stored per-user
        val userScopedKeys = listOf(
            PreferencesKey.RegisteredServers,
            PreferencesKey.ServerCredentials,
            PreferencesKey.ReaderSettings,
            PreferencesKey.ReaderCustomFonts,
            PreferencesKey.CurrentlyReading,
            PreferencesKey.SkippedLogin,
            PreferencesKey.BubblePosition,
        )

        userScopedKeys.forEach { key ->
            preferences.remove(PreferencesKey.UserScoped(userId, key.name))
        }
        logger.d { "Cleared profile-scoped preferences" }
    }
}
