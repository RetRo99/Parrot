package com.retro99.cloud.implementation

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class CloudSessionManager(
    @Provided private val preferences: Preferences,
) {
    internal fun forProfile(localProfileId: String): ProfileSessionManager {
        return ProfileSessionManager(preferences, localProfileId)
    }

    fun storedCloudAccountId(localProfileId: String): String? {
        return preferences.getStringOrNull(
            PreferencesKey.CloudSessionAccount(localProfileId),
        )
    }

    fun reauthenticationAccountId(localProfileId: String): String? {
        return preferences.getStringOrNull(
            PreferencesKey.CloudReauthenticationAccount(localProfileId),
        )
    }
}

internal class ProfileSessionManager(
    private val preferences: Preferences,
    private val localProfileId: String,
) : SessionManager {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private var invalidated = false

    override suspend fun saveSession(session: UserSession) {
        mutex.withLock {
            if (invalidated) return@withLock
            val indexedCloudUserId = preferences.getStringOrNull(
                PreferencesKey.CloudSessionAccount(localProfileId),
            )
            val cloudUserId = session.user?.id ?: indexedCloudUserId
                ?: error("Cannot persist a cloud session without a cloud user ID")
            val sessionKey = PreferencesKey.CloudSession(localProfileId, cloudUserId)
            preferences.putString(
                sessionKey,
                json.encodeToString(UserSession.serializer(), session),
            )
            preferences.putString(
                PreferencesKey.CloudSessionAccount(localProfileId),
                cloudUserId,
            )
            preferences.remove(PreferencesKey.CloudReauthenticationAccount(localProfileId))
            if (indexedCloudUserId != null && indexedCloudUserId != cloudUserId) {
                preferences.remove(PreferencesKey.CloudSession(localProfileId, indexedCloudUserId))
            }
        }
    }

    override suspend fun loadSession(): UserSession? = mutex.withLock {
        if (invalidated) return@withLock null
        val cloudUserId = preferences.getStringOrNull(
            PreferencesKey.CloudSessionAccount(localProfileId),
        ) ?: return@withLock null
        val sessionKey = PreferencesKey.CloudSession(localProfileId, cloudUserId)
        val sessionJson = preferences.getStringOrNull(sessionKey)
        if (sessionJson == null) {
            preferences.remove(PreferencesKey.CloudSessionAccount(localProfileId))
            return@withLock null
        }

        try {
            json.decodeFromString(UserSession.serializer(), sessionJson)
        } catch (exception: Exception) {
            preferences.remove(sessionKey)
            preferences.remove(PreferencesKey.CloudSessionAccount(localProfileId))
            null
        }
    }

    override suspend fun deleteSession() {
        mutex.withLock {
            if (invalidated) return@withLock
            preferences.getStringOrNull(PreferencesKey.CloudSessionAccount(localProfileId))
                ?.let { cloudUserId ->
                    preferences.putString(
                        PreferencesKey.CloudReauthenticationAccount(localProfileId),
                        cloudUserId,
                    )
                }
            deleteStoredSession()
        }
    }

    suspend fun invalidate(clearStoredSession: Boolean = false) {
        mutex.withLock {
            if (invalidated) return@withLock
            invalidated = true
            if (clearStoredSession) {
                deleteStoredSession()
                preferences.remove(PreferencesKey.CloudReauthenticationAccount(localProfileId))
            }
        }
    }

    private fun deleteStoredSession() {
        val cloudUserId = preferences.getStringOrNull(
            PreferencesKey.CloudSessionAccount(localProfileId),
        )
        if (cloudUserId != null) {
            preferences.remove(PreferencesKey.CloudSession(localProfileId, cloudUserId))
        }
        preferences.remove(PreferencesKey.CloudSessionAccount(localProfileId))
    }
}
