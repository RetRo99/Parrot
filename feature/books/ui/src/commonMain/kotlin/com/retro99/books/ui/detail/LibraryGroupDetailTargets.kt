package com.retro99.books.ui.detail

import com.retro99.books.domain.DeviceStorageAvailability
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.library.domain.projection.readerTargetFor
import com.retro99.reader.domain.model.DownloadState
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId

internal data class LibraryGroupDefaultMediaTarget(
    val member: LibraryGroupMember,
    val resource: SourceMediaResource,
    val readerTarget: LibraryReaderTarget?,
    val downloadTarget: LibraryGroupDownloadTarget?,
    val cachedMediaTarget: LibraryGroupCachedMediaTarget?,
    val isPreferred: Boolean,
)

/** Reader-cache metadata is usable independently of the source's current network presence. */
data class LibraryGroupCachedMediaTarget(
    val source: SourceBookRef,
    val resource: SourceMediaResource,
    val bookType: BookType,
    val title: String,
    val cacheBookId: String,
) {
    val resourceReference: SourceResourceRef
        get() = resource.reference

    val mediaType: String
        get() = resource.mediaType.lowercase()

    val remoteFilePath: String
        get() = requireNotNull(resource.remoteResourceReference).value

    fun readerTarget(localPath: String): LibraryReaderTarget {
        val connectionId = requireNotNull(source.connectionId).value
        val nativeBookId = source.key.nativeBookId.value
        return LibraryReaderTarget(
            connectionId = connectionId,
            nativeBookId = nativeBookId,
            resource = resource.reference,
            mediaType = mediaType,
            title = title,
            storage = DeviceStorageRef(localPath),
            progressOwner = ProgressOwnerRef(
                adapterId = source.key.adapterId,
                source = source,
                nativeProgressId = nativeBookId,
            ),
        )
    }

    fun matchesCurrentMember(member: LibraryGroupMember): Boolean {
        if (member.snapshot.source != source) return false
        if (member.snapshot.status.presence == SourcePresence.Removed) return false
        if (member.snapshot.status.presence != SourcePresence.Present &&
            member.snapshot.status.presence != SourcePresence.Unknown
        ) {
            return false
        }
        val currentResource = member.snapshot.resources.firstOrNull { candidate ->
            candidate.reference == resource.reference
        } ?: return false
        return currentResource == resource
    }

    fun matchesCacheIdentity(candidate: LibraryGroupCachedMediaTarget): Boolean =
        source == candidate.source &&
            resource.reference == candidate.resource.reference &&
            resource.remoteResourceReference == candidate.resource.remoteResourceReference &&
            bookType == candidate.bookType &&
            cacheBookId == candidate.cacheBookId
}

internal fun LibraryGroupCachedMediaTarget.isOpenableFrom(
    member: LibraryGroupMember,
    downloadState: DownloadState?,
): Boolean = downloadState == DownloadState.Cached && matchesCurrentMember(member)

internal fun LibraryBookGroup.remoteReplicaDeletionTargets():
    List<LibraryGroupRemoteReplicaDeletionTarget> =
    members.flatMap { member ->
        member.snapshot.resources.mapNotNull { resource ->
            member.remoteReplicaDeletionTargetFor(groupId, resource)
        }
    }

internal fun LibraryBookGroup.remoteBookRemovalTargets():
    List<LibraryGroupRemoteBookRemovalTarget> = members.mapNotNull { member ->
        if (member.snapshot.status.presence != SourcePresence.Present ||
            member.snapshot.source.connectionId == null
        ) {
            return@mapNotNull null
        }
        LibraryGroupRemoteBookRemovalTarget(
            groupId = groupId,
            sourceKey = member.sourceKey,
            source = member.snapshot.source,
            sourceRevision = member.snapshot.status.revision,
            title = member.snapshot.metadata.title,
            observedResources = member.snapshot.resources,
        )
    }

internal fun LibraryGroupMember.remoteReplicaDeletionTargetFor(
    groupId: LibraryGroupId,
    resource: SourceMediaResource,
): LibraryGroupRemoteReplicaDeletionTarget? {
    if (resource !in snapshot.resources || resource.reference.book != sourceKey) return null
    if (snapshot.status.presence != SourcePresence.Present ||
        resource.availability != SourceResourceAvailability.AvailableRemotely ||
        snapshot.source.connectionId == null
    ) {
        return null
    }
    val remoteReference = resource.remoteResourceReference ?: return null
    val target = LibraryOperationTarget.RemoteReplica(
        source = snapshot.source,
        resource = resource.reference,
        replicaId = StorageReplicaId(remoteReference.value),
        remoteRef = remoteReference,
        mediaType = resource.mediaType,
    )
    return LibraryGroupRemoteReplicaDeletionTarget(
        groupId = groupId,
        sourceKey = sourceKey,
        resource = resource.reference,
        target = target,
        mediaType = resource.mediaType,
        title = snapshot.metadata.title,
        assetId = resource.reference.toMediaAssetId(),
    )
}

/** Select one usable read/listen target per media type, falling back in stable projection order. */
internal fun LibraryBookGroup.defaultMediaTargets(
    availableDownloadTargets: List<LibraryGroupDownloadTarget>,
    cachedMediaTargets: List<LibraryGroupCachedMediaTarget> = emptyList(),
    downloadStates: Map<SourceResourceRef, DownloadState> = emptyMap(),
): Map<String, LibraryGroupDefaultMediaTarget> {
    val readerCacheTargets = availableDownloadTargets
        .filter { target -> target.isReaderCacheDownload() }
        .associateBy { target -> target.target.resource }
    val cacheTargetsByResource = cachedMediaTargets.associateBy { target ->
        target.resourceReference
    }
    val candidates = members.flatMap { member ->
        member.snapshot.resources.sortedWith(
            compareBy<SourceMediaResource> { resource -> resource.reference.nativeResourceId }
                .thenBy { resource -> resource.reference.revision.orEmpty() },
        ).mapNotNull { resource ->
            val readerTarget = member.readerTargetFor(resource)
            val downloadTarget = readerCacheTargets[resource.reference]
            val cachedMediaTarget = cacheTargetsByResource[resource.reference]
                ?.takeIf { target -> target.source == member.snapshot.source }
            val hasCachedFile = cachedMediaTarget?.let { target ->
                downloadStates[target.resourceReference] == DownloadState.Cached
            } == true
            if (readerTarget == null && downloadTarget == null && !hasCachedFile) {
                null
            } else {
                val mediaType = resource.mediaType.lowercase()
                LibraryGroupDefaultMediaTarget(
                    member = member,
                    resource = resource,
                    readerTarget = readerTarget,
                    downloadTarget = downloadTarget,
                    cachedMediaTarget = cachedMediaTarget,
                    isPreferred = preferredMediaSourceKeys[mediaType] == member.sourceKey,
                )
            }
        }
    }
    return candidates.groupBy { candidate -> candidate.resource.mediaType.lowercase() }
        .mapValues { (_, mediaCandidates) ->
            mediaCandidates.firstOrNull { candidate ->
                candidate.isPreferred && candidate.readerTarget != null
            } ?: mediaCandidates.firstOrNull { candidate -> candidate.isPreferred }
                ?: mediaCandidates.firstOrNull { candidate -> candidate.readerTarget != null }
                ?: mediaCandidates.first()
        }
}

/** Discover cache identities without checking remote operation availability. */
internal fun LibraryBookGroup.readerCacheTargets(
    adapterRegistry: LibraryOperationAdapterRegistry,
): List<LibraryGroupCachedMediaTarget> =
    members.flatMap { member ->
        if (member.snapshot.status.presence != SourcePresence.Present &&
            member.snapshot.status.presence != SourcePresence.Unknown
        ) {
            return@flatMap emptyList()
        }
        val adapter = adapterRegistry.adapter(member.sourceKey.adapterId)
            ?: return@flatMap emptyList()
        val resources = adapter.downloadOperationResources(member.snapshot.resources)
        resources.mapNotNull { resource ->
            member.cachedMediaTargetFor(resource, adapter)
        }
    }

internal fun LibraryGroupMember.cachedMediaTargetFor(
    resource: SourceMediaResource,
    adapter: LibraryOperationAdapter,
): LibraryGroupCachedMediaTarget? {
    if (snapshot.status.presence != SourcePresence.Present &&
        snapshot.status.presence != SourcePresence.Unknown
    ) {
        return null
    }
    if (resource !in snapshot.resources || resource.reference.book != sourceKey) return null
    if (resource.availability != SourceResourceAvailability.AvailableRemotely) return null
    if (snapshot.source.connectionId == null) return null
    val remoteReference = resource.remoteResourceReference ?: return null
    val bookType = BookType.entries.firstOrNull { type ->
        type.value == resource.mediaType.lowercase()
    } ?: return null
    val target = LibraryOperationTarget.RemoteReplica(
        source = snapshot.source,
        resource = resource.reference,
        replicaId = StorageReplicaId(remoteReference.value),
        remoteRef = remoteReference,
        mediaType = resource.mediaType,
    )
    if (!adapter.supportsReaderCache(target)) return null
    val cacheBookId = adapter.readerCacheBookId(target)
        ?: generatedLibraryReaderCacheId(snapshot.source, resource.reference, bookType)
    return LibraryGroupCachedMediaTarget(
        source = snapshot.source,
        resource = resource,
        bookType = bookType,
        title = snapshot.metadata.title,
        cacheBookId = cacheBookId,
    )
}

internal fun LibraryBookGroup.downloadTargets(
    adapterRegistry: LibraryOperationAdapterRegistry,
): List<LibraryGroupDownloadTarget> =
    members.flatMap { member ->
        val downloadResources = adapterRegistry.adapter(member.sourceKey.adapterId)
            ?.downloadOperationResources(member.snapshot.resources)
            ?: member.snapshot.resources
        downloadResources.mapNotNull { resource ->
            member.downloadTargetFor(resource)
        }
    }

internal fun LibraryGroupMember.downloadTargetFor(
    resource: SourceMediaResource,
): LibraryGroupDownloadTarget? {
    if (resource !in snapshot.resources || resource.reference.book != sourceKey) return null
    if (snapshot.status.presence != SourcePresence.Present ||
        resource.availability != SourceResourceAvailability.AvailableRemotely ||
        snapshot.source.connectionId == null
    ) {
        return null
    }
    val remoteReference = resource.remoteResourceReference ?: return null
    val bookType = BookType.entries.firstOrNull { type ->
        type.value == resource.mediaType.lowercase()
    } ?: return null
    val target = LibraryOperationTarget.RemoteReplica(
        source = snapshot.source,
        resource = resource.reference,
        replicaId = StorageReplicaId(remoteReference.value),
        remoteRef = remoteReference,
        mediaType = resource.mediaType,
    )
    return LibraryGroupDownloadTarget(
        assetId = resource.reference.toMediaAssetId(),
        target = target,
        bookType = bookType,
        mediaType = resource.mediaType.lowercase(),
        title = snapshot.metadata.title,
    )
}

internal fun LibraryGroupDownloadTarget.readerTarget(localPath: String): LibraryReaderTarget {
    val source = target.source
    return LibraryReaderTarget(
        connectionId = requireNotNull(source.connectionId).value,
        nativeBookId = source.key.nativeBookId.value,
        resource = target.resource,
        mediaType = mediaType,
        title = title,
        storage = DeviceStorageRef(localPath),
        progressOwner = ProgressOwnerRef(
            adapterId = source.key.adapterId,
            source = source,
            nativeProgressId = source.key.nativeBookId.value,
        ),
    )
}

internal suspend fun LibraryReaderTarget.withAvailableDeviceStorage(
    deviceStorageAvailability: DeviceStorageAvailability,
): LibraryReaderTarget? = takeIf { target ->
    deviceStorageAvailability.exists(target.storage)
}

internal fun LibraryGroupDownloadTarget.withReaderCacheSupport(
    adapter: LibraryOperationAdapter,
): LibraryGroupDownloadTarget {
    val supportsReaderCache = adapter.supportsReaderCache(target)
    return copy(
        supportsReaderCache = supportsReaderCache,
        readerCacheBookId = adapter.readerCacheBookId(target).takeIf { supportsReaderCache },
    )
}

internal fun LibraryGroupDownloadTarget.isReaderCacheDownload(): Boolean = supportsReaderCache

internal fun LibraryGroupDownloadTarget.toDownloadRequest(
    operationId: String,
): LibraryOperationRequest = LibraryOperationRequest(
    operationId = operationId,
    operation = LibraryOperation.Download,
    assetId = assetId,
    target = target,
    downloadCacheId = takeIf { supportsReaderCache }?.downloadCacheId,
    displayTitle = title,
)

internal fun LibraryGroupDownloadTarget.matchesProjectionTarget(
    candidate: LibraryGroupDownloadTarget,
): Boolean = assetId == candidate.assetId &&
    target == candidate.target &&
    bookType == candidate.bookType &&
    mediaType == candidate.mediaType &&
    title == candidate.title

internal val LibraryGroupDownloadTarget.downloadCacheId: String
    get() = readerCacheBookId ?: generatedDownloadCacheId()

private fun LibraryGroupDownloadTarget.generatedDownloadCacheId(): String {
    return generatedLibraryReaderCacheId(target.source, target.resource, bookType)
}

private fun generatedLibraryReaderCacheId(
    source: SourceBookRef,
    resource: SourceResourceRef,
    bookType: BookType,
): String {
    val identity = when (val accountIdentity = source.key.accountIdentity) {
        is SourceAccountIdentity.Portable -> listOf(
            "portable",
            accountIdentity.backendId,
            accountIdentity.accountId,
        )
        is SourceAccountIdentity.Unresolved -> listOf(
            "unresolved",
            accountIdentity.connectionId.value,
        )
    }
    val parts = listOf(
        source.key.profileId.value,
        source.key.adapterId.value,
        source.connectionId?.value.orEmpty(),
    ) + identity + listOf(
        source.key.nativeBookId.value,
        resource.nativeResourceId,
        resource.revision.orEmpty(),
        bookType.value,
    )
    val canonical = parts.joinToString(separator = "") { part ->
        "${part.length}:$part"
    }
    var hash = -3750763034362895579L
    canonical.encodeToByteArray().forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xffL)) * 1099511628211L
    }
    return "library-${hash.toString(36)}"
}

internal fun LibraryBookGroup.deviceReplicaRemovalTargets():
    List<LibraryGroupDeviceReplicaRemovalTarget> =
    members.flatMap { member ->
        member.snapshot.resources.mapNotNull { resource ->
            member.deviceReplicaRemovalTargetFor(groupId, resource)
        }
    }

internal fun LibraryGroupMember.deviceReplicaRemovalTargetFor(
    groupId: LibraryGroupId,
    resource: SourceMediaResource,
): LibraryGroupDeviceReplicaRemovalTarget? {
    if (resource !in snapshot.resources || resource.reference.book != sourceKey) return null
    if (snapshot.status.presence != SourcePresence.Present ||
        resource.availability != SourceResourceAvailability.DevicePresent ||
        snapshot.source.connectionId == null
    ) {
        return null
    }
    val storage = resource.localStorageReference ?: return null
    val replica = LibraryOperationTarget.DeviceReplica(
        source = snapshot.source,
        resource = resource.reference,
        replicaId = StorageReplicaId(storage.value),
        storageRef = storage,
    )
    return LibraryGroupDeviceReplicaRemovalTarget(
        groupId = groupId,
        sourceKey = sourceKey,
        resource = resource.reference,
        replica = replica,
        mediaType = resource.mediaType,
        title = snapshot.metadata.title,
    )
}

internal fun SourceResourceRef.toMediaAssetId(): MediaAssetId {
    val accountIdentity = when (val identity = book.accountIdentity) {
        is SourceAccountIdentity.Portable -> listOf(
            "portable",
            identity.backendId,
            identity.accountId,
        )
        is SourceAccountIdentity.Unresolved -> listOf(
            "unresolved",
            identity.connectionId.value,
        )
    }
    val identityParts = listOf(book.profileId.value, book.adapterId.value) + accountIdentity +
        listOf(book.nativeBookId.value, nativeResourceId, revision.orEmpty())
    val stableIdentity = identityParts.joinToString(separator = "") { part ->
        "${part.length}:$part"
    }
    return MediaAssetId(stableIdentity)
}

internal fun List<OperationAvailability>.hasAvailableDownload(): Boolean =
    any { availability ->
        availability.operation == LibraryOperation.Download && availability.isAvailable
    }

internal fun List<OperationAvailability>.hasAvailableDeviceReplicaRemoval(): Boolean =
    any { availability ->
        availability.operation == LibraryOperation.RemoveDeviceReplica && availability.isAvailable
    }

internal fun List<OperationAvailability>.hasAvailableRemoteReplicaDeletion(): Boolean =
    any { availability ->
        availability.operation == LibraryOperation.DeleteRemoteReplica && availability.isAvailable
    }
