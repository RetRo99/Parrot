package com.retro99.server.implementation

import com.retro99.preferences.api.*
import com.retro99.server.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

@Single(binds = [CatalogueAccessStore::class])
class CatalogueAccessStoreImpl(private val preferences: Preferences) : CatalogueAccessStore {
    private val mutex = Mutex()
    private fun key(profileId: String) = PreferencesKey.UserScoped(profileId, PreferencesKey.CatalogueAccessStatus.name)
    private fun read(profileId: String) = preferences.getObject<Map<String, CatalogueAccessStatus>>(key(profileId)).orEmpty()
    override fun get(profileId: String, sourceId: String) = read(profileId)[sourceId] ?: CatalogueAccessStatus()
    override fun observe(profileId: String): Flow<Map<String, CatalogueAccessStatus>> =
        preferences.observeObject<Map<String, CatalogueAccessStatus>>(key(profileId)).map { it.orEmpty() }

    override suspend fun recordSuccess(profileId: String, sourceId: String, username: String?, at: Long, isRoot: Boolean) = mutex.withLock {
        val previous = get(profileId, sourceId)
        write(profileId, sourceId, previous.copy(
            access = username?.let { ServerAccessState.SignedIn(it) } ?: ServerAccessState.Public,
            lastCheck = previous.lastCheck.copy(lastSuccessAt = at),
            rootAnswered401 = if (isRoot) false else previous.rootAnswered401,
        ))
    }

    override suspend fun recordFailure(profileId: String, sourceId: String, kind: CatalogueErrorKind, at: Long, rootAnswered401: Boolean) = mutex.withLock {
        val previous = get(profileId, sourceId)
        write(profileId, sourceId, previous.copy(
            access = when (kind) {
                CatalogueErrorKind.SignInNeeded -> ServerAccessState.SignInNeeded
                CatalogueErrorKind.SignInUnsupported -> ServerAccessState.SignInUnsupported
                else -> previous.access
            },
            lastCheck = previous.lastCheck.copy(lastError = kind, lastErrorAt = at),
            rootAnswered401 = previous.rootAnswered401 || rootAnswered401,
        ))
    }

    private fun write(profileId: String, sourceId: String, status: CatalogueAccessStatus) {
        preferences.putObject(key(profileId), read(profileId) + (sourceId to status))
    }
    override suspend fun remove(profileId: String, sourceId: String) = mutex.withLock {
        preferences.putObject(key(profileId), read(profileId) - sourceId)
    }
}
