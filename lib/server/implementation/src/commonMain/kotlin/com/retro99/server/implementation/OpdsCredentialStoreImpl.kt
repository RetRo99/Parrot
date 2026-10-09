package com.retro99.server.implementation

import com.retro99.preferences.api.*
import com.retro99.server.api.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

/** Preferences is SecureSettings: Android encrypted prefs / iOS Keychain. Not a sync DTO. */
@Single(binds = [OpdsCredentialStore::class])
class OpdsCredentialStoreImpl(private val preferences: Preferences) : OpdsCredentialStore {
    /** [next] only grows, so a removed and re-added catalogue never gets an old number back. */
    @Serializable
    private data class Generations(val next: Long = 1, val bySource: Map<String, Long> = emptyMap())

    private val mutex = Mutex()
    private fun key(profileId: String) = PreferencesKey.UserScoped(profileId, PreferencesKey.OpdsCredentials.name)
    private fun generationsKey(profileId: String) = PreferencesKey.UserScoped(profileId, PreferencesKey.CatalogueAccessGenerations.name)
    private fun read(profileId: String) = preferences.getObject<Map<String, OpdsAccountDetails>>(key(profileId)).orEmpty()
    private fun generations(profileId: String) = preferences.getObject<Generations>(generationsKey(profileId)) ?: Generations()
    override fun get(profileId: String, sourceId: String) = read(profileId)[sourceId]
    override fun accessGeneration(profileId: String, sourceId: String) = generations(profileId).bySource[sourceId] ?: 0L
    override suspend fun save(profileId: String, sourceId: String, details: OpdsAccountDetails) = mutex.withLock {
        val stored = read(profileId)
        if (stored[sourceId] == details) return@withLock
        // The number first: a crash in between leaves old details under a new number, which
        // only costs saved pages. The other order would leave new details under the old one.
        newGeneration(profileId, sourceId)
        preferences.putObject(key(profileId), stored + (sourceId to details))
    }
    override suspend fun remove(profileId: String, sourceId: String) = mutex.withLock {
        val stored = read(profileId)
        if (sourceId !in stored) return@withLock
        newGeneration(profileId, sourceId)
        preferences.putObject(key(profileId), stored - sourceId)
    }

    private fun newGeneration(profileId: String, sourceId: String) {
        val current = generations(profileId)
        preferences.putObject(generationsKey(profileId), Generations(current.next + 1, current.bySource + (sourceId to current.next)))
    }
}
