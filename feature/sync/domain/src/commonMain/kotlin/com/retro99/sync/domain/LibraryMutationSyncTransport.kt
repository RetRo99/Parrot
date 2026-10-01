package com.retro99.sync.domain

/**
 * Backend-neutral contract for non-progress library-mutation synchronization.
 */
interface LibraryMutationSyncTransport {
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
    val revision: Long?,
    val payload: String?,
    val reason: String?,
    val retryAfterMillis: Long? = null,
    /** For `duplicate`: the book the server already has with the same content. */
    val existingBookId: String? = null,
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
