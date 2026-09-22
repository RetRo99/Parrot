package com.retro99.server.api

import kotlinx.coroutines.flow.Flow

interface ServerAuthStateProvider {
    val serverType: ServerType

    fun observeAuthState(server: ServerConfig): Flow<ServerAuthState>

    suspend fun isAuthenticated(server: ServerConfig): Boolean

    suspend fun clearAuthentication(server: ServerConfig)
}
