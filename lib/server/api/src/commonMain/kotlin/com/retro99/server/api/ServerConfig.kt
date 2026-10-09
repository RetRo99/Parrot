package com.retro99.server.api

import com.retro99.base.server.ServerType
import kotlinx.serialization.Serializable

/**
 * Configuration for a registered server instance.
 */
@Serializable
data class ServerConfig(
    val id: String,                    // Unique identifier (UUID)
    val name: String,                  // User-defined display name
    @Serializable(with = ServerTypeSerializer::class)
    val type: ServerType,              // Type of server
    val baseUrl: String,               // Base URL (e.g., "https://books.example.com")
    val addedAt: Long,                 // Timestamp when server was added
    val lastConnectedAt: Long? = null, // Last successful connection
    val enabled: Boolean = true,
    /** Preset-supplied presentation hint. Never inferred from the address or provider name. */
    val listEntriesAreBooks: Boolean = false,
)
