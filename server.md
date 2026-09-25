# Multi-Server Architecture Implementation Plan

## Overview

This document outlines the detailed implementation plan for supporting multiple servers (Storyteller, Audiobookshelf, Calibre-Web, etc.) in the StoryTellerKMP application. The architecture uses a **Server Registry Pattern** with per-server authentication and a repository factory for server-specific implementations.

---

## Table of Contents

1. [Phase 1: Core Server Abstractions](#phase-1-core-server-abstractions)
2. [Phase 2: Server Registry Implementation](#phase-2-server-registry-implementation)
3. [Phase 3: Multi-Server Authentication](#phase-3-multi-server-authentication)
4. [Phase 4: Repository Factory Pattern](#phase-4-repository-factory-pattern)
5. [Phase 5: Refactor Existing Storyteller Code](#phase-5-refactor-existing-storyteller-code)
6. [Phase 6: Update Use Cases](#phase-6-update-use-cases)
7. [Phase 7: Update Local Storage](#phase-7-update-local-storage)
8. [Phase 8: UI Changes](#phase-8-ui-changes)
9. [Phase 9: Testing Strategy](#phase-9-testing-strategy)
10. [Future: Adding New Servers](#future-adding-new-servers)

---

## Phase 1: Core Server Abstractions

### 1.1 Create New Module Structure

```
lib/
  server/
    api/                                    # Server abstraction interfaces
      build.gradle.kts
      src/commonMain/kotlin/com/retro99/server/api/
        ServerType.kt
        ServerConfig.kt
        ServerAuthState.kt
        ServerRegistry.kt
        ServerCredentials.kt
        ServerCapabilities.kt
        ServerBooksRepository.kt
        ServerNetworkClient.kt
```

### 1.2 Define Server Type

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerType.kt`**

```kotlin
package com.retro99.server.api

import kotlinx.serialization.Serializable

/**
 * Represents the type of media server.
 * Each type has different API endpoints, authentication methods, and capabilities.
 */
@Serializable
sealed class ServerType {
    abstract val identifier: String
    abstract val displayName: String
    
    @Serializable
    data object Storyteller : ServerType() {
        override val identifier = "storyteller"
        override val displayName = "Storyteller"
    }
    
    @Serializable
    data object Audiobookshelf : ServerType() {
        override val identifier = "audiobookshelf"
        override val displayName = "Audiobookshelf"
    }
    
    @Serializable
    data object CalibreWeb : ServerType() {
        override val identifier = "calibre_web"
        override val displayName = "Calibre-Web"
    }
    
    // Future server types can be added here
}
```

### 1.3 Define Server Configuration

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerConfig.kt`**

```kotlin
package com.retro99.server.api

import kotlinx.serialization.Serializable

/**
 * Configuration for a registered server instance.
 */
@Serializable
data class ServerConfig(
    val id: String,                    // Unique identifier (UUID)
    val name: String,                  // User-defined display name
    val type: ServerType,              // Type of server
    val baseUrl: String,               // Base URL (e.g., "https://books.example.com")
    val addedAt: Long,                 // Timestamp when server was added
    val lastConnectedAt: Long? = null, // Last successful connection
)
```

### 1.4 Define Server Capabilities

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerCapabilities.kt`**

```kotlin
package com.retro99.server.api

/**
 * Describes what features a server type supports.
 * Used to conditionally show/hide UI features.
 */
data class ServerCapabilities(
    val supportsEbooks: Boolean,
    val supportsAudiobooks: Boolean,
    val supportsReadAloud: Boolean,
    val supportsReadingProgress: Boolean,
    val supportsCollections: Boolean,
    val supportsSeries: Boolean,
    val supportsSearch: Boolean,
    val supportsUserLibrary: Boolean,
)

fun ServerType.getCapabilities(): ServerCapabilities = when (this) {
    ServerType.Storyteller -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = true,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.Audiobookshelf -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = true,
        supportsReadAloud = false,
        supportsReadingProgress = true,
        supportsCollections = true,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = true,
    )
    ServerType.CalibreWeb -> ServerCapabilities(
        supportsEbooks = true,
        supportsAudiobooks = false,
        supportsReadAloud = false,
        supportsReadingProgress = false,
        supportsCollections = false,
        supportsSeries = true,
        supportsSearch = true,
        supportsUserLibrary = false,
    )
}
```

### 1.5 Define Server Auth State

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerAuthState.kt`**

```kotlin
package com.retro99.server.api

/**
 * Represents the authentication state for a specific server.
 */
sealed class ServerAuthState {
    abstract val serverId: String
    
    data class Authenticated(
        override val serverId: String,
        val username: String,
        val authenticatedAt: Long,
    ) : ServerAuthState()
    
    data class NotAuthenticated(
        override val serverId: String,
    ) : ServerAuthState()
    
    data class AuthenticationFailed(
        override val serverId: String,
        val error: AuthError,
        val failedAt: Long,
    ) : ServerAuthState()
    
    data class TokenExpired(
        override val serverId: String,
        val expiredAt: Long,
    ) : ServerAuthState()
}

sealed class AuthError {
    data object InvalidCredentials : AuthError()
    data object NetworkError : AuthError()
    data object ServerUnreachable : AuthError()
    data object TokenRefreshFailed : AuthError()
    data class Unknown(val message: String?) : AuthError()
}
```

### 1.6 Define Server Credentials

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerCredentials.kt`**

```kotlin
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
)
```

---

## Phase 2: Server Registry Implementation

### 2.1 Define Server Registry Interface

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerRegistry.kt`**

```kotlin
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

    // ==================== Active Server ====================

    /**
     * Observe the currently active server (for UI context).
     * Returns null if no server is active.
     */
    fun observeActiveServer(): Flow<ServerConfig?>

    /**
     * Get the currently active server.
     */
    suspend fun getActiveServer(): ServerConfig?

    /**
     * Set the active server for UI context.
     */
    suspend fun setActiveServer(serverId: String)

    /**
     * Clear the active server selection.
     */
    suspend fun clearActiveServer()
}
```

### 2.2 Create Server Registry Implementation Module

```
lib/
  server/
    implementation/
      build.gradle.kts
      src/commonMain/kotlin/com/retro99/server/implementation/
        ServerRegistryImpl.kt
        model/
          ServerConfigLocal.kt
          ServerCredentialsLocal.kt
        di/
          ServerModule.kt
```

### 2.3 Implement Server Registry

**File: `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/ServerRegistryImpl.kt`**

```kotlin
package com.retro99.server.implementation

import com.retro99.preferences.api.Preferences
import com.retro99.server.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

@Single(binds = [ServerRegistry::class])
class ServerRegistryImpl(
    private val preferences: Preferences,
) : ServerRegistry {

    private val mutex = Mutex()

    // In-memory cache backed by preferences
    private val _servers = MutableStateFlow<Map<String, ServerConfig>>(emptyMap())
    private val _credentials = MutableStateFlow<Map<String, ServerCredentials>>(emptyMap())
    private val _activeServerId = MutableStateFlow<String?>(null)

    init {
        // Load from preferences on initialization
        loadFromPreferences()
    }

    // ==================== Server Management ====================

    override fun observeAllServers(): Flow<List<ServerConfig>> {
        return _servers.map { it.values.toList().sortedBy { server -> server.name } }
    }

    override suspend fun getAllServers(): List<ServerConfig> {
        return _servers.value.values.toList()
    }

    override suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = mutex.withLock {
        val config = ServerConfig(
            id = generateUuid(),
            name = name,
            type = type,
            baseUrl = baseUrl.trimEnd('/'),
            addedAt = currentTimeMillis(),
        )

        _servers.update { it + (config.id to config) }
        persistServers()

        config
    }

    override suspend fun removeServer(serverId: String) = mutex.withLock {
        _servers.update { it - serverId }
        _credentials.update { it - serverId }

        if (_activeServerId.value == serverId) {
            _activeServerId.value = null
        }

        persistServers()
        persistCredentials()
    }

    // ==================== Authentication State ====================

    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> {
        return combine(_servers, _credentials) { servers, creds ->
            servers.values.filter { server ->
                creds.containsKey(server.id)
            }.toList()
        }
    }

    override suspend fun isAuthenticated(serverId: String): Boolean {
        return _credentials.value.containsKey(serverId)
    }

    override fun observeAuthState(serverId: String): Flow<ServerAuthState> {
        return _credentials.map { creds ->
            val credential = creds[serverId]
            if (credential != null) {
                // Check if token is expired
                val expiresAt = credential.expiresAt
                if (expiresAt != null && expiresAt < currentTimeMillis()) {
                    ServerAuthState.TokenExpired(serverId, expiresAt)
                } else {
                    ServerAuthState.Authenticated(
                        serverId = serverId,
                        username = credential.username,
                        authenticatedAt = currentTimeMillis(), // Could store this
                    )
                }
            } else {
                ServerAuthState.NotAuthenticated(serverId)
            }
        }
    }

    // ==================== Credentials Management ====================

    override suspend fun saveCredentials(credentials: ServerCredentials) = mutex.withLock {
        _credentials.update { it + (credentials.serverId to credentials) }
        persistCredentials()
    }

    override suspend fun getCredentials(serverId: String): ServerCredentials? {
        return _credentials.value[serverId]
    }

    override suspend fun clearCredentials(serverId: String) = mutex.withLock {
        _credentials.update { it - serverId }
        persistCredentials()
    }

    // ==================== Active Server ====================

    override fun observeActiveServer(): Flow<ServerConfig?> {
        return combine(_activeServerId, _servers) { activeId, servers ->
            activeId?.let { servers[it] }
        }
    }

    override suspend fun setActiveServer(serverId: String) {
        _activeServerId.value = serverId
        persistActiveServer()
    }

    // ==================== Persistence ====================

    private fun loadFromPreferences() {
        // Load servers, credentials, and active server from preferences
        // Implementation uses Preferences.getObject<T>()
    }

    private suspend fun persistServers() {
        // Save servers map to preferences
    }

    private suspend fun persistCredentials() {
        // Save credentials map to encrypted preferences
    }

    private suspend fun persistActiveServer() {
        // Save active server ID to preferences
    }
}
```

### 2.4 Add New Preferences Keys

**Update: `lib/preferences/api/src/commonMain/kotlin/com/retro99/preferences/api/Preferences.kt`**

```kotlin
sealed class PreferencesKey(val name: String) {
    // Existing keys
    data object ServerUrl : PreferencesKey("ServerUrl")           // DEPRECATED - migrate to new system
    data object Credentials : PreferencesKey("Credentials")       // DEPRECATED - migrate to new system

    // New multi-server keys
    data object RegisteredServers : PreferencesKey("RegisteredServers")
    data object ServerCredentials : PreferencesKey("ServerCredentials")  // Map of serverId -> credentials
    data object ActiveServerId : PreferencesKey("ActiveServerId")

    // Other existing keys...
    data object ReaderSettings : PreferencesKey("ReaderSettings")
    data object DatabaseSchemaVersion : PreferencesKey("DatabaseSchemaVersion")
    // ...
}
```

---

## Phase 3: Multi-Server Authentication

### 3.1 Create Server-Aware Token Provider

The current `BearerTokenProvider` returns a single token. We need to make it server-aware.

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerTokenProvider.kt`**

```kotlin
package com.retro99.server.api

/**
 * Provides authentication tokens for specific servers.
 */
interface ServerTokenProvider {
    /**
     * Get the bearer token for a specific server.
     * @return Token string or null if not authenticated
     */
    suspend fun getToken(serverId: String): String?

    /**
     * Get the token for the currently active server.
     */
    suspend fun getActiveServerToken(): String?

    /**
     * Refresh the token for a specific server.
     * @return New token or null if refresh failed
     */
    suspend fun refreshToken(serverId: String): String?
}
```

### 3.2 Implement Server Token Provider

**File: `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/ServerTokenProviderImpl.kt`**

```kotlin
package com.retro99.server.implementation

import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerTokenProvider
import org.koin.core.annotation.Single

@Single(binds = [ServerTokenProvider::class])
class ServerTokenProviderImpl(
    private val serverRegistry: ServerRegistry,
) : ServerTokenProvider {

    override suspend fun getToken(serverId: String): String? {
        val credentials = serverRegistry.getCredentials(serverId)

        // Check if token is expired
        credentials?.expiresAt?.let { expiresAt ->
            if (expiresAt < currentTimeMillis()) {
                // Token expired, try to refresh
                return refreshToken(serverId)
            }
        }

        return credentials?.accessToken
    }

    override suspend fun getActiveServerToken(): String? {
        val activeServer = serverRegistry.getActiveServer()
        return activeServer?.let { getToken(it.id) }
    }

    override suspend fun refreshToken(serverId: String): String? {
        // Get server config to determine refresh strategy
        val server = serverRegistry.getServer(serverId) ?: return null
        val credentials = serverRegistry.getCredentials(serverId) ?: return null

        // Delegate to server-specific refresh logic
        // This would use a ServerAuthenticatorFactory
        return null // TODO: Implement per-server refresh
    }
}
```

### 3.3 Create Server-Specific Authenticators

Each server type has different authentication endpoints and flows.

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerAuthenticator.kt`**

```kotlin
package com.retro99.server.api

import com.retro99.base.result.AppResult

/**
 * Handles authentication for a specific server type.
 */
interface ServerAuthenticator {
    /**
     * The server type this authenticator handles.
     */
    val serverType: ServerType

    /**
     * Authenticate with username and password.
     */
    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials>

    /**
     * Refresh an expired token.
     */
    suspend fun refreshToken(
        baseUrl: String,
        refreshToken: String,
    ): AppResult<ServerCredentials>

    /**
     * Validate that a server URL is reachable and correct type.
     */
    suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult>
}

data class ServerValidationResult(
    val isValid: Boolean,
    val serverVersion: String?,
    val serverName: String?,
    val errorMessage: String?,
)
```

### 3.4 Create Authenticator Factory

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerAuthenticatorFactory.kt`**

```kotlin
package com.retro99.server.api

/**
 * Factory for creating server-specific authenticators.
 */
interface ServerAuthenticatorFactory {
    fun create(serverType: ServerType): ServerAuthenticator
}
```

### 3.5 Update Login Use Case for Multi-Server

**File: `feature/login/domain/src/commonMain/kotlin/com/retro99/login/domain/usecase/ServerLoginUseCase.kt`**

```kotlin
package com.retro99.login.domain.usecase

import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.map
import com.retro99.base.result.AppResult
import com.retro99.server.api.*
import org.koin.core.annotation.Factory

/**
 * Use case for logging into a specific server.
 */
@Factory
class ServerLoginUseCase(
    private val serverRegistry: ServerRegistry,
    private val authenticatorFactory: ServerAuthenticatorFactory,
) {
    /**
     * Login to an existing registered server.
     */
    suspend operator fun invoke(
        serverId: String,
        username: String,
        password: String,
    ): AppResult<ServerAuthState.Authenticated> {
        val server = serverRegistry.getServer(serverId)
            ?: return Err(AppError.NotFound("Server not found: $serverId"))

        val authenticator = authenticatorFactory.create(server.type)

        return authenticator.login(server.baseUrl, username, password)
            .flatMap { credentials ->
                serverRegistry.saveCredentials(credentials)
                Ok(ServerAuthState.Authenticated(
                    serverId = serverId,
                    username = username,
                    authenticatedAt = currentTimeMillis(),
                ))
            }
    }

    /**
     * Add a new server and login in one step.
     */
    suspend fun addAndLogin(
        name: String,
        serverType: ServerType,
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerConfig> {
        val authenticator = authenticatorFactory.create(serverType)

        // First validate the server
        return authenticator.validateServer(baseUrl)
            .flatMap { validation ->
                if (!validation.isValid) {
                    return Err(AppError.ValidationError(validation.errorMessage ?: "Invalid server"))
                }

                // Try to authenticate
                authenticator.login(baseUrl, username, password)
            }
            .flatMap { credentials ->
                // Add server to registry
                val server = serverRegistry.addServer(name, serverType, baseUrl)

                // Save credentials with correct server ID
                serverRegistry.saveCredentials(credentials.copy(serverId = server.id))

                // Set as active server
                serverRegistry.setActiveServer(server.id)

                Ok(server)
            }
    }
}
```

### 3.6 Create Server Logout Use Case

**File: `feature/auth/domain/src/commonMain/kotlin/com/retro99/auth/domain/usecase/ServerLogoutUseCase.kt`**

```kotlin
package com.retro99.auth.domain.usecase

import com.github.michaelbull.result.Ok
import com.retro99.base.result.CompletableResult
import com.retro99.database.api.DatabaseCleaner
import com.retro99.server.api.ServerRegistry
import org.koin.core.annotation.Factory

@Factory
class ServerLogoutUseCase(
    private val serverRegistry: ServerRegistry,
    private val databaseCleaner: DatabaseCleaner,
) {
    /**
     * Logout from a specific server.
     */
    suspend operator fun invoke(serverId: String): CompletableResult {
        serverRegistry.clearCredentials(serverId)

        // Optionally clear cached data for this server
        // databaseCleaner.clearDataForServer(serverId)

        return Ok(Unit)
    }

    /**
     * Logout from all servers.
     */
    suspend fun logoutAll(): CompletableResult {
        serverRegistry.clearAllCredentials()
        databaseCleaner.clearAllData()
        return Ok(Unit)
    }
}
```

---

## Phase 4: Repository Factory Pattern

### 4.1 Create Server-Scoped Network Client

Each server needs its own network client with the correct base URL and token.

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerNetworkClient.kt`**

```kotlin
package com.retro99.server.api

import com.retro99.base.result.AppResult
import io.ktor.http.HeadersBuilder

/**
 * Network client scoped to a specific server.
 * Automatically uses the correct base URL and authentication token.
 */
interface ServerNetworkClient {
    val serverId: String
    val baseUrl: String

    suspend fun <T> get(
        path: String,
        typeInfo: TypeInfo,
        queryBuilder: QueryParamsScope.() -> Unit = {},
        headers: HeadersBuilder.() -> Unit = {},
    ): AppResult<T>

    suspend fun <T, R> post(
        path: String,
        body: T,
        responseTypeInfo: TypeInfo,
        headers: HeadersBuilder.() -> Unit = {},
    ): AppResult<R>

    // Other HTTP methods...
}
```

### 4.2 Create Server Network Client Factory

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerNetworkClientFactory.kt`**

```kotlin
package com.retro99.server.api

/**
 * Factory for creating server-scoped network clients.
 */
interface ServerNetworkClientFactory {
    /**
     * Create a network client for a specific server.
     */
    fun create(serverConfig: ServerConfig): ServerNetworkClient

    /**
     * Create a network client for the active server.
     * @throws IllegalStateException if no active server
     */
    suspend fun createForActiveServer(): ServerNetworkClient
}
```

### 4.3 Create Books Repository Factory

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksRepositoryFactory.kt`**

```kotlin
package com.retro99.server.api

import com.retro99.books.domain.BooksRepository

/**
 * Factory for creating server-specific BooksRepository implementations.
 */
interface ServerBooksRepositoryFactory {
    /**
     * Create a BooksRepository for a specific server.
     */
    fun create(serverConfig: ServerConfig): BooksRepository

    /**
     * Create repositories for all authenticated servers.
     */
    suspend fun createForAuthenticatedServers(): List<BooksRepository>
}
```

### 4.4 Implement Books Repository Factory

**File: `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/ServerBooksRepositoryFactoryImpl.kt`**

```kotlin
package com.retro99.server.implementation

import com.retro99.books.domain.BooksRepository
import com.retro99.server.api.*
import com.retro99.server.storyteller.StorytellerBooksRepository
// Future: import com.retro99.server.audiobookshelf.AudiobookshelfBooksRepository
import org.koin.core.annotation.Single

@Single(binds = [ServerBooksRepositoryFactory::class])
class ServerBooksRepositoryFactoryImpl(
    private val serverRegistry: ServerRegistry,
    private val networkClientFactory: ServerNetworkClientFactory,
    private val localSourceFactory: ServerLocalSourceFactory,
) : ServerBooksRepositoryFactory {

    // Cache of created repositories to avoid recreating them
    private val repositoryCache = mutableMapOf<String, BooksRepository>()

    override fun create(serverConfig: ServerConfig): BooksRepository {
        return repositoryCache.getOrPut(serverConfig.id) {
            createRepository(serverConfig)
        }
    }

    override suspend fun createForAuthenticatedServers(): List<BooksRepository> {
        val authenticatedServers = serverRegistry.getAuthenticatedServers()
        return authenticatedServers.map { create(it) }
    }

    private fun createRepository(serverConfig: ServerConfig): BooksRepository {
        val networkClient = networkClientFactory.create(serverConfig)
        val localSource = localSourceFactory.create(serverConfig)

        return when (serverConfig.type) {
            ServerType.Storyteller -> StorytellerBooksRepository(
                serverId = serverConfig.id,
                networkClient = networkClient,
                localSource = localSource,
            )
            ServerType.Audiobookshelf -> {
                // Future implementation
                throw NotImplementedError("Audiobookshelf support coming soon")
            }
            ServerType.CalibreWeb -> {
                // Future implementation
                throw NotImplementedError("Calibre-Web support coming soon")
            }
        }
    }

    /**
     * Clear cached repository when server is removed or credentials change.
     */
    fun invalidateCache(serverId: String) {
        repositoryCache.remove(serverId)
    }
}
```

### 4.5 Create Authenticated Repository Provider

This is what use cases will depend on.

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/AuthenticatedRepositoryProvider.kt`**

```kotlin
package com.retro99.server.api

import com.retro99.books.domain.BooksRepository
import kotlinx.coroutines.flow.Flow

/**
 * Provides repositories only for authenticated servers.
 * Use cases depend on this to get the correct repositories.
 */
interface AuthenticatedRepositoryProvider {
    /**
     * Observe repositories for all authenticated servers.
     * Automatically updates when servers are added/removed or auth state changes.
     */
    fun observeBooksRepositories(): Flow<List<BooksRepository>>

    /**
     * Get repositories for all authenticated servers (suspend version).
     */
    suspend fun getBooksRepositories(): List<BooksRepository>

    /**
     * Get repository for a specific server.
     * @return null if server doesn't exist or is not authenticated
     */
    suspend fun getBooksRepository(serverId: String): BooksRepository?

    /**
     * Get repository for the active server.
     * @return null if no active server or not authenticated
     */
    suspend fun getActiveBooksRepository(): BooksRepository?
}
```

### 4.6 Implement Authenticated Repository Provider

**File: `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/AuthenticatedRepositoryProviderImpl.kt`**

```kotlin
package com.retro99.server.implementation

import com.retro99.books.domain.BooksRepository
import com.retro99.server.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [AuthenticatedRepositoryProvider::class])
class AuthenticatedRepositoryProviderImpl(
    private val serverRegistry: ServerRegistry,
    private val repositoryFactory: ServerBooksRepositoryFactory,
) : AuthenticatedRepositoryProvider {

    override fun observeBooksRepositories(): Flow<List<BooksRepository>> {
        return serverRegistry.observeAuthenticatedServers().map { servers ->
            servers.map { server -> repositoryFactory.create(server) }
        }
    }

    override suspend fun getBooksRepositories(): List<BooksRepository> {
        val servers = serverRegistry.getAuthenticatedServers()
        return servers.map { repositoryFactory.create(it) }
    }

    override suspend fun getBooksRepository(serverId: String): BooksRepository? {
        if (!serverRegistry.isAuthenticated(serverId)) {
            return null
        }
        val server = serverRegistry.getServer(serverId) ?: return null
        return repositoryFactory.create(server)
    }

    override suspend fun getActiveBooksRepository(): BooksRepository? {
        val activeServer = serverRegistry.getActiveServer() ?: return null
        if (!serverRegistry.isAuthenticated(activeServer.id)) {
            return null
        }
        return repositoryFactory.create(activeServer)
    }
}
```

---

## Phase 5: Refactor Existing Storyteller Code

### 5.1 Create Storyteller Server Module

Move existing Storyteller-specific code to a dedicated module.

```
lib/
  server-storyteller/
    build.gradle.kts
    src/commonMain/kotlin/com/retro99/server/storyteller/
      StorytellerBooksRepository.kt
      StorytellerAuthenticator.kt
      api/
        StorytellerBooksRemoteSource.kt
        StorytellerBooksRemoteDataSource.kt
      model/
        StorytellerBookApiModel.kt        # Renamed from BookApiModel
        StorytellerPersonApiModel.kt      # Renamed from PersonApiModel
        StorytellerSeriesApiModel.kt      # etc.
        # ... all other API models
```

### 5.2 Move and Rename API Models

**Current location:** `feature/books/data/src/commonMain/kotlin/com/retro99/books/data/model/BookApiModel.kt`

**New location:** `lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/model/StorytellerBookApiModel.kt`

```kotlin
package com.retro99.server.storyteller.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API model for books from Storyteller server.
 * Maps to the Storyteller API v2 response format.
 */
@Serializable
data class StorytellerBookApiModel(
    @SerialName("uuid") val uuid: String,
    @SerialName("id") val id: Long,
    @SerialName("title") val title: String,
    @SerialName("language") val language: String?,
    @SerialName("created_at") val createdAt: String?,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("publication_date") val publicationDate: String?,
    @SerialName("description") val description: String?,
    @SerialName("rating") val rating: Float?,
    @SerialName("suffix") val suffix: String?,
    @SerialName("subtitle") val subtitle: String?,
    @SerialName("authors") val authors: List<StorytellerPersonApiModel>,
    @SerialName("narrators") val narrators: List<StorytellerPersonApiModel>,
    @SerialName("creators") val creators: List<StorytellerPersonApiModel>,
    @SerialName("series") val series: List<StorytellerSeriesApiModel>,
    @SerialName("tags") val tags: List<StorytellerTagApiModel>,
    @SerialName("collections") val collections: List<StorytellerCollectionApiModel>,
    @SerialName("status") val status: StorytellerStatusApiModel?,
    @SerialName("ebook") val ebook: StorytellerMediaFileApiModel?,
    @SerialName("audiobook") val audiobook: StorytellerMediaFileApiModel?,
    @SerialName("readaloud") val readaloud: StorytellerReadaloudApiModel?,
    @SerialName("aligned_by_storyteller_version") val alignedByStorytellerVersion: String?,
)

// Mapper to domain model
fun StorytellerBookApiModel.toDomain(
    serverId: String,
    baseUrl: String?,
): BookDomainModel.StorytellerBook {
    return BookDomainModel.StorytellerBook(
        uuid = uuid,
        serverId = serverId,  // NEW: Track which server this book came from
        title = title,
        id = id,
        // ... rest of mapping
    )
}
```

### 5.3 Create Storyteller Books Repository

**File: `lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/StorytellerBooksRepository.kt`**

```kotlin
package com.retro99.server.storyteller

import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppResult
import com.retro99.base.result.mapCatching
import com.retro99.books.domain.BooksRepository
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.ServerNetworkClient
import com.retro99.server.storyteller.api.StorytellerBooksRemoteSource
import com.retro99.server.storyteller.model.toDomain
import kotlinx.coroutines.flow.Flow

/**
 * BooksRepository implementation for Storyteller servers.
 */
class StorytellerBooksRepository(
    private val serverId: String,
    private val baseUrl: String,
    private val remoteSource: StorytellerBooksRemoteSource,
    private val localSource: ServerBooksLocalSource,
) : BooksRepository, BaseRepository {

    override fun getBooks(): Flow<AppResult<List<BookDomainModel>>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBooks(serverId).mapCatching { books ->
                    books?.map { it.toDomain(serverId, baseUrl) }
                }
            },
            remoteSource = {
                remoteSource.getBooks().mapCatching { books ->
                    books.map { it.toDomain(serverId, baseUrl) }
                        .sortedBy { it.title.lowercase() }
                }
            },
            saveToCache = { domainBooks ->
                localSource.saveBooks(
                    serverId = serverId,
                    books = domainBooks.filterIsInstance<BookDomainModel.StorytellerBook>()
                        .map { it.toLocal() }
                )
            },
        )
    }

    override fun getBook(uuid: String): Flow<AppResult<BookDomainModel>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBook(serverId, uuid).mapCatching { book ->
                    book?.toDomain(serverId, baseUrl)
                }
            },
            remoteSource = {
                remoteSource.getBook(uuid).mapCatching { book ->
                    book.toDomain(serverId, baseUrl)
                }
            },
            saveToCache = { domainBook ->
                localSource.saveBook(serverId, domainBook.toLocal())
            },
        )
    }

    override suspend fun saveBook(book: BookDomainModel): CompletableResult {
        // Storyteller doesn't support uploading books yet
        return Err(AppError.NotSupported("Uploading books to Storyteller is not yet supported"))
    }
}
```

### 5.4 Create Storyteller Authenticator

**File: `lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/StorytellerAuthenticator.kt`**

```kotlin
package com.retro99.server.storyteller

import com.retro99.base.result.AppResult
import com.retro99.server.api.*
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters

class StorytellerAuthenticator(
    private val httpClient: HttpClient,
) : ServerAuthenticator {

    override val serverType = ServerType.Storyteller

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        return try {
            val response = httpClient.submitForm(
                url = "$baseUrl/api/v2/token",
                formParameters = Parameters.build {
                    append("usernameOrEmail", username)
                    append("password", password)
                }
            )

            // Parse response and create credentials
            val tokenResponse = response.body<StorytellerTokenResponse>()

            Ok(ServerCredentials(
                serverId = "", // Will be set by caller
                username = username,
                accessToken = tokenResponse.accessToken,
                refreshToken = null, // Storyteller does not support refresh tokens
                expiresAt = null, // The current expires_in response is not a reliable deadline
            ))
        } catch (e: Exception) {
            Err(mapException(e))
        }
    }

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> {
        return try {
            val normalizedBaseUrl = baseUrl.trimEnd('/')
            val healthResponse = httpClient.get("$normalizedBaseUrl/api/health")
            val booksStatus = if (healthResponse.status.isSuccess()) {
                null
            } else {
                httpClient.get("$normalizedBaseUrl/api/v2/books").status
            }
            val isStoryteller = healthResponse.status.isSuccess() ||
                booksStatus?.isSuccess() == true ||
                booksStatus?.value in setOf(401, 405)

            Ok(ServerValidationResult(
                isValid = isStoryteller,
                serverVersion = null,
                serverName = if (isStoryteller) "Storyteller" else null,
                errorMessage = if (isStoryteller) {
                    null
                } else {
                    "Server returned ${booksStatus ?: healthResponse.status}"
                },
            ))
        } catch (e: Exception) {
            Ok(ServerValidationResult(
                isValid = false,
                serverVersion = null,
                serverName = null,
                errorMessage = e.message,
            ))
        }
    }

    override suspend fun refreshToken(
        baseUrl: String,
        refreshToken: String,
    ): AppResult<ServerCredentials> {
        // Implement Storyteller token refresh
        TODO("Implement token refresh")
    }
}
```

---

## Phase 6: Update Use Cases

### 6.1 Update GetBooksUseCase

The key change is using `AuthenticatedRepositoryProvider` instead of a static list.

**File: `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/GetBooksUseCase.kt`**

```kotlin
package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Factory

@Factory
class GetBooksUseCase(
    private val repositoryProvider: AuthenticatedRepositoryProvider,
    private val importedBooksRepository: ImportedBooksRepository, // Local books always included
) {
    /**
     * Get books from all authenticated servers plus local imported books.
     * Automatically updates when servers are added/removed or auth state changes.
     */
    operator fun invoke(): Flow<AppResult<List<BookDomainModel>>> {
        return repositoryProvider.observeBooksRepositories()
            .flatMapLatest { repositories ->
                // Always include imported books repository
                val allRepositories = repositories + importedBooksRepository

                if (allRepositories.isEmpty()) {
                    flowOf(Ok(emptyList()))
                } else {
                    combine(allRepositories.map { it.getBooks() }) { results ->
                        val allBooks = results.flatMap { it.getOrElse { emptyList() } }
                        Ok(allBooks.sortedBy { it.title.lowercase() })
                    }
                }
            }
    }
}
```

### 6.2 Create GetBooksWithServerStatusUseCase

For UI that needs to show server connection status.

**File: `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/GetBooksWithServerStatusUseCase.kt`**

```kotlin
package com.retro99.books.domain.usecase

import com.github.michaelbull.result.fold
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Factory

/**
 * Result that includes both books and server status information.
 */
data class BooksWithServerStatus(
    val books: List<BookDomainModel>,
    val serverStatuses: List<ServerFetchStatus>,
)

sealed class ServerFetchStatus {
    abstract val server: ServerConfig

    data class Success(
        override val server: ServerConfig,
        val bookCount: Int,
    ) : ServerFetchStatus()

    data class NotAuthenticated(
        override val server: ServerConfig,
    ) : ServerFetchStatus()

    data class Error(
        override val server: ServerConfig,
        val error: AppError,
    ) : ServerFetchStatus()

    data class Loading(
        override val server: ServerConfig,
    ) : ServerFetchStatus()
}

@Factory
class GetBooksWithServerStatusUseCase(
    private val serverRegistry: ServerRegistry,
    private val repositoryFactory: ServerBooksRepositoryFactory,
) {
    operator fun invoke(): Flow<AppResult<BooksWithServerStatus>> {
        return serverRegistry.observeAllServers()
            .flatMapLatest { allServers ->
                if (allServers.isEmpty()) {
                    flowOf(Ok(BooksWithServerStatus(emptyList(), emptyList())))
                } else {
                    val serverFlows = allServers.map { server ->
                        fetchFromServer(server)
                    }
                    combine(serverFlows) { results ->
                        val allBooks = results.flatMap { it.books }
                        val statuses = results.map { it.status }
                        Ok(BooksWithServerStatus(
                            books = allBooks.sortedBy { it.title.lowercase() },
                            serverStatuses = statuses,
                        ))
                    }
                }
            }
    }

    private fun fetchFromServer(server: ServerConfig): Flow<ServerFetchResult> {
        return flow {
            // Emit loading state first
            emit(ServerFetchResult(
                books = emptyList(),
                status = ServerFetchStatus.Loading(server),
            ))

            // Check authentication
            val isAuthenticated = serverRegistry.isAuthenticated(server.id)
            if (!isAuthenticated) {
                emit(ServerFetchResult(
                    books = emptyList(),
                    status = ServerFetchStatus.NotAuthenticated(server),
                ))
                return@flow
            }

            // Fetch books
            val repository = repositoryFactory.create(server)
            repository.getBooks().collect { result ->
                result.fold(
                    success = { books ->
                        emit(ServerFetchResult(
                            books = books,
                            status = ServerFetchStatus.Success(server, books.size),
                        ))
                    },
                    failure = { error ->
                        emit(ServerFetchResult(
                            books = emptyList(),
                            status = ServerFetchStatus.Error(server, error),
                        ))
                    }
                )
            }
        }
    }
}

private data class ServerFetchResult(
    val books: List<BookDomainModel>,
    val status: ServerFetchStatus,
)
```

### 6.3 Update GetBookByUuidUseCase

**File: `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/GetBookByUuidUseCase.kt`**

```kotlin
package com.retro99.books.domain.usecase

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory

@Factory
class GetBookByUuidUseCase(
    private val repositoryProvider: AuthenticatedRepositoryProvider,
    private val importedBooksRepository: ImportedBooksRepository,
) {
    operator fun invoke(uuid: String): Flow<AppResult<BookDomainModel>> {
        return repositoryProvider.observeBooksRepositories()
            .flatMapLatest { repositories ->
                val allRepositories = repositories + importedBooksRepository

                if (allRepositories.isEmpty()) {
                    flowOf(Err(AppError.NotFound("No repositories available")))
                } else {
                    combine(allRepositories.map { it.getBook(uuid) }) { results ->
                        // Return first successful result
                        results.mapNotNull { it.getOrElse { null } }.firstOrNull()
                    }.map { book ->
                        book?.let { Ok(it) }
                            ?: Err(AppError.NotFound("Book not found: $uuid"))
                    }
                }
            }
    }

    /**
     * Get a book from a specific server.
     */
    suspend fun fromServer(serverId: String, uuid: String): Flow<AppResult<BookDomainModel>> {
        val repository = repositoryProvider.getBooksRepository(serverId)
            ?: return flowOf(Err(AppError.NotFound("Server not found or not authenticated: $serverId")))

        return repository.getBook(uuid)
    }
}
```

---

## Phase 7: Update Local Storage

### 7.1 Add Server ID to Database Entities

Books cached locally need to track which server they came from.

**Update: `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BookEntity.kt`**

```kotlin
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,

    // NEW: Track which server this book belongs to
    @ColumnInfo(name = "server_id")
    val serverId: String,

    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "title")
    val title: String,

    // ... rest of fields
)
```

### 7.2 Update Database Queries

**Update: `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/books/BooksDatabase.kt`**

```kotlin
interface BooksDatabase {

    // Get all books for a specific server
    suspend fun getBooksByServer(serverId: String): List<BookEntity>

    // Get a specific book (now needs server context for uniqueness)
    suspend fun getBookByUuid(serverId: String, uuid: String): BookEntity?

    // Legacy: Get book by UUID across all servers (for backwards compatibility)
    suspend fun getBookByUuid(uuid: String): BookEntity?

    // Save books for a specific server
    suspend fun upsertBooks(serverId: String, books: List<BookEntity>)

    // Clear cache for a specific server
    suspend fun clearBooksForServer(serverId: String)

    // Clear all cached books
    suspend fun clearAllBooks()

    // Get all books across all servers
    fun getAllBooks(): List<BookEntity>
}
```

### 7.3 Create Server-Aware Local Source Interface

**File: `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksLocalSource.kt`**

```kotlin
package com.retro99.server.api

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult

/**
 * Local data source that is server-aware.
 * Caches books per-server to avoid conflicts.
 */
interface ServerBooksLocalSource {

    suspend fun getBooks(serverId: String): AppResult<List<BookLocalModel>?>

    suspend fun getBook(serverId: String, uuid: String): AppResult<BookLocalModel?>

    suspend fun saveBooks(serverId: String, books: List<BookLocalModel>): CompletableResult

    suspend fun saveBook(serverId: String, book: BookLocalModel): CompletableResult

    suspend fun clearCache(serverId: String): CompletableResult

    suspend fun clearAllCache(): CompletableResult
}
```

### 7.4 Database Migration

**File: `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/migrations/Migration_AddServerId.kt`**

```kotlin
package com.retro99.database.implementation.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration to add server_id column to books table.
 * Existing books are assigned to a "legacy" server that will be migrated
 * when the user re-authenticates.
 */
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(connection: SQLiteConnection) {
        // Add server_id column with default value for existing data
        connection.execSQL(
            "ALTER TABLE books ADD COLUMN server_id TEXT NOT NULL DEFAULT 'legacy_storyteller'"
        )

        // Create index for efficient server-based queries
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS index_books_server_id ON books(server_id)"
        )

        // Update composite primary key or unique constraint
        // Note: SQLite doesn't support modifying primary keys, so we may need
        // to recreate the table if uuid alone isn't unique across servers
    }
}
```

### 7.5 Update Domain Model with Server ID

**Update: `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/model/BookDomainModel.kt`**

```kotlin
sealed class BookDomainModel {
    abstract val uuid: String
    abstract val title: String
    abstract val description: String?
    abstract val coverUrl: String?
    abstract val series: List<SeriesDomainModel>

    // NEW: Track which server this book belongs to (null for local books)
    abstract val serverId: String?

    data class StorytellerBook(
        override val uuid: String,
        override val serverId: String,  // Required for server books
        override val title: String,
        // ... rest of fields
    ) : BookDomainModel()

    data class LocalBook(
        override val uuid: String,
        override val title: String,
        // ... rest of fields
    ) : BookDomainModel() {
        override val serverId: String? = null  // Local books have no server
        override val series: List<SeriesDomainModel> = emptyList()
    }

    // Future: Add more book types for other servers
    // data class AudiobookshelfBook(...) : BookDomainModel()
}
```

---

## Phase 8: UI Changes

### 8.1 Server Management Screen

Create a new screen for managing servers.

**File: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/servers/ServerManagementScreen.kt`**

```kotlin
@Composable
fun ServerManagementScreen(
    viewModel: ServerManagementViewModel,
) {
    val state by viewModel.state.collectAsState()

    Column {
        // Header
        Text("Servers", style = MaterialTheme.typography.headlineMedium)

        // List of servers with status
        LazyColumn {
            items(state.servers) { serverWithStatus ->
                ServerListItem(
                    server = serverWithStatus.server,
                    authState = serverWithStatus.authState,
                    isActive = serverWithStatus.isActive,
                    onServerClick = { viewModel.onServerClick(serverWithStatus.server.id) },
                    onLoginClick = { viewModel.onLoginClick(serverWithStatus.server.id) },
                    onLogoutClick = { viewModel.onLogoutClick(serverWithStatus.server.id) },
                    onRemoveClick = { viewModel.onRemoveClick(serverWithStatus.server.id) },
                )
            }
        }

        // Add server button
        Button(onClick = { viewModel.onAddServerClick() }) {
            Text("Add Server")
        }
    }
}

@Composable
fun ServerListItem(
    server: ServerConfig,
    authState: ServerAuthState,
    isActive: Boolean,
    onServerClick: () -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onServerClick),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Server type icon
            Icon(
                imageVector = server.type.icon(),
                contentDescription = server.type.displayName,
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(server.baseUrl, style = MaterialTheme.typography.bodySmall)

                // Auth status
                when (authState) {
                    is ServerAuthState.Authenticated -> {
                        Text(
                            "Logged in as ${authState.username}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ServerAuthState.NotAuthenticated -> {
                        Text(
                            "Not logged in",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is ServerAuthState.TokenExpired -> {
                        Text(
                            "Session expired",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    is ServerAuthState.AuthenticationFailed -> {
                        Text(
                            "Login failed: ${authState.error}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Actions
            if (authState is ServerAuthState.Authenticated) {
                IconButton(onClick = onLogoutClick) {
                    Icon(Icons.Default.Logout, "Logout")
                }
            } else {
                IconButton(onClick = onLoginClick) {
                    Icon(Icons.Default.Login, "Login")
                }
            }

            IconButton(onClick = onRemoveClick) {
                Icon(Icons.Default.Delete, "Remove")
            }
        }
    }
}
```

### 8.2 Add Server Dialog

**File: `feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/servers/AddServerDialog.kt`**

```kotlin
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAddServer: (name: String, type: ServerType, url: String, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var serverType by remember { mutableStateOf<ServerType>(ServerType.Storyteller) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Server") },
        text = {
            Column {
                // Server name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("My Storyteller Server") },
                )

                Spacer(Modifier.height(8.dp))

                // Server type dropdown
                ExposedDropdownMenuBox(...) {
                    // Dropdown for ServerType selection
                }

                Spacer(Modifier.height(8.dp))

                // Server URL
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://books.example.com") },
                )

                Spacer(Modifier.height(16.dp))

                // Credentials
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAddServer(name, serverType, url, username, password) },
                enabled = name.isNotBlank() && url.isNotBlank() && username.isNotBlank(),
            ) {
                Text("Add & Login")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
```

### 8.3 Update Books Screen to Show Server Status

**Update: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/list/BooksListScreen.kt`**

```kotlin
@Composable
fun BooksListScreen(
    viewModel: BooksListViewModel,
) {
    val state by viewModel.state.collectAsState()

    Column {
        // Server status bar (optional, can be collapsed)
        if (state.showServerStatus) {
            ServerStatusBar(
                serverStatuses = state.serverStatuses,
                onServerClick = { serverId -> viewModel.onServerClick(serverId) },
            )
        }

        // Books grid/list
        when {
            state.isLoading -> LoadingIndicator()
            state.books.isEmpty() -> EmptyState(
                hasServers = state.serverStatuses.isNotEmpty(),
                hasAuthenticatedServers = state.serverStatuses.any {
                    it is ServerFetchStatus.Success
                },
            )
            else -> BooksList(
                books = state.books,
                onBookClick = { viewModel.onBookClick(it) },
            )
        }
    }
}

@Composable
fun ServerStatusBar(
    serverStatuses: List<ServerFetchStatus>,
    onServerClick: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(serverStatuses) { status ->
            ServerStatusChip(
                status = status,
                onClick = { onServerClick(status.server.id) },
            )
        }
    }
}

@Composable
fun ServerStatusChip(
    status: ServerFetchStatus,
    onClick: () -> Unit,
) {
    val (icon, color, text) = when (status) {
        is ServerFetchStatus.Success -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "${status.server.name}: ${status.bookCount} books",
        )
        is ServerFetchStatus.NotAuthenticated -> Triple(
            Icons.Default.Lock,
            MaterialTheme.colorScheme.error,
            "${status.server.name}: Not logged in",
        )
        is ServerFetchStatus.Error -> Triple(
            Icons.Default.Error,
            MaterialTheme.colorScheme.error,
            "${status.server.name}: Error",
        )
        is ServerFetchStatus.Loading -> Triple(
            Icons.Default.Sync,
            MaterialTheme.colorScheme.secondary,
            "${status.server.name}: Loading...",
        )
    }

    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = { Icon(icon, null, tint = color) },
    )
}
```

---

## Phase 9: Testing Strategy

### 9.1 Unit Tests for Server Registry

```kotlin
class ServerRegistryImplTest {

    private lateinit var preferences: FakePreferences
    private lateinit var serverRegistry: ServerRegistryImpl

    @BeforeTest
    fun setup() {
        preferences = FakePreferences()
        serverRegistry = ServerRegistryImpl(preferences)
    }

    @Test
    fun `addServer creates server with unique ID`() = runTest {
        val server = serverRegistry.addServer(
            name = "Test Server",
            type = ServerType.Storyteller,
            baseUrl = "https://test.com",
        )

        assertNotNull(server.id)
        assertEquals("Test Server", server.name)
        assertEquals(ServerType.Storyteller, server.type)
    }

    @Test
    fun `observeAuthenticatedServers only returns servers with credentials`() = runTest {
        // Add two servers
        val server1 = serverRegistry.addServer("Server 1", ServerType.Storyteller, "https://s1.com")
        val server2 = serverRegistry.addServer("Server 2", ServerType.Storyteller, "https://s2.com")

        // Only authenticate server1
        serverRegistry.saveCredentials(ServerCredentials(
            serverId = server1.id,
            username = "user",
            accessToken = "token",
        ))

        // Verify only server1 is returned
        val authenticated = serverRegistry.observeAuthenticatedServers().first()
        assertEquals(1, authenticated.size)
        assertEquals(server1.id, authenticated[0].id)
    }

    @Test
    fun `clearCredentials removes server from authenticated list`() = runTest {
        val server = serverRegistry.addServer("Server", ServerType.Storyteller, "https://s.com")
        serverRegistry.saveCredentials(ServerCredentials(
            serverId = server.id,
            username = "user",
            accessToken = "token",
        ))

        assertTrue(serverRegistry.isAuthenticated(server.id))

        serverRegistry.clearCredentials(server.id)

        assertFalse(serverRegistry.isAuthenticated(server.id))
    }

    @Test
    fun `removeServer also removes credentials`() = runTest {
        val server = serverRegistry.addServer("Server", ServerType.Storyteller, "https://s.com")
        serverRegistry.saveCredentials(ServerCredentials(
            serverId = server.id,
            username = "user",
            accessToken = "token",
        ))

        serverRegistry.removeServer(server.id)

        assertNull(serverRegistry.getServer(server.id))
        assertNull(serverRegistry.getCredentials(server.id))
    }
}
```

### 9.2 Unit Tests for GetBooksUseCase

```kotlin
class GetBooksUseCaseTest {

    private lateinit var repositoryProvider: FakeAuthenticatedRepositoryProvider
    private lateinit var importedBooksRepository: FakeImportedBooksRepository
    private lateinit var useCase: GetBooksUseCase

    @BeforeTest
    fun setup() {
        repositoryProvider = FakeAuthenticatedRepositoryProvider()
        importedBooksRepository = FakeImportedBooksRepository()
        useCase = GetBooksUseCase(repositoryProvider, importedBooksRepository)
    }

    @Test
    fun `returns books from all authenticated servers`() = runTest {
        // Setup two authenticated servers with books
        val server1Books = listOf(createBook("book1", "server1"))
        val server2Books = listOf(createBook("book2", "server2"))

        repositoryProvider.addRepository(FakeBooksRepository(server1Books))
        repositoryProvider.addRepository(FakeBooksRepository(server2Books))

        val result = useCase().first()

        assertTrue(result.isOk)
        assertEquals(2, result.value.size)
    }

    @Test
    fun `includes imported books even when no servers authenticated`() = runTest {
        val importedBook = createLocalBook("imported1")
        importedBooksRepository.addBook(importedBook)

        // No authenticated servers
        repositoryProvider.clear()

        val result = useCase().first()

        assertTrue(result.isOk)
        assertEquals(1, result.value.size)
        assertTrue(result.value[0] is BookDomainModel.LocalBook)
    }

    @Test
    fun `updates when server auth state changes`() = runTest {
        val serverBooks = listOf(createBook("book1", "server1"))

        val results = mutableListOf<AppResult<List<BookDomainModel>>>()
        val job = launch {
            useCase().collect { results.add(it) }
        }

        // Initially no servers
        advanceUntilIdle()
        assertEquals(1, results.size)
        assertEquals(0, results[0].value.size)

        // Add authenticated server
        repositoryProvider.addRepository(FakeBooksRepository(serverBooks))
        advanceUntilIdle()

        assertEquals(2, results.size)
        assertEquals(1, results[1].value.size)

        job.cancel()
    }
}
```

### 9.3 Integration Tests

```kotlin
class MultiServerIntegrationTest {

    @Test
    fun `full flow - add server, login, fetch books, logout`() = runTest {
        // Setup
        val serverRegistry = ServerRegistryImpl(FakePreferences())
        val authenticatorFactory = FakeServerAuthenticatorFactory()
        val repositoryFactory = FakeServerBooksRepositoryFactory()

        val loginUseCase = ServerLoginUseCase(serverRegistry, authenticatorFactory)
        val getBooksUseCase = GetBooksUseCase(
            AuthenticatedRepositoryProviderImpl(serverRegistry, repositoryFactory),
            FakeImportedBooksRepository(),
        )
        val logoutUseCase = ServerLogoutUseCase(serverRegistry, FakeDatabaseCleaner())

        // Add and login to server
        val result = loginUseCase.addAndLogin(
            name = "Test Server",
            serverType = ServerType.Storyteller,
            baseUrl = "https://test.com",
            username = "user",
            password = "pass",
        )

        assertTrue(result.isOk)
        val server = result.value

        // Verify books are fetched
        val books = getBooksUseCase().first()
        assertTrue(books.isOk)
        assertTrue(books.value.isNotEmpty())

        // Logout
        logoutUseCase(server.id)

        // Verify no books from server
        val booksAfterLogout = getBooksUseCase().first()
        assertTrue(booksAfterLogout.isOk)
        assertTrue(booksAfterLogout.value.none { it.serverId == server.id })
    }
}
```

---

## Future: Adding New Servers

### Adding Audiobookshelf Support (Example)

When you want to add a new server type like Audiobookshelf, follow these steps:

### Step 1: Add Server Type

```kotlin
// In ServerType.kt
@Serializable
data object Audiobookshelf : ServerType() {
    override val identifier = "audiobookshelf"
    override val displayName = "Audiobookshelf"
}
```

### Step 2: Create Server Module

```
lib/
  server-audiobookshelf/
    build.gradle.kts
    src/commonMain/kotlin/com/retro99/server/audiobookshelf/
      AudiobookshelfAuthenticator.kt
      AudiobookshelfBooksRepository.kt
      api/
        AudiobookshelfBooksRemoteSource.kt
      model/
        AudiobookshelfBookApiModel.kt
        AudiobookshelfLibraryApiModel.kt
```

### Step 3: Implement API Models

```kotlin
// Audiobookshelf has a different API structure
@Serializable
data class AudiobookshelfBookApiModel(
    val id: String,
    val libraryId: String,
    val title: String,
    val authorName: String?,
    val duration: Double?,  // Audiobookshelf tracks duration
    val progress: AudiobookshelfProgressApiModel?,
    // ... Audiobookshelf-specific fields
)

fun AudiobookshelfBookApiModel.toDomain(
    serverId: String,
    baseUrl: String,
): BookDomainModel.AudiobookshelfBook {
    return BookDomainModel.AudiobookshelfBook(
        uuid = id,
        serverId = serverId,
        title = title,
        // Map to common domain model
    )
}
```

### Step 4: Implement Authenticator

```kotlin
class AudiobookshelfAuthenticator(
    private val httpClient: HttpClient,
) : ServerAuthenticator {

    override val serverType = ServerType.Audiobookshelf

    override suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
    ): AppResult<ServerCredentials> {
        // Audiobookshelf uses POST /login with JSON body
        return try {
            val response = httpClient.post("$baseUrl/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "username" to username,
                    "password" to password,
                ))
            }

            val tokenResponse = response.body<AudiobookshelfLoginResponse>()

            Ok(ServerCredentials(
                serverId = "",
                username = username,
                accessToken = tokenResponse.token,
                refreshToken = null,  // Audiobookshelf doesn't use refresh tokens
                expiresAt = null,
            ))
        } catch (e: Exception) {
            Err(mapException(e))
        }
    }

    override suspend fun validateServer(baseUrl: String): AppResult<ServerValidationResult> {
        // Check /ping or /api/status endpoint
        return try {
            val response = httpClient.get("$baseUrl/ping")
            Ok(ServerValidationResult(
                isValid = response.status.isSuccess(),
                serverVersion = null,
                serverName = "Audiobookshelf",
                errorMessage = null,
            ))
        } catch (e: Exception) {
            Ok(ServerValidationResult(
                isValid = false,
                serverVersion = null,
                serverName = null,
                errorMessage = e.message,
            ))
        }
    }
}
```

### Step 5: Implement Repository

```kotlin
class AudiobookshelfBooksRepository(
    private val serverId: String,
    private val baseUrl: String,
    private val remoteSource: AudiobookshelfBooksRemoteSource,
    private val localSource: ServerBooksLocalSource,
) : BooksRepository, BaseRepository {

    override fun getBooks(): Flow<AppResult<List<BookDomainModel>>> {
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBooks(serverId).mapCatching { books ->
                    books?.map { it.toDomain(serverId, baseUrl) }
                }
            },
            remoteSource = {
                // Audiobookshelf requires fetching libraries first, then items
                remoteSource.getLibraries().flatMap { libraries ->
                    val allBooks = libraries.flatMap { library ->
                        remoteSource.getLibraryItems(library.id).getOrElse { emptyList() }
                    }
                    Ok(allBooks.map { it.toDomain(serverId, baseUrl) })
                }
            },
            saveToCache = { domainBooks ->
                localSource.saveBooks(serverId, domainBooks.map { it.toLocal() })
            },
        )
    }
}
```

### Step 6: Register in Factory

```kotlin
// In ServerBooksRepositoryFactoryImpl
private fun createRepository(serverConfig: ServerConfig): BooksRepository {
    return when (serverConfig.type) {
        ServerType.Storyteller -> StorytellerBooksRepository(...)
        ServerType.Audiobookshelf -> AudiobookshelfBooksRepository(
            serverId = serverConfig.id,
            baseUrl = serverConfig.baseUrl,
            remoteSource = AudiobookshelfBooksRemoteDataSource(networkClient),
            localSource = localSourceFactory.create(serverConfig),
        )
        ServerType.CalibreWeb -> TODO()
    }
}

// In ServerAuthenticatorFactoryImpl
override fun create(serverType: ServerType): ServerAuthenticator {
    return when (serverType) {
        ServerType.Storyteller -> StorytellerAuthenticator(httpClient)
        ServerType.Audiobookshelf -> AudiobookshelfAuthenticator(httpClient)
        ServerType.CalibreWeb -> TODO()
    }
}
```

### Step 7: Add Domain Model Variant (Optional)

If the new server has unique fields that don't fit in existing domain models:

```kotlin
sealed class BookDomainModel {
    // ... existing variants

    /**
     * Book from Audiobookshelf server.
     */
    data class AudiobookshelfBook(
        override val uuid: String,
        override val serverId: String,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        override val series: List<SeriesDomainModel>,

        // Audiobookshelf-specific fields
        val libraryId: String,
        val duration: Double?,
        val progress: Float?,
        val isFinished: Boolean,
    ) : BookDomainModel()
}
```

---

## Migration Strategy

### Migrating Existing Users

When users update to the multi-server version:

1. **Detect existing credentials**: Check for `PreferencesKey.Credentials` (old format)

2. **Create legacy server**: If old credentials exist, create a server entry:
   ```kotlin
   val legacyServer = ServerConfig(
       id = "legacy_storyteller",
       name = "My Storyteller Server",
       type = ServerType.Storyteller,
       baseUrl = oldCredentials.serverUrl,
       addedAt = currentTimeMillis(),
   )
   ```

3. **Migrate credentials**: Convert old credentials to new format:
   ```kotlin
   val newCredentials = ServerCredentials(
       serverId = "legacy_storyteller",
       username = oldCredentials.username,
       accessToken = oldCredentials.token,
   )
   ```

4. **Set as active**: Make the migrated server the active server

5. **Clean up**: Remove old preference keys after successful migration

### Migration Code

```kotlin
class CredentialsMigration(
    private val preferences: Preferences,
    private val serverRegistry: ServerRegistry,
) {
    suspend fun migrateIfNeeded() {
        // Check if already migrated
        if (preferences.getBoolean(PreferencesKey.MultiServerMigrated)) {
            return
        }

        // Check for old credentials
        val oldCredentials = preferences.getObject<OldCredentialsLocalModel>(
            PreferencesKey.Credentials
        ) ?: return

        val oldServerUrl = preferences.getStringOrNull(PreferencesKey.ServerUrl)
            ?: return

        // Create server entry
        val server = serverRegistry.addServer(
            name = "Storyteller Server",
            type = ServerType.Storyteller,
            baseUrl = oldServerUrl,
        )

        // Save credentials
        serverRegistry.saveCredentials(ServerCredentials(
            serverId = server.id,
            username = oldCredentials.username,
            accessToken = oldCredentials.token,
        ))

        // Set as active
        serverRegistry.setActiveServer(server.id)

        // Mark as migrated
        preferences.putBoolean(PreferencesKey.MultiServerMigrated, true)

        // Optionally clean up old keys
        // preferences.remove(PreferencesKey.Credentials)
        // preferences.remove(PreferencesKey.ServerUrl)
    }
}
```

---

## Summary

### New Modules to Create

| Module | Purpose |
|--------|---------|
| `lib/server/api` | Server abstraction interfaces |
| `lib/server/implementation` | ServerRegistry, factories, providers |
| `lib/server-storyteller` | Storyteller-specific implementation |
| `lib/server-audiobookshelf` | Future: Audiobookshelf implementation |

### Key Interfaces

| Interface | Purpose |
|-----------|---------|
| `ServerRegistry` | Manages servers and auth state |
| `ServerAuthenticator` | Server-specific authentication |
| `ServerBooksRepositoryFactory` | Creates repositories per server |
| `AuthenticatedRepositoryProvider` | Provides repos for authenticated servers only |
| `ServerTokenProvider` | Provides tokens per server |

### Files to Modify

| File | Changes |
|------|---------|
| `BookDomainModel.kt` | Add `serverId` field |
| `BookEntity.kt` | Add `server_id` column |
| `GetBooksUseCase.kt` | Use `AuthenticatedRepositoryProvider` |
| `GetBookByUuidUseCase.kt` | Use `AuthenticatedRepositoryProvider` |
| `Preferences.kt` | Add new preference keys |
| `BooksDatabase.kt` | Add server-aware queries |

### Estimated Effort

| Phase | Effort |
|-------|--------|
| Phase 1: Core Abstractions | 2-3 days |
| Phase 2: Server Registry | 2-3 days |
| Phase 3: Multi-Server Auth | 3-4 days |
| Phase 4: Repository Factory | 2-3 days |
| Phase 5: Refactor Storyteller | 3-4 days |
| Phase 6: Update Use Cases | 2-3 days |
| Phase 7: Update Local Storage | 2-3 days |
| Phase 8: UI Changes | 4-5 days |
| Phase 9: Testing | 3-4 days |
| **Total** | **~25-32 days** |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                 UI Layer                                     │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────────────────┐ │
│  │ BooksListScreen │  │ ServerMgmtScreen │  │ AddServerDialog             │ │
│  └────────┬────────┘  └────────┬─────────┘  └─────────────────────────────┘ │
└───────────┼────────────────────┼────────────────────────────────────────────┘
            │                    │
┌───────────┼────────────────────┼────────────────────────────────────────────┐
│           │     Domain Layer   │                                             │
│  ┌────────▼────────┐  ┌────────▼─────────┐  ┌───────────────────────────┐   │
│  │ GetBooksUseCase │  │ ServerLoginUseCase│  │ ServerLogoutUseCase       │   │
│  └────────┬────────┘  └────────┬─────────┘  └───────────────────────────┘   │
│           │                    │                                             │
│  ┌────────▼────────────────────▼─────────────────────────────────────────┐  │
│  │              AuthenticatedRepositoryProvider                           │  │
│  └────────────────────────────┬──────────────────────────────────────────┘  │
└───────────────────────────────┼─────────────────────────────────────────────┘
                                │
┌───────────────────────────────┼─────────────────────────────────────────────┐
│                               │     Data Layer                               │
│  ┌────────────────────────────▼──────────────────────────────────────────┐  │
│  │                        ServerRegistry                                  │  │
│  │  - servers: Map<ServerId, ServerConfig>                                │  │
│  │  - credentials: Map<ServerId, ServerCredentials>                       │  │
│  │  - activeServerId: String?                                             │  │
│  └────────────────────────────┬──────────────────────────────────────────┘  │
│                               │                                              │
│  ┌────────────────────────────▼──────────────────────────────────────────┐  │
│  │                  ServerBooksRepositoryFactory                          │  │
│  └───────┬─────────────────────┬─────────────────────┬───────────────────┘  │
│          │                     │                     │                       │
│  ┌───────▼───────┐    ┌────────▼────────┐   ┌───────▼────────┐              │
│  │  Storyteller  │    │  Audiobookshelf │   │   Calibre-Web  │              │
│  │  Repository   │    │   Repository    │   │   Repository   │              │
│  └───────┬───────┘    └────────┬────────┘   └───────┬────────┘              │
│          │                     │                     │                       │
│  ┌───────▼───────┐    ┌────────▼────────┐   ┌───────▼────────┐              │
│  │  Storyteller  │    │  Audiobookshelf │   │   Calibre-Web  │              │
│  │  API Models   │    │   API Models    │   │   API Models   │              │
│  └───────────────┘    └─────────────────┘   └────────────────┘              │
└─────────────────────────────────────────────────────────────────────────────┘
```
