package com.retro99.login.domain

import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.server.api.ServerConfig

interface LoginRepository {

    suspend fun login(
        serverType: ServerType,
        serverUrl: String,
        username: String,
        password: String,
        existingServerId: String? = null,
    ): CompletableResult

    suspend fun loginWithOAuth(
        serverType: ServerType,
        serverUrl: String,
        existingServerId: String? = null,
    ): CompletableResult

    suspend fun getServerConfig(serverId: String): ServerConfig?

    /**
     * Find out what is hosted at [serverUrl]. [preferredType] is checked first so a server that
     * could match both keeps the type the user picked.
     */
    suspend fun probeServer(
        serverUrl: String,
        preferredType: ServerType,
    ): ServerProbeResult = ServerProbeResult.Unreachable
}
