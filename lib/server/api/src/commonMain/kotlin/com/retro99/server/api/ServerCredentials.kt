package com.retro99.server.api

import kotlinx.serialization.Serializable

/**
 * Credentials for authenticating with a server.
 */
@Serializable
data class ServerCredentials(
    val serverId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
    /** Optional server-issued account ID; null when the server does not provide one. */
    val accountId: String? = null,
)
