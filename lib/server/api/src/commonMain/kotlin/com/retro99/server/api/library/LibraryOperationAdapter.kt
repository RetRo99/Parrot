package com.retro99.server.api.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class MediaAssetId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class StorageReplicaId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class LibraryOperation {
    Read,
    Listen,
    Download,
    Upload,
    RemoveDeviceReplica,
    DeleteRemoteReplica,
}

data class OperationAvailability(
    val operation: LibraryOperation,
    val isAvailable: Boolean,
    val reason: String? = null,
) {
    init {
        require(isAvailable || !reason.isNullOrBlank()) {
            "An unavailable operation must explain why"
        }
    }
}

/** Exact target selected by library policy; never infer it again during execution. */
sealed interface LibraryOperationTarget {
    val source: SourceBookRef
    val resource: SourceResourceRef

    /** One source's association with a locally stored device resource. */
    data class DeviceReplica(
        override val source: SourceBookRef,
        override val resource: SourceResourceRef,
        val replicaId: StorageReplicaId,
        val storageRef: DeviceStorageRef,
        /** Delete the file after detaching this association; false preserves shared bytes. */
        val deleteStorageBytes: Boolean = true,
    ) : LibraryOperationTarget

    data class RemoteReplica(
        override val source: SourceBookRef,
        override val resource: SourceResourceRef,
        val replicaId: StorageReplicaId,
        val remoteRef: RemoteResourceRef,
        /** Media type from the selected resource, when the caller has it. */
        val mediaType: String? = null,
    ) : LibraryOperationTarget {
        init {
            require(mediaType == null || mediaType.isNotBlank())
        }
    }

    /** Destination account is explicit even before a remote resource exists. */
    data class UploadDestination(
        override val source: SourceBookRef,
        override val resource: SourceResourceRef,
        val assetId: MediaAssetId,
        val sourceReplicaId: StorageReplicaId,
        val sourceStorageRef: DeviceStorageRef,
        val destinationAdapterId: LibraryAdapterId,
        val destinationAccount: SourceAccountIdentity.Portable,
        val destinationConnectionId: SourceConnectionId,
    ) : LibraryOperationTarget {
        init {
            require(resource.book == source.key)
            require(destinationAdapterId.value.isNotBlank())
        }
    }
}

data class LibraryOperationRequest(
    val operationId: String,
    val operation: LibraryOperation,
    val assetId: MediaAssetId,
    val target: LibraryOperationTarget,
    /** True only after the user confirms any adapter-specific operation terms. */
    val userConfirmed: Boolean = false,
    /** Optional reader-cache namespace for native downloads from grouped sources. */
    val downloadCacheId: String? = null,
    /** Optional source title for transfer notifications. */
    val displayTitle: String? = null,
) {
    init {
        require(operationId.isNotBlank())
        require(target.resource.book == target.source.key)
        require(target.source.connectionId != null) {
            "An executable operation target requires a local source connection"
        }
        require(downloadCacheId == null || downloadCacheId.isNotBlank())
        require(displayTitle == null || displayTitle.isNotBlank())
        if (operation == LibraryOperation.Upload &&
            target is LibraryOperationTarget.UploadDestination
        ) {
            require(assetId == target.assetId) {
                "The upload request must retain the selected asset"
            }
        }
    }
}

/** Exact device copy and asset for which upload adapters may offer destinations. */
data class LibraryUploadSource(
    val assetId: MediaAssetId,
    val sourceReplica: LibraryOperationTarget.DeviceReplica,
    val format: String,
) {
    init { require(format.isNotBlank()) }
}

/** An adapter proposal remains bound to the selected asset and concrete device replica. */
data class LibraryUploadDestinationCandidate(
    val proposedBy: LibraryAdapterId,
    val source: LibraryUploadSource,
    val target: LibraryOperationTarget.UploadDestination,
    val acceptedFormat: String,
) {
    init {
        require(proposedBy == target.destinationAdapterId) {
            "The proposing adapter must own the destination"
        }
        require(source.sourceReplica.source == target.source) {
            "The upload destination must retain the source book reference"
        }
        require(source.sourceReplica.resource == target.resource) {
            "The upload destination must retain the exact source resource"
        }
        require(source.assetId == target.assetId) {
            "The upload destination must retain the exact asset"
        }
        require(source.sourceReplica.replicaId == target.sourceReplicaId) {
            "The upload destination must retain the exact source replica ID"
        }
        require(source.sourceReplica.storageRef == target.sourceStorageRef) {
            "The upload destination must retain the exact source storage reference"
        }
        require(acceptedFormat.isNotBlank())
    }
}

/** Adapter proposal together with its backend-specific Upload capability result. */
data class LibraryUploadDestinationProposal(
    val candidate: LibraryUploadDestinationCandidate,
    val availability: List<OperationAvailability>,
)

sealed interface LibraryOperationResult {
    data class Accepted(val operationId: String) : LibraryOperationResult
    data class Rejected(val reason: String) : LibraryOperationResult {
        init { require(reason.isNotBlank()) }
    }
}

/** Backend-specific policy and protocol translation stay in this adapter. */
interface LibraryOperationAdapter {
    val adapterId: LibraryAdapterId

    /**
     * Resources that each represent one user-visible download operation.
     *
     * The default is a resource-level download. Adapters that download a bundle of resources may
     * return a representative resource for that bundle; operation availability and execution
     * must still validate the selected resource against the current source state.
     */
    fun downloadOperationResources(
        resources: List<SourceMediaResource>,
    ): List<SourceMediaResource> = resources

    /** Whether a remote download for this target is stored in the shared reader cache. */
    fun supportsReaderCache(target: LibraryOperationTarget.RemoteReplica): Boolean = false

    /** Existing cache key to reuse when the source already has a native reader download. */
    fun readerCacheBookId(target: LibraryOperationTarget.RemoteReplica): String? = null

    suspend fun proposeUploadDestinations(
        source: LibraryUploadSource,
    ): List<LibraryUploadDestinationCandidate> = emptyList()

    /** Transfers are exposed through adapter-owned native IDs and backend-neutral state. */
    fun observeTransfers(source: SourceBookRef): Flow<List<LibraryTransferProgress>> =
        flowOf(emptyList())

    suspend fun cancelTransfer(transferId: LibraryTransferId) {
        error("This adapter cannot cancel transfers")
    }

    suspend fun retryTransfer(transferId: LibraryTransferId) {
        error("This adapter cannot retry transfers")
    }

    suspend fun availability(target: LibraryOperationTarget): List<OperationAvailability>

    /** Book-level operations are separate from operations on an individual media resource. */
    suspend fun bookAvailability(
        source: SourceBookRef,
        operation: LibraryBookOperation,
    ): LibraryBookOperationAvailability = LibraryBookOperationAvailability(
        operation = operation,
        isAvailable = false,
        reason = "This source does not support the requested book operation",
    )

    /** Executes a confirmed source-book operation without inventing a media resource target. */
    suspend fun executeBookOperation(
        request: LibraryBookOperationRequest,
    ): LibraryOperationResult = LibraryOperationResult.Rejected(
        "This source does not support the requested book operation",
    )

    /**
     * RemoveDeviceReplica detaches its selected source association; its target controls byte
     * deletion.
     */
    suspend fun execute(request: LibraryOperationRequest): LibraryOperationResult
}

interface LibraryOperationAdapterRegistry {
    fun adapter(adapterId: LibraryAdapterId): LibraryOperationAdapter?

    fun adapters(): List<LibraryOperationAdapter>
}

enum class LibraryTransferStatus {
    Queued,
    Transferring,
    Verifying,
    Finalizing,
    Completed,
    Failed,
    Cancelled,
    Unknown,
}

/** Adapter-neutral transfer row; controls always use [transferId] exactly. */
data class LibraryTransferProgress(
    val adapterId: LibraryAdapterId,
    val source: SourceBookRef,
    val transferId: LibraryTransferId,
    val operation: LibraryOperation,
    val status: LibraryTransferStatus,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val attemptCount: Int,
    val canCancel: Boolean,
    val canRetry: Boolean,
) {
    init {
        require(bytesTransferred >= 0)
        require(totalBytes >= 0)
        require(attemptCount >= 0)
    }
}
