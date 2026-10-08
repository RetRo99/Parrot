package com.retro99.server.implementation

import com.retro99.preferences.api.*
import com.retro99.server.api.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** Preferences is SecureSettings: Android encrypted prefs / iOS Keychain. Not a sync DTO. */
@Single(binds = [OpdsCredentialStore::class])
class OpdsCredentialStoreImpl(private val preferences: Preferences) : OpdsCredentialStore {
    private val mutex = Mutex()
    private fun key(profileId: String) = PreferencesKey.UserScoped(profileId, PreferencesKey.OpdsCredentials.name)
    private fun read(profileId: String) = preferences.getObject<Map<String, OpdsAccountDetails>>(key(profileId)).orEmpty()
    override fun get(profileId: String, sourceId: String) = read(profileId)[sourceId]
    override suspend fun save(profileId: String, sourceId: String, details: OpdsAccountDetails) = mutex.withLock {
        preferences.putObject(key(profileId), read(profileId) + (sourceId to details))
    }
    override suspend fun remove(profileId: String, sourceId: String) = mutex.withLock {
        preferences.putObject(key(profileId), read(profileId) - sourceId)
    }
}
