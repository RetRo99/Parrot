package com.retro99.sync.domain

/**
 * Backend-neutral contract for non-progress sync mutations that still use the
 * legacy library-registration path during the migration.
 */
interface LegacySyncTransport {
    suspend fun push(
        mutations: List<SyncMutationRequest>,
        cursor: String?,
    ): List<SyncMutationResponse>

    suspend fun pull(
        cursor: String?,
        limit: Int,
    ): SyncChangePage
}

data class SyncMutationRequest(
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val baseRevision: Long?,
    val createdAt: String,
)

data class SyncMutationResponse(
    val mutationId: String,
    val status: String,
    val cloudBookId: String?,
    val revision: Long?,
    val payload: String?,
    val reason: String?,
)

data class SyncChangePage(
    val changes: List<SyncChange>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class SyncChange(
    val entityType: String,
    val payload: String,
    val revision: Long,
)
