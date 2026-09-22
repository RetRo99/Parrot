package com.retro99.database.api.sync

import com.retro99.database.api.DataClearable

/**
 * Durable remote checkpoint storage scoped to the current local profile
 * database. Cursors are opaque to the database and to the shared sync engine.
 */
interface SyncCheckpointDatabase : DataClearable {
    suspend fun getCheckpoint(
        destinationId: String,
        remoteAccountId: String,
    ): SyncCheckpoint?

    suspend fun saveCheckpoint(checkpoint: SyncCheckpoint)
}

data class SyncCheckpoint(
    val destinationId: String,
    val remoteAccountId: String,
    val cursor: String?,
    val updatedAt: String,
    val status: String? = null,
    val pendingMutationCount: Int = 0,
    val lastSuccessfulAt: String? = null,
    val lastError: String? = null,
)
