package com.retro99.user.api

/** Device work bound to a profile. Cleanup completes before switching/deleting its context. */
interface ProfileWorkRegistry {
    fun activate(profileId: String)
    fun register(profileId: String, key: Any, cancel: suspend () -> Unit): Boolean
    fun unregister(profileId: String, key: Any)
    suspend fun cancel(profileId: String)
}
