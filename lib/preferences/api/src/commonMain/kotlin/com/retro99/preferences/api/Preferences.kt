package com.retro99.preferences.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

interface Preferences {
    fun getStringOrNull(key: PreferencesKey): String?
    fun putString(key: PreferencesKey, value: String)
    fun observeStringOrNull(key: PreferencesKey): Flow<String?>
    fun getBoolean(key: PreferencesKey, defaultValue: Boolean = false): Boolean
    fun putBoolean(key: PreferencesKey, value: Boolean)
    fun observeBoolean(key: PreferencesKey, defaultValue: Boolean = false): Flow<Boolean>
    fun getLong(key: PreferencesKey, defaultValue: Long = 0L): Long
    fun putLong(key: PreferencesKey, value: Long)
    fun remove(key: PreferencesKey)
}

/**
 * Json instance configured for preferences serialization.
 * - ignoreUnknownKeys: Allows adding new fields without breaking existing stored data
 * - coerceInputValues: Uses data class default values when JSON field is missing or null
 */
@PublishedApi
internal val preferencesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

inline fun <reified T> Preferences.putObject(key: PreferencesKey, value: T) {
    val jsonString = preferencesJson.encodeToString(value)
    putString(key, jsonString)
}

inline fun <reified T> Preferences.getObject(key: PreferencesKey): T? {
    val jsonString = getStringOrNull(key) ?: return null
    return try {
        preferencesJson.decodeFromString<T>(jsonString)
    } catch (e: Exception) {
        // Note: This is in the API module without Analytics dependency.
        // Callers should handle null return appropriately.
        // Consider using a logged version in implementation if needed.
        null
    }
}

inline fun <reified T> Preferences.observeObject(key: PreferencesKey): Flow<T?> =
    observeStringOrNull(key).map { jsonString ->
        jsonString?.let {
            try {
                preferencesJson.decodeFromString<T>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

sealed class PreferencesKey(val name: String) {
    data object ReaderSettings : PreferencesKey("ReaderSettings")
    data object ReaderCustomFonts : PreferencesKey("ReaderCustomFonts")
    data object DatabaseSchemaVersion : PreferencesKey("DatabaseSchemaVersion")
    data object FileLoggingEnabled : PreferencesKey("FileLoggingEnabled")
    data object FileLoggingCrashesOnly : PreferencesKey("FileLoggingCrashesOnly")
    data object CurrentlyReading : PreferencesKey("CurrentlyReading")
    data object BubblePosition : PreferencesKey("BubblePosition")
    data object OpenLastBookOnLaunch : PreferencesKey("OpenLastBookOnLaunch")
    data object ShowContinueReading : PreferencesKey("ShowContinueReading")
    data object RegisteredServers : PreferencesKey("RegisteredServers")
    data object ServerCredentials : PreferencesKey("ServerCredentials")
    data object SkippedLogin : PreferencesKey("SkippedLogin")
    data object BookListFilterSort : PreferencesKey("BookListFilterSort")

    // User profile keys (device-level, not user-scoped)
    data object UserProfiles : PreferencesKey("UserProfiles")
    data object ActiveProfileId : PreferencesKey("ActiveProfileId")
    data object ProfileMigrationVersion : PreferencesKey("ProfileMigrationVersion")
    data object CloudProfileLinks : PreferencesKey("CloudProfileLinks")

    data class CloudSessionAccount(val localProfileId: String) :
        PreferencesKey("cloud_session_account_$localProfileId")

    data class CloudReauthenticationAccount(val localProfileId: String) :
        PreferencesKey("cloud_reauthentication_account_$localProfileId")

    data class CloudSession(val localProfileId: String, val cloudUserId: String) :
        PreferencesKey("cloud_session_${localProfileId}_$cloudUserId")

    data class SyncCursor(val localProfileId: String, val cloudUserId: String) :
        PreferencesKey("sync_cursor_${localProfileId}_$cloudUserId")

    data class PendingCloudAuthentication(val localProfileId: String) :
        PreferencesKey("pending_cloud_authentication_$localProfileId")

    /**
     * Dynamic key for user-scoped preferences.
     * Used to store per-user data like servers, credentials, settings.
     * Example: UserScoped("abc123", "RegisteredServers") -> "user_abc123_RegisteredServers"
     */
    data class UserScoped(val userId: String, val key: String) : PreferencesKey("user_${userId}_$key")
}
