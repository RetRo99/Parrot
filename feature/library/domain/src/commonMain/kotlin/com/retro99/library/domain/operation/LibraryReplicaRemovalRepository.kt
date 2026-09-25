package com.retro99.library.domain.operation

import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryProfileId

sealed interface LibraryReplicaRemovalResult {
    val operationId: String

    data class Completed(override val operationId: String) : LibraryReplicaRemovalResult

    /** The durable intent remains pending so the caller can retry or clear its blocking operation. */
    data class Blocked(
        override val operationId: String,
        val reason: String,
    ) : LibraryReplicaRemovalResult
}

interface LibraryReplicaRemovalRepository {
    suspend fun remove(
        groupId: LibraryGroupId,
        request: LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult

    suspend fun recoverPending(profileId: LibraryProfileId): List<LibraryReplicaRemovalResult>
}
