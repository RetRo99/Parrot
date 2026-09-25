package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.library.domain.projection.readerTargetFor
import com.retro99.library.domain.operation.ResolvedLibraryUploadTarget
import com.retro99.library.domain.operation.UnavailableLibraryUploadTarget
import com.retro99.reader.domain.model.DownloadState
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.translations.StringRes
import resources.translations.books_detail_default_media
import resources.translations.books_detail_delete_remote_replica
import resources.translations.books_detail_open_resource
import resources.translations.books_detail_preferred_media
import resources.translations.books_detail_set_preferred_media
import resources.translations.books_detail_remove_device_replica
import resources.translations.books_detail_remove_remote_book
import resources.translations.books_detail_remote_book_removal_queued
import resources.translations.books_detail_resource_device
import resources.translations.books_detail_resource_pending
import resources.translations.books_detail_resource_remote
import resources.translations.books_detail_resource_unavailable
import resources.translations.books_detail_resource_unknown
import resources.translations.books_detail_sources
import resources.translations.books_detail_source_present
import resources.translations.books_detail_source_removed
import resources.translations.books_detail_source_stale
import resources.translations.books_detail_upload_checking
import resources.translations.books_detail_upload_unavailable
import resources.translations.books_reading_progress
import resources.translations.cloud_backup_button
import resources.translations.books_media_download
import resources.translations.books_media_downloading
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryGroupLocationsSection(
    group: LibraryBookGroup,
    onNavigateToReader: ((target: LibraryReaderTarget, bookType: BookType) -> Unit)? = null,
    showReaderActions: Boolean = true,
    isSavingPreferredMediaSource: Boolean = false,
    onSetPreferredMediaSource: ((SourceBookKey, String) -> Unit)? = null,
    isManagingMembers: Boolean = false,
    selectedSourceKeys: Set<SourceBookKey> = emptySet(),
    onMemberSelectionChanged: (SourceBookKey, Boolean) -> Unit = { _, _ -> },
    allowDeviceReplicaRemoval: Boolean = false,
    availableDeviceReplicaRemovalTargets: Set<LibraryGroupDeviceReplicaRemovalTarget> = emptySet(),
    onRemoveDeviceReplica: ((SourceBookKey, SourceResourceRef) -> Unit)? = null,
    allowRemoteReplicaDeletion: Boolean = false,
    availableRemoteReplicaDeletionTargets:
        Set<LibraryGroupRemoteReplicaDeletionTarget> = emptySet(),
    onDeleteRemoteReplica: ((SourceBookKey, SourceResourceRef) -> Unit)? = null,
    allowRemoteBookRemoval: Boolean = false,
    availableRemoteBookRemovalTargets:
        Set<LibraryGroupRemoteBookRemovalTarget> = emptySet(),
    queuedRemoteBookRemovalSourceKeys: Set<SourceBookKey> = emptySet(),
    onRemoveRemoteBook: ((SourceBookKey) -> Unit)? = null,
    availableUploadTargets: List<ResolvedLibraryUploadTarget> = emptyList(),
    unavailableUploadTargets: List<UnavailableLibraryUploadTarget> = emptyList(),
    isResolvingUploadTargets: Boolean = false,
    availableDownloadTargets: List<LibraryGroupDownloadTarget> = emptyList(),
    readerCacheTargets: List<LibraryGroupCachedMediaTarget> = emptyList(),
    readingProgressBySourceAndMediaType: Map<SourceBookKey, Map<String, Double>> = emptyMap(),
    downloadStates: Map<SourceResourceRef, DownloadState> = emptyMap(),
    isPreparingReader: Boolean = false,
    onOpenDownloadedResource: ((LibraryGroupCachedMediaTarget) -> Unit)? = null,
    allowDownload: Boolean = false,
    onDownload: ((LibraryGroupDownloadTarget) -> Unit)? = null,
    allowUpload: Boolean = false,
    onUpload: ((ResolvedLibraryUploadTarget) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(StringRes.books_detail_sources),
            style = MaterialTheme.typography.titleMedium,
        )
        val defaultMediaTargets = if (showReaderActions && !isManagingMembers) {
            group.defaultMediaTargets(
                availableDownloadTargets = availableDownloadTargets,
                cachedMediaTargets = readerCacheTargets,
                downloadStates = downloadStates,
            )
        } else {
            emptyMap()
        }
        if (defaultMediaTargets.isNotEmpty()) {
            LibraryGroupDefaultMediaActions(
                targets = defaultMediaTargets.values.toList(),
                downloadStates = downloadStates,
                isPreparingReader = isPreparingReader,
                allowDownload = allowDownload,
                onNavigateToReader = onNavigateToReader,
                onOpenDownloadedResource = onOpenDownloadedResource,
                onDownload = onDownload,
            )
        }
        if (isResolvingUploadTargets) {
            Text(stringResource(StringRes.books_detail_upload_checking))
        }
        group.members.forEach { member ->
            LibraryGroupMemberCard(
                member = member,
                onNavigateToReader = onNavigateToReader,
                showReaderActions = showReaderActions && !isManagingMembers,
                isManagingMembers = isManagingMembers,
                isSelected = member.sourceKey in selectedSourceKeys,
                allowDeviceReplicaRemoval = allowDeviceReplicaRemoval,
                availableDeviceReplicaRemovalTargets =
                    availableDeviceReplicaRemovalTargets.filterTo(mutableSetOf()) { target ->
                        target.groupId == group.groupId && target.sourceKey == member.sourceKey
                    },
                allowRemoteReplicaDeletion = allowRemoteReplicaDeletion,
                availableRemoteReplicaDeletionTargets =
                    availableRemoteReplicaDeletionTargets.filterTo(mutableSetOf()) { target ->
                        target.groupId == group.groupId && target.sourceKey == member.sourceKey
                    },
                availableUploadTargets = availableUploadTargets.filter { target ->
                    target.source.sourceReplica.source.key == member.sourceKey
                },
                unavailableUploadTargets = unavailableUploadTargets.filter { target ->
                    target.source.sourceReplica.source.key == member.sourceKey
                },
                availableDownloadTargets = availableDownloadTargets.filter { target ->
                    target.target.source.key == member.sourceKey
                },
                readerCacheTargets = readerCacheTargets.filter { target ->
                    target.source.key == member.sourceKey
                },
                readingProgressBySourceAndMediaType = readingProgressBySourceAndMediaType,
                defaultMediaTargets = defaultMediaTargets,
                preferredMediaSourceKeys = group.preferredMediaSourceKeys,
                isSavingPreferredMediaSource = isSavingPreferredMediaSource,
                onSetPreferredMediaSource = onSetPreferredMediaSource?.let { callback ->
                    { mediaType -> callback(member.sourceKey, mediaType) }
                },
                downloadStates = downloadStates,
                isPreparingReader = isPreparingReader,
                onOpenDownloadedResource = onOpenDownloadedResource,
                allowDownload = allowDownload,
                allowUpload = allowUpload,
                onSelectionChanged = { selected ->
                    onMemberSelectionChanged(member.sourceKey, selected)
                },
                onRemoveDeviceReplica = { resourceReference ->
                    onRemoveDeviceReplica?.invoke(member.sourceKey, resourceReference)
                },
                onDeleteRemoteReplica = { resourceReference ->
                    onDeleteRemoteReplica?.invoke(member.sourceKey, resourceReference)
                },
                allowRemoteBookRemoval = allowRemoteBookRemoval,
                remoteBookRemovalTarget = availableRemoteBookRemovalTargets.firstOrNull { target ->
                    target.groupId == group.groupId && target.sourceKey == member.sourceKey
                },
                isRemoteBookRemovalQueued =
                    member.sourceKey in queuedRemoteBookRemovalSourceKeys,
                onRemoveRemoteBook = { onRemoveRemoteBook?.invoke(member.sourceKey) },
                onUpload = { target -> onUpload?.invoke(target) },
                onDownload = { target -> onDownload?.invoke(target) },
            )
        }
    }
}

@Composable
private fun LibraryGroupDefaultMediaActions(
    targets: List<LibraryGroupDefaultMediaTarget>,
    downloadStates: Map<SourceResourceRef, DownloadState>,
    isPreparingReader: Boolean,
    allowDownload: Boolean,
    onNavigateToReader: ((LibraryReaderTarget, BookType) -> Unit)?,
    onOpenDownloadedResource: ((LibraryGroupCachedMediaTarget) -> Unit)?,
    onDownload: ((LibraryGroupDownloadTarget) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        targets.forEach { target ->
            val bookType = BookType.entries.firstOrNull { type ->
                type.value == target.resource.mediaType.lowercase()
            } ?: return@forEach
            val mediaLabel = when (bookType) {
                BookType.EBOOK -> stringResource(StringRes.books_media_ebook)
                BookType.AUDIOBOOK -> stringResource(StringRes.books_media_audio)
                BookType.READALOUD -> stringResource(StringRes.books_media_readaloud)
            }
            val downloadTarget = target.downloadTarget
            val cachedMediaTarget = target.cachedMediaTarget
            val downloadState = cachedMediaTarget?.let { cachedTarget ->
                downloadStates[cachedTarget.resourceReference]
            }
            when {
                target.readerTarget != null && onNavigateToReader != null -> {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onNavigateToReader(target.readerTarget, bookType)
                        },
                    ) {
                        Text(stringResource(StringRes.books_detail_open_resource, mediaLabel))
                    }
                }
                cachedMediaTarget != null &&
                    cachedMediaTarget.isOpenableFrom(target.member, downloadState) &&
                    onOpenDownloadedResource != null -> {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPreparingReader,
                        onClick = { onOpenDownloadedResource(cachedMediaTarget) },
                    ) {
                        Text(stringResource(StringRes.books_detail_open_resource, mediaLabel))
                    }
                }
                cachedMediaTarget != null && downloadState is DownloadState.Downloading -> {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        onClick = {},
                    ) {
                        Text(stringResource(StringRes.books_media_downloading))
                    }
                }
                downloadTarget != null && allowDownload && onDownload != null -> {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onDownload(downloadTarget) },
                    ) {
                        Text(stringResource(StringRes.books_media_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryGroupMemberCard(
    member: LibraryGroupMember,
    onNavigateToReader: ((target: LibraryReaderTarget, bookType: BookType) -> Unit)?,
    showReaderActions: Boolean,
    isManagingMembers: Boolean,
    isSelected: Boolean,
    allowDeviceReplicaRemoval: Boolean,
    availableDeviceReplicaRemovalTargets: Set<LibraryGroupDeviceReplicaRemovalTarget>,
    allowRemoteReplicaDeletion: Boolean,
    availableRemoteReplicaDeletionTargets: Set<LibraryGroupRemoteReplicaDeletionTarget>,
    availableUploadTargets: List<ResolvedLibraryUploadTarget>,
    unavailableUploadTargets: List<UnavailableLibraryUploadTarget>,
    availableDownloadTargets: List<LibraryGroupDownloadTarget>,
    readerCacheTargets: List<LibraryGroupCachedMediaTarget>,
    readingProgressBySourceAndMediaType: Map<SourceBookKey, Map<String, Double>>,
    defaultMediaTargets: Map<String, LibraryGroupDefaultMediaTarget>,
    preferredMediaSourceKeys: Map<String, SourceBookKey>,
    isSavingPreferredMediaSource: Boolean,
    onSetPreferredMediaSource: ((String) -> Unit)?,
    downloadStates: Map<SourceResourceRef, DownloadState>,
    isPreparingReader: Boolean,
    onOpenDownloadedResource: ((LibraryGroupCachedMediaTarget) -> Unit)?,
    allowDownload: Boolean,
    allowUpload: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onRemoveDeviceReplica: (SourceResourceRef) -> Unit,
    onDeleteRemoteReplica: (SourceResourceRef) -> Unit,
    allowRemoteBookRemoval: Boolean,
    remoteBookRemovalTarget: LibraryGroupRemoteBookRemovalTarget?,
    isRemoteBookRemovalQueued: Boolean,
    onRemoveRemoteBook: () -> Unit,
    onUpload: (ResolvedLibraryUploadTarget) -> Unit,
    onDownload: (LibraryGroupDownloadTarget) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val connectionId = member.snapshot.source.connectionId?.value
            val sourceLabel = listOfNotNull(
                member.sourceKey.adapterId.value,
                connectionId,
            ).joinToString(" · ")
            val preferredResourceByMediaType = member.snapshot.resources
                .groupBy { resource -> resource.mediaType.lowercase() }
                .mapValues { (_, resources) ->
                    resources.minWithOrNull(
                        compareBy<SourceMediaResource> { resource ->
                            resource.reference.nativeResourceId
                        }.thenBy { resource -> resource.reference.revision.orEmpty() },
                    )?.reference
                }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    member.snapshot.metadata.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(sourceLabel, style = MaterialTheme.typography.bodySmall)
                Text(sourcePresenceLabel(member.snapshot.status.presence))
                member.snapshot.resources.forEach { resource ->
                    LibraryGroupResourceRow(
                        member = member,
                        resource = resource,
                        onNavigateToReader = onNavigateToReader,
                        showReaderActions = showReaderActions,
                        canRemoveDeviceReplica = allowDeviceReplicaRemoval &&
                            availableDeviceReplicaRemovalTargets.any { target ->
                                target.resource == resource.reference
                            },
                        canDeleteRemoteReplica = allowRemoteReplicaDeletion &&
                            availableRemoteReplicaDeletionTargets.any { target ->
                                target.resource == resource.reference
                            },
                        availableUploadTargets = availableUploadTargets.filter { target ->
                            target.source.sourceReplica.resource == resource.reference
                        },
                        unavailableUploadTargets = unavailableUploadTargets.filter { target ->
                            target.source.sourceReplica.resource == resource.reference
                        },
                        availableDownloadTargets = availableDownloadTargets.filter { target ->
                            target.target.resource == resource.reference
                        },
                        readerCacheTargets = readerCacheTargets.filter { target ->
                            target.resourceReference == resource.reference
                        },
                        readingProgression = readingProgressForResource(
                            progressBySourceAndMediaType = readingProgressBySourceAndMediaType,
                            sourceKey = member.sourceKey,
                            mediaType = resource.mediaType,
                        ),
                        isDefaultMediaTarget = defaultMediaTargets[resource.mediaType.lowercase()]
                            ?.let { target ->
                                target.member.sourceKey == member.sourceKey &&
                                    target.resource.reference == resource.reference
                            } == true,
                        isPreferredMediaSource =
                            preferredMediaSourceKeys[resource.mediaType.lowercase()] ==
                                member.sourceKey,
                        canChoosePreferredSource = !isManagingMembers &&
                            member.snapshot.status.presence != SourcePresence.Removed &&
                            preferredResourceByMediaType[resource.mediaType.lowercase()] ==
                                resource.reference,
                        isSavingPreferredMediaSource = isSavingPreferredMediaSource,
                        onSetPreferredMediaSource = onSetPreferredMediaSource,
                        downloadStates = downloadStates,
                        isPreparingReader = isPreparingReader,
                        onOpenDownloadedResource = onOpenDownloadedResource,
                        allowDownload = allowDownload &&
                            member.snapshot.status.presence == SourcePresence.Present,
                        allowUpload = allowUpload &&
                            member.snapshot.status.presence == SourcePresence.Present,
                        onRemoveDeviceReplica = onRemoveDeviceReplica,
                        onDeleteRemoteReplica = onDeleteRemoteReplica,
                        onUpload = onUpload,
                        onDownload = onDownload,
                    )
                }
                if (isRemoteBookRemovalQueued) {
                    Text(stringResource(StringRes.books_detail_remote_book_removal_queued))
                } else if (allowRemoteBookRemoval && remoteBookRemovalTarget != null) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRemoveRemoteBook,
                    ) {
                        Text(stringResource(StringRes.books_detail_remove_remote_book))
                    }
                }
            }
            if (isManagingMembers) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onSelectionChanged,
                )
            }
        }
    }
}

@Composable
private fun LibraryGroupResourceRow(
    member: LibraryGroupMember,
    resource: SourceMediaResource,
    onNavigateToReader: ((target: LibraryReaderTarget, bookType: BookType) -> Unit)?,
    showReaderActions: Boolean,
    canRemoveDeviceReplica: Boolean,
    canDeleteRemoteReplica: Boolean,
    availableUploadTargets: List<ResolvedLibraryUploadTarget>,
    unavailableUploadTargets: List<UnavailableLibraryUploadTarget>,
    availableDownloadTargets: List<LibraryGroupDownloadTarget>,
    readerCacheTargets: List<LibraryGroupCachedMediaTarget>,
    readingProgression: Double?,
    isDefaultMediaTarget: Boolean,
    isPreferredMediaSource: Boolean,
    canChoosePreferredSource: Boolean,
    isSavingPreferredMediaSource: Boolean,
    onSetPreferredMediaSource: ((String) -> Unit)?,
    downloadStates: Map<SourceResourceRef, DownloadState>,
    isPreparingReader: Boolean,
    onOpenDownloadedResource: ((LibraryGroupCachedMediaTarget) -> Unit)?,
    allowDownload: Boolean,
    allowUpload: Boolean,
    onRemoveDeviceReplica: (SourceResourceRef) -> Unit,
    onDeleteRemoteReplica: (SourceResourceRef) -> Unit,
    onUpload: (ResolvedLibraryUploadTarget) -> Unit,
    onDownload: (LibraryGroupDownloadTarget) -> Unit,
) {
    val bookType = BookType.entries.firstOrNull { type ->
        type.value == resource.mediaType.lowercase()
    }
    val mediaLabel = when (bookType) {
        BookType.EBOOK -> stringResource(StringRes.books_media_ebook)
        BookType.AUDIOBOOK -> stringResource(StringRes.books_media_audio)
        BookType.READALOUD -> stringResource(StringRes.books_media_readaloud)
        null -> resource.mediaType
    }
    val target = member.readerTargetFor(resource)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(mediaLabel, style = MaterialTheme.typography.bodyMedium)
            if (isPreferredMediaSource) {
                Text(
                    text = stringResource(StringRes.books_detail_preferred_media),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (isDefaultMediaTarget) {
                Text(
                    text = stringResource(StringRes.books_detail_default_media),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            readingProgression?.let { progression ->
                val progressPercent = (progression * 100).toInt()
                Text(
                    text = "${stringResource(StringRes.books_reading_progress)}: " +
                        "$progressPercent%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canChoosePreferredSource && !isPreferredMediaSource &&
                onSetPreferredMediaSource != null
            ) {
                TextButton(
                    enabled = !isSavingPreferredMediaSource,
                    onClick = { onSetPreferredMediaSource(resource.mediaType) },
                ) {
                    Text(stringResource(StringRes.books_detail_set_preferred_media))
                }
            }
            Text(resourceAvailabilityLabel(resource.availability))
            resource.format?.let { format -> Text(format) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showReaderActions && !isDefaultMediaTarget && target != null && bookType != null &&
                onNavigateToReader != null
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onNavigateToReader(target, bookType) },
                ) {
                    Text(stringResource(StringRes.books_detail_open_resource, mediaLabel))
                }
            }
            if (canRemoveDeviceReplica) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onRemoveDeviceReplica(resource.reference) },
                ) {
                    Text(stringResource(StringRes.books_detail_remove_device_replica))
                }
            }
            if (canDeleteRemoteReplica) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDeleteRemoteReplica(resource.reference) },
                ) {
                    Text(stringResource(StringRes.books_detail_delete_remote_replica))
                }
            }
            val downloadTarget = availableDownloadTargets.firstOrNull()
            val readerCacheTarget = readerCacheTargets.firstOrNull { target ->
                target.resourceReference == resource.reference
            }
            val downloadState = readerCacheTarget?.let { target ->
                downloadStates[target.resourceReference]
            }
            if (!isDefaultMediaTarget && readerCacheTarget != null &&
                readerCacheTarget.isOpenableFrom(member, downloadState)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPreparingReader,
                    onClick = { onOpenDownloadedResource?.invoke(readerCacheTarget) },
                ) {
                    Text(stringResource(StringRes.books_detail_open_resource, mediaLabel))
                }
            } else if (!isDefaultMediaTarget && readerCacheTarget != null &&
                downloadState is DownloadState.Downloading
            ) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    onClick = {},
                ) {
                    Text(stringResource(StringRes.books_media_downloading))
                }
            } else if (!isDefaultMediaTarget && allowDownload &&
                downloadTarget != null && downloadTarget.isReaderCacheDownload()
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDownload(downloadTarget) },
                ) {
                    Text(stringResource(StringRes.books_media_download))
                }
            } else if (!isDefaultMediaTarget && allowDownload) {
                availableDownloadTargets.forEach { target ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onDownload(target) },
                    ) {
                        Text(stringResource(StringRes.books_media_download))
                    }
                }
            }
            if (allowUpload) {
                availableUploadTargets.forEach { target ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onUpload(target) },
                    ) {
                        Text(stringResource(StringRes.cloud_backup_button))
                    }
                }
            }
            if (unavailableUploadTargets.isNotEmpty()) {
                val reason = unavailableUploadTargets.firstNotNullOfOrNull { target ->
                    target.detail
                }
                Text(
                    text = reason ?: stringResource(StringRes.books_detail_upload_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun sourcePresenceLabel(presence: SourcePresence): String = when (presence) {
    SourcePresence.Present -> stringResource(StringRes.books_detail_source_present)
    SourcePresence.Removed -> stringResource(StringRes.books_detail_source_removed)
    SourcePresence.Unknown -> stringResource(StringRes.books_detail_source_stale)
}

@Composable
private fun resourceAvailabilityLabel(availability: SourceResourceAvailability): String = when (
    availability
) {
    SourceResourceAvailability.DevicePresent ->
        stringResource(StringRes.books_detail_resource_device)
    SourceResourceAvailability.AvailableRemotely ->
        stringResource(StringRes.books_detail_resource_remote)
    SourceResourceAvailability.TransferPending ->
        stringResource(StringRes.books_detail_resource_pending)
    SourceResourceAvailability.Unavailable ->
        stringResource(StringRes.books_detail_resource_unavailable)
    SourceResourceAvailability.Unknown ->
        stringResource(StringRes.books_detail_resource_unknown)
}
