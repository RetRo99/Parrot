package com.retro99.server.api

import kotlinx.serialization.Serializable

/** Device-only HTTP Basic account details; never a bearer access token. */
@Serializable
data class OpdsAccountDetails(val username: String, val password: String) {
    override fun toString(): String = "OpdsAccountDetails(redacted)"
}

interface OpdsCredentialStore {
    fun get(profileId: String, sourceId: String): OpdsAccountDetails?
    suspend fun save(profileId: String, sourceId: String, details: OpdsAccountDetails)
    suspend fun remove(profileId: String, sourceId: String)

    /**
     * A number that changes whenever [sourceId]'s account details are saved, changed or
     * removed. It is kept across restarts and never used twice in a profile, so anything
     * stored under it was fetched with exactly the details the catalogue has now.
     */
    fun accessGeneration(profileId: String, sourceId: String): Long
}

/**
 * Saves new account details for a catalogue of the open profile. Use this, not the store:
 * requests and downloads still running under the old details are stopped first. Removing
 * details is [ServerRegistry.clearCredentials].
 */
interface CatalogueAccountEditor {
    suspend fun saveAccount(sourceId: String, details: OpdsAccountDetails)
}
