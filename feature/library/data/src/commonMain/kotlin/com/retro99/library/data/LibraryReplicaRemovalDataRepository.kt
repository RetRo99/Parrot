package com.retro99.library.data

import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryReplicaRemovalDatabase
import com.retro99.database.api.library.LibraryReplicaRemovalIntent
import com.retro99.database.api.library.LibraryReplicaRemovalPhase
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.library.domain.operation.LibraryReplicaRemovalRepository
import com.retro99.library.domain.operation.LibraryReplicaRemovalResult
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceResourceRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryReplicaRemovalRepository::class])
class LibraryReplicaRemovalDataRepository(
    private val removalDatabase: LibraryReplicaRemovalDatabase,
    private val snapshotsDatabase: LibrarySourceSnapshotsDatabase,
    @Provided private val adapterRegistry: LibraryOperationAdapterRegistry,
) : LibraryReplicaRemovalRepository {
    private val removalMutex = Mutex()

    override suspend fun remove(
        groupId: LibraryGroupId,
        request: LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult {
        require(request.operation == LibraryOperation.RemoveDeviceReplica)
        require(requestedAt.isNotBlank())
        val target = request.target as? LibraryOperationTarget.DeviceReplica
            ?: error("Device replica removal requires a concrete device target")
        val intent = removalDatabase.beginRemoval(
            LibraryReplicaRemovalIntent(
                operationId = request.operationId,
                groupId = groupId,
                source = target.source,
                resource = target.resource,
                assetId = request.assetId,
                replicaId = target.replicaId,
                storageRef = target.storageRef,
                requestedAt = requestedAt,
            ),
        )
        if (intent.phase == LibraryReplicaRemovalPhase.Completed) {
            return LibraryReplicaRemovalResult.Completed(intent.operationId)
        }
        val canonicalRequest = intent.toRequest()
        return removalMutex.withLock {
            resume(intent, canonicalRequest)
        }
    }

    override suspend fun recoverPending(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalResult> = removalMutex.withLock {
        removalDatabase.getPendingRemovals(profileId).map { intent ->
            resume(intent, intent.toRequest())
        }
    }

    private suspend fun resume(
        initialIntent: LibraryReplicaRemovalIntent,
        request: LibraryOperationRequest,
    ): LibraryReplicaRemovalResult {
        val intent = removalDatabase.getRemoval(
            initialIntent.profileId,
            initialIntent.operationId,
        ) ?: initialIntent
        if (intent.phase == LibraryReplicaRemovalPhase.Completed) {
            return LibraryReplicaRemovalResult.Completed(intent.operationId)
        }
        val target = request.target as LibraryOperationTarget.DeviceReplica
        if (intent.phase == LibraryReplicaRemovalPhase.Requested) {
            val adapter = adapterRegistry.adapter(target.source.key.adapterId)
                ?: return LibraryReplicaRemovalResult.Blocked(
                    operationId = intent.operationId,
                    reason = "No operation adapter is registered for this source",
                )
            val availability = adapter.availability(target).firstOrNull { result ->
                result.operation == LibraryOperation.RemoveDeviceReplica
            } ?: return LibraryReplicaRemovalResult.Blocked(
                operationId = intent.operationId,
                reason = "This source does not support device replica removal",
            )
            if (!availability.isAvailable) {
                return LibraryReplicaRemovalResult.Blocked(
                    operationId = intent.operationId,
                    reason = requireNotNull(availability.reason),
                )
            }
        }

        val retirement = snapshotsDatabase.retireDeviceReplica(
            source = intent.source,
            resource = intent.resource,
            expectedStorageRef = intent.storageRef,
        )
        val shouldDeleteBytes = when (retirement) {
            DeviceReplicaRetirementResult.Removed,
            DeviceReplicaRetirementResult.AlreadyRemoved,
            -> true
            DeviceReplicaRetirementResult.RemovedWithSharedReferences,
            DeviceReplicaRetirementResult.AlreadyRemovedWithSharedReferences,
            -> false
            DeviceReplicaRetirementResult.ReferenceChanged -> {
                return LibraryReplicaRemovalResult.Blocked(
                    operationId = intent.operationId,
                    reason = "The selected device location has changed",
                )
            }
            DeviceReplicaRetirementResult.NotFound -> {
                return LibraryReplicaRemovalResult.Blocked(
                    operationId = intent.operationId,
                    reason = "The selected device resource is no longer available",
                )
            }
        }
        if (intent.phase == LibraryReplicaRemovalPhase.Requested) {
            val adapter = requireNotNull(adapterRegistry.adapter(target.source.key.adapterId))
            val removalRequest = request.copy(
                target = target.copy(deleteStorageBytes = shouldDeleteBytes),
            )
            when (val result = adapter.execute(removalRequest)) {
                is LibraryOperationResult.Accepted -> {
                    check(result.operationId == intent.operationId) {
                        "The adapter acknowledged a different removal operation"
                    }
                    removalDatabase.markRemovalEffectApplied(
                        intent.profileId,
                        intent.operationId,
                    )
                }
                is LibraryOperationResult.Rejected -> {
                    return LibraryReplicaRemovalResult.Blocked(
                        operationId = intent.operationId,
                        reason = result.reason,
                    )
                }
            }
        }

        removalDatabase.completeRemoval(intent.profileId, intent.operationId)
        return LibraryReplicaRemovalResult.Completed(intent.operationId)
    }

    private fun LibraryReplicaRemovalIntent.toRequest(): LibraryOperationRequest =
        LibraryOperationRequest(
            operationId = operationId,
            operation = LibraryOperation.RemoveDeviceReplica,
            assetId = assetId,
            target = LibraryOperationTarget.DeviceReplica(
                source = source,
                resource = resource,
                replicaId = replicaId,
                storageRef = storageRef,
            ),
        )
}
