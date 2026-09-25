package com.retro99.database.api.library

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceResourceRef
import kotlinx.coroutines.flow.Flow

data class LibraryBackfillResult(
    val processedSnapshots: Int,
    val isComplete: Boolean,
)

enum class DeviceReplicaRetirementResult {
    Removed,
    RemovedWithSharedReferences,
    AlreadyRemoved,
    AlreadyRemovedWithSharedReferences,
    ReferenceChanged,
    NotFound,
}

enum class RemoteReplicaRetirementResult {
    Removed,
    AlreadyRemoved,
    ReferenceChanged,
    NotFound,
}

interface LibrarySourceSnapshotsDatabase {
    /** Emit when persisted source metadata or resource availability changes for the profile. */
    fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit>

    /** Persist a source snapshot and ensure it has a stable group membership. */
    suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId

    suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot?

    suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot>

    /** Clear the selected exact device location without clearing other owners of its path. */
    suspend fun retireDeviceReplica(
        source: SourceBookRef,
        resource: SourceResourceRef,
        expectedStorageRef: DeviceStorageRef,
    ): DeviceReplicaRetirementResult

    /** Clear only the exact old remote location, retaining resource history and local replicas. */
    suspend fun retireRemoteReplica(
        resource: SourceResourceRef,
        expectedRemoteRef: RemoteResourceRef,
    ): RemoteReplicaRetirementResult

    /** Resume an idempotent group backfill from already persisted source snapshots. */
    suspend fun backfillGroups(
        profileId: LibraryProfileId,
        migrationId: String,
        startedAt: String,
        completedAt: String,
    ): LibraryBackfillResult
}
