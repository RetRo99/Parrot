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
}
