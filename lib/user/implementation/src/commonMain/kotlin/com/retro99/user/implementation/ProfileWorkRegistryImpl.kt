package com.retro99.user.implementation

import com.retro99.user.api.ProfileWorkRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

@Single(binds = [ProfileWorkRegistry::class])
class ProfileWorkRegistryImpl : ProfileWorkRegistry {
    private data class Key(val profileId: String, val key: Any)
    private data class State(val closed: Set<String> = emptySet(), val work: Map<Key, suspend () -> Unit> = emptyMap())
    private val state = MutableStateFlow(State())
    override fun activate(profileId: String) { state.update { it.copy(closed = it.closed - profileId) } }
    override fun register(profileId: String, key: Any, cancel: suspend () -> Unit): Boolean {
        while (true) {
            val previous = state.value
            if (profileId in previous.closed) return false
            if (state.compareAndSet(previous, previous.copy(work = previous.work + (Key(profileId, key) to cancel)))) return true
        }
    }
    override fun unregister(profileId: String, key: Any) {
        state.update { it.copy(work = it.work - Key(profileId, key)) }
    }
    override suspend fun cancel(profileId: String) {
        val previous = state.getAndUpdate { it.copy(closed = it.closed + profileId, work = it.work.filterKeys { key -> key.profileId != profileId }) }
        previous.work.filterKeys { it.profileId == profileId }.values.forEach { it() }
    }
}
