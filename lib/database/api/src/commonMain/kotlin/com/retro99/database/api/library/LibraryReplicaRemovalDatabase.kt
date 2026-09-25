package com.retro99.database.api.library

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId

enum class LibraryReplicaRemovalPhase {
    Requested,
    EffectApplied,
    Completed,
}

/** Durable, exact-target record that makes interrupted local removal recoverable. */
data class LibraryReplicaRemovalIntent(
    val operationId: String,
    val groupId: LibraryGroupId,
    val source: SourceBookRef,
    val resource: SourceResourceRef,
    val assetId: MediaAssetId,
    val replicaId: StorageReplicaId,
    val storageRef: DeviceStorageRef,
    val requestedAt: String,
    val phase: LibraryReplicaRemovalPhase = LibraryReplicaRemovalPhase.Requested,
) {
    init {
        require(operationId.isNotBlank())
        require(resource.book == source.key)
        require(source.connectionId != null) {
            "A pending removal requires a local source connection"
        }
        require(requestedAt.isNotBlank())
    }

    val profileId: LibraryProfileId
        get() = source.key.profileId
}

interface LibraryReplicaRemovalDatabase {
    /** Insert once per active replica target; a duplicate returns its existing intent. */
    suspend fun beginRemoval(intent: LibraryReplicaRemovalIntent): LibraryReplicaRemovalIntent

    suspend fun getRemoval(
        profileId: LibraryProfileId,
        operationId: String,
    ): LibraryReplicaRemovalIntent?

    suspend fun getPendingRemovals(profileId: LibraryProfileId): List<LibraryReplicaRemovalIntent>

    /** Mark that the source association has been detached and any required byte effect applied. */
    suspend fun markRemovalEffectApplied(profileId: LibraryProfileId, operationId: String)

    suspend fun completeRemoval(profileId: LibraryProfileId, operationId: String)
}
