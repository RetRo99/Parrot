package com.retro99.server.api

import kotlinx.coroutines.flow.Flow

/**
 * Central registry for managing multiple server connections.
 * Handles server configuration, authentication state, and active server selection.
 */
interface ServerRegistry {

    // ==================== Server Management ====================

    /**
     * Observe all registered servers.
     */
    fun observeAllServers(): Flow<List<ServerConfig>>

    /**
     * Get all registered servers (suspend version).
     */
    suspend fun getAllServers(): List<ServerConfig>

    /**
     * Add a new server to the registry.
     * @return The created ServerConfig with generated ID
     */
    suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig

    /**
     * Add a new server with a specific ID.
     * Used for special servers like Local that need a fixed ID.
     * @return The created ServerConfig with the specified ID
     */
    suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig

    /**
     * Update an existing server's configuration.
     */
    suspend fun updateServer(config: ServerConfig)

    /**
     * Remove a server and its credentials from the registry.
     */
    suspend fun removeServer(serverId: String)

    /**
     * Get a specific server by ID.
     */
    suspend fun getServer(serverId: String): ServerConfig?

    // ==================== Authentication State ====================

    /**
     * Observe authentication state for all servers.
     */
    fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>>

    /**
     * Observe authentication state for a specific server.
     */
    fun observeAuthState(serverId: String): Flow<ServerAuthState>

    /**
     * Check if a server is currently authenticated.
     */
    suspend fun isAuthenticated(serverId: String): Boolean

    /**
     * Get only servers that are currently authenticated.
     */
    fun observeAuthenticatedServers(): Flow<List<ServerConfig>>

    /**
     * Get authenticated servers (suspend version).
     */
    suspend fun getAuthenticatedServers(): List<ServerConfig>

    // ==================== Credentials Management ====================

    /**
     * Store credentials after successful login.
     */
    suspend fun saveCredentials(credentials: ServerCredentials)

    /**
     * Get credentials for a specific server.
     */
    suspend fun getCredentials(serverId: String): ServerCredentials?

    /**
     * Clear credentials for a specific server (logout).
     */
    suspend fun clearCredentials(serverId: String)

    /**
     * Clear all credentials (logout from all servers).
     */
    suspend fun clearAllCredentials()

    suspend fun deactivateServer(serverId: String)
}
