package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.translations.StringRes
import resources.translations.books_detail_group_unavailable
import resources.translations.books_detail_manage_done
import resources.translations.books_detail_manage_groups
import resources.translations.books_detail_preferred_media_error
import resources.translations.books_detail_progress_separate
import resources.translations.books_detail_retry
import resources.translations.books_detail_remove_replica_confirm
import resources.translations.books_detail_remove_replica_message
import resources.translations.books_detail_remove_replica_retry
import resources.translations.books_detail_remove_replica_stale
import resources.translations.books_detail_remove_replica_title
import resources.translations.books_detail_delete_remote_replica_confirm
import resources.translations.books_detail_delete_remote_replica_message
import resources.translations.books_detail_delete_remote_replica_stale
import resources.translations.books_detail_delete_remote_replica_title
import resources.translations.books_detail_remove_remote_book_confirm
import resources.translations.books_detail_remove_remote_book_message
import resources.translations.books_detail_remove_remote_book_stale
import resources.translations.books_detail_remove_remote_book_title
import resources.translations.books_detail_series
import resources.translations.books_detail_split_action
import resources.translations.books_detail_split_keep_member
import resources.translations.books_detail_split_message
import resources.translations.books_detail_split_selection_count
import resources.translations.books_detail_split_stale
import resources.translations.books_detail_split_title
import resources.translations.books_detail_tags
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.cloud_backup_confirm
import resources.translations.cloud_backup_title
import resources.translations.books_merge_cancel
import resources.translations.general_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LibraryGroupDetailScreen(
    groupId: String,
    onNavigateToReader: (target: LibraryReaderTarget, bookType: BookType) -> Unit,
    onReplaceWithCanonicalGroup: (groupId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryGroupDetailViewModel = koinViewModel {
        parametersOf(groupId, onBack, onNavigateToReader, onReplaceWithCanonicalGroup)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            viewState.group != null -> LibraryGroupDetailContent(
                group = viewState.group,
                viewState = viewState,
                onNavigateToReader = { target, bookType ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnDeviceResourceOpenRequested(
                            target = target,
                            bookType = bookType,
                        ),
                    )
                },
                intentDispatcher = intentDispatcher,
            )
            else -> LibraryGroupUnavailableContent(
                errorMessage = viewState.errorMessage,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryGroupDetailContent(
    group: LibraryBookGroup,
    viewState: LibraryGroupDetailViewState,
    onNavigateToReader: (target: LibraryReaderTarget, bookType: BookType) -> Unit,
    intentDispatcher: IntentDispatcher<LibraryGroupDetailIntent>,
) {
    val metadata = group.displayMetadata
    if (viewState.showReplicaRemovalConfirmation) {
        val removalTarget = viewState.replicaRemovalTarget
        if (removalTarget != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!viewState.isRemovingReplica) {
                        intentDispatcher(
                            LibraryGroupDetailIntent.OnRemoveDeviceReplicaDismissed,
                        )
                    }
                },
                title = { Text(stringResource(StringRes.books_detail_remove_replica_title)) },
                text = {
                    Text(
                        stringResource(
                            StringRes.books_detail_remove_replica_message,
                            removalTarget.mediaType,
                            removalTarget.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !viewState.isRemovingReplica,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnRemoveDeviceReplicaConfirmed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_detail_remove_replica_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !viewState.isRemovingReplica,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnRemoveDeviceReplicaDismissed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                },
            )
        }
    }
    if (viewState.showRemoteReplicaDeletionConfirmation) {
        val deletionTarget = viewState.pendingRemoteReplicaDeletionTarget
        if (deletionTarget != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!viewState.isDeletingRemoteReplica) {
                        intentDispatcher(
                            LibraryGroupDetailIntent.OnDeleteRemoteReplicaDismissed,
                        )
                    }
                },
                title = {
                    Text(stringResource(StringRes.books_detail_delete_remote_replica_title))
                },
                text = {
                    Text(
                        stringResource(
                            StringRes.books_detail_delete_remote_replica_message,
                            deletionTarget.mediaType,
                            deletionTarget.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !viewState.isDeletingRemoteReplica,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnDeleteRemoteReplicaConfirmed,
                            )
                        },
                    ) {
                        Text(
                            stringResource(
                                StringRes.books_detail_delete_remote_replica_confirm,
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !viewState.isDeletingRemoteReplica,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnDeleteRemoteReplicaDismissed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                },
            )
        }
    }
    if (viewState.showRemoteBookRemovalConfirmation) {
        val removalTarget = viewState.pendingRemoteBookRemovalTarget
        if (removalTarget != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!viewState.isRemovingRemoteBook) {
                        intentDispatcher(
                            LibraryGroupDetailIntent.OnRemoveRemoteBookDismissed,
                        )
                    }
                },
                title = {
                    Text(stringResource(StringRes.books_detail_remove_remote_book_title))
                },
                text = {
                    Text(
                        stringResource(
                            StringRes.books_detail_remove_remote_book_message,
                            removalTarget.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !viewState.isRemovingRemoteBook,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnRemoveRemoteBookConfirmed,
                            )
                        },
                    ) {
                        Text(
                            stringResource(
                                StringRes.books_detail_remove_remote_book_confirm,
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !viewState.isRemovingRemoteBook,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnRemoveRemoteBookDismissed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                },
            )
        }
    }
    if (viewState.showUploadConfirmation) {
        val uploadTarget = viewState.pendingUploadTarget
        if (uploadTarget != null) {
            val uploadTitle = group.members.firstOrNull { member ->
                member.sourceKey == uploadTarget.source.sourceReplica.source.key
            }?.snapshot?.metadata?.title ?: metadata.title
            AlertDialog(
                onDismissRequest = {
                    if (!viewState.isExecutingUpload) {
                        intentDispatcher(LibraryGroupDetailIntent.OnUploadDismissed)
                    }
                },
                title = { Text(stringResource(StringRes.cloud_backup_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(uploadTitle, style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = viewState.uploadRightsAttested,
                                enabled = !viewState.isExecutingUpload,
                                onCheckedChange = { attested ->
                                    intentDispatcher(
                                        LibraryGroupDetailIntent.OnUploadRightsAttestationChanged(
                                            attested,
                                        ),
                                    )
                                },
                            )
                            Text(
                                text = stringResource(StringRes.cloud_backup_attestation_checkbox),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        viewState.uploadErrorMessage?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = viewState.uploadRightsAttested && !viewState.isExecutingUpload,
                        onClick = { intentDispatcher(LibraryGroupDetailIntent.OnUploadConfirmed) },
                    ) {
                        Text(stringResource(StringRes.cloud_backup_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !viewState.isExecutingUpload,
                        onClick = { intentDispatcher(LibraryGroupDetailIntent.OnUploadDismissed) },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                },
            )
        }
    }
    if (viewState.showSplitConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!viewState.isSplittingMembers) {
                    intentDispatcher(LibraryGroupDetailIntent.OnSplitDismissed)
                }
            },
            title = { Text(stringResource(StringRes.books_detail_split_title)) },
            text = { Text(stringResource(StringRes.books_detail_split_message)) },
            confirmButton = {
                TextButton(
                    enabled = !viewState.isSplittingMembers,
                    onClick = { intentDispatcher(LibraryGroupDetailIntent.OnSplitConfirmed) },
                ) {
                    Text(stringResource(StringRes.books_detail_split_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !viewState.isSplittingMembers,
                    onClick = { intentDispatcher(LibraryGroupDetailIntent.OnSplitDismissed) },
                ) {
                    Text(stringResource(StringRes.books_merge_cancel))
                }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metadata.title) },
                navigationIcon = {
                    IconButton(
                        onClick = { intentDispatcher(LibraryGroupDetailIntent.OnBackClicked) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
                actions = {
                    if (group.members.size > 1) {
                        TextButton(
                            enabled = !viewState.isSplittingMembers &&
                                !viewState.isRemovingReplica &&
                                !viewState.isExecutingUpload,
                            onClick = {
                                intentDispatcher(
                                    if (viewState.isManagingMembers) {
                                        LibraryGroupDetailIntent.OnManageMembersCancelled
                                    } else {
                                        LibraryGroupDetailIntent.OnManageMembersClicked
                                    },
                                )
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (viewState.isManagingMembers) {
                                        StringRes.books_detail_manage_done
                                    } else {
                                        StringRes.books_detail_manage_groups
                                    },
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (metadata.authors.isNotEmpty()) {
                Text(metadata.authors.joinToString(), style = MaterialTheme.typography.titleMedium)
            }
            metadata.description?.let { description -> Text(description) }
            if (metadata.series.isNotEmpty()) {
                Text(
                    text = "${stringResource(StringRes.books_detail_series)}: " +
                        metadata.series.joinToString { series -> series.name },
                )
            }
            if (metadata.tags.isNotEmpty()) {
                Text(
                    text = "${stringResource(StringRes.books_detail_tags)}: " +
                        metadata.tags.joinToString(),
                )
            }
            val mediaTypes = group.members
                .flatMap { member -> member.snapshot.resources }
                .map { resource -> resource.mediaType.lowercase() }
                .distinct()
            if (mediaTypes.size > 1) {
                Text(
                    text = stringResource(StringRes.books_detail_progress_separate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (viewState.isManagingMembers) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            StringRes.books_detail_split_selection_count,
                            viewState.selectedMemberKeys.size,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !viewState.isSplittingMembers,
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnManageMembersCancelled,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                    Button(
                        enabled = viewState.canSplitSelectedMembers,
                        onClick = {
                            intentDispatcher(LibraryGroupDetailIntent.OnSplitRequested)
                        },
                    ) {
                        Text(stringResource(StringRes.books_detail_split_action))
                    }
                }
                if (viewState.selectedAllMembers) {
                    Text(
                        text = stringResource(StringRes.books_detail_split_keep_member),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (viewState.splitSelectionError) {
                    Text(
                        text = stringResource(StringRes.books_detail_split_stale),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (viewState.replicaRemovalIsStale || viewState.replicaRemovalError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (viewState.replicaRemovalIsStale) {
                            stringResource(StringRes.books_detail_remove_replica_stale)
                        } else {
                            requireNotNull(viewState.replicaRemovalError)
                        },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (viewState.canRetryReplicaRemoval) {
                        TextButton(
                            onClick = {
                                intentDispatcher(
                                    LibraryGroupDetailIntent.OnRemoveDeviceReplicaRetried,
                                )
                            },
                        ) {
                            Text(stringResource(StringRes.books_detail_remove_replica_retry))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                intentDispatcher(
                                    LibraryGroupDetailIntent.OnRemoveDeviceReplicaErrorDismissed,
                                )
                            },
                        ) {
                            Text(stringResource(StringRes.books_merge_cancel))
                        }
                    }
                }
            }
            if (viewState.remoteReplicaDeletionIsStale ||
                viewState.remoteReplicaDeletionError != null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (viewState.remoteReplicaDeletionIsStale) {
                            stringResource(StringRes.books_detail_delete_remote_replica_stale)
                        } else {
                            requireNotNull(viewState.remoteReplicaDeletionError)
                        },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnDeleteRemoteReplicaErrorDismissed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                }
            }
            if (viewState.remoteBookRemovalIsStale || viewState.remoteBookRemovalError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (viewState.remoteBookRemovalIsStale) {
                            stringResource(StringRes.books_detail_remove_remote_book_stale)
                        } else {
                            requireNotNull(viewState.remoteBookRemovalError)
                        },
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = {
                            intentDispatcher(
                                LibraryGroupDetailIntent.OnRemoveRemoteBookErrorDismissed,
                            )
                        },
                    ) {
                        Text(stringResource(StringRes.books_merge_cancel))
                    }
                }
            }
            if (!viewState.showUploadConfirmation && viewState.uploadErrorMessage != null) {
                Text(
                    text = requireNotNull(viewState.uploadErrorMessage),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            viewState.downloadErrorMessage?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (viewState.preferredMediaSourceError) {
                Text(
                    stringResource(StringRes.books_detail_preferred_media_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LibraryGroupLocationsSection(
                group = group,
                onNavigateToReader = onNavigateToReader,
                isSavingPreferredMediaSource = viewState.isSavingPreferredMediaSource,
                onSetPreferredMediaSource = { sourceKey, mediaType ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnPreferredMediaSourceSelected(
                            sourceKey = sourceKey,
                            mediaType = mediaType,
                        ),
                    )
                },
                isManagingMembers = viewState.isManagingMembers,
                selectedSourceKeys = viewState.selectedMemberKeys,
                onMemberSelectionChanged = { sourceKey, selected ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnMemberSelectionChanged(
                            sourceKey = sourceKey,
                            selected = selected,
                        ),
                    )
                },
                allowDeviceReplicaRemoval = !viewState.isManagingMembers &&
                    !viewState.isRemovingReplica &&
                    !viewState.isDeletingRemoteReplica &&
                    !viewState.isRemovingRemoteBook &&
                    !viewState.isResolvingDeviceReplicaRemovalTargets &&
                    viewState.pendingRemoteReplicaDeletionTarget == null &&
                    viewState.pendingRemoteBookRemovalTarget == null &&
                    viewState.pendingReplicaRemoval == null &&
                    viewState.pendingUploadTarget == null &&
                    !viewState.showUploadConfirmation &&
                    !viewState.isExecutingUpload &&
                    !viewState.isExecutingDownload &&
                    !viewState.replicaRemovalIsStale,
                availableDeviceReplicaRemovalTargets = viewState
                    .availableDeviceReplicaRemovalTargets,
                onRemoveDeviceReplica = { sourceKey, resource ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnRemoveDeviceReplicaRequested(
                            sourceKey = sourceKey,
                            resource = resource,
                        ),
                    )
                },
                allowRemoteReplicaDeletion = !viewState.isManagingMembers &&
                    !viewState.isDeletingRemoteReplica &&
                    !viewState.isRemovingReplica &&
                    !viewState.isRemovingRemoteBook &&
                    !viewState.isResolvingRemoteReplicaDeletionTargets &&
                    viewState.pendingRemoteReplicaDeletionTarget == null &&
                    viewState.pendingRemoteBookRemovalTarget == null &&
                    viewState.replicaRemovalTarget == null &&
                    viewState.pendingReplicaRemoval == null &&
                    viewState.pendingUploadTarget == null &&
                    !viewState.showUploadConfirmation &&
                    !viewState.isExecutingUpload &&
                    !viewState.isExecutingDownload &&
                    !viewState.isPreparingReader,
                availableRemoteReplicaDeletionTargets = viewState
                    .availableRemoteReplicaDeletionTargets,
                onDeleteRemoteReplica = { sourceKey, resource ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnDeleteRemoteReplicaRequested(
                            sourceKey = sourceKey,
                            resource = resource,
                        ),
                    )
                },
                allowRemoteBookRemoval = !viewState.isManagingMembers &&
                    !viewState.isRemovingRemoteBook &&
                    !viewState.isDeletingRemoteReplica &&
                    !viewState.isRemovingReplica &&
                    !viewState.isResolvingRemoteBookRemovalTargets &&
                    viewState.pendingRemoteBookRemovalTarget == null &&
                    viewState.pendingRemoteReplicaDeletionTarget == null &&
                    viewState.replicaRemovalTarget == null &&
                    viewState.pendingReplicaRemoval == null &&
                    viewState.pendingUploadTarget == null &&
                    !viewState.showUploadConfirmation &&
                    !viewState.isExecutingUpload &&
                    !viewState.isExecutingDownload &&
                    !viewState.isPreparingReader,
                availableRemoteBookRemovalTargets =
                    viewState.availableRemoteBookRemovalTargets,
                queuedRemoteBookRemovalSourceKeys = viewState.remoteBookRemovalQueuedSourceKeys,
                onRemoveRemoteBook = { sourceKey ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnRemoveRemoteBookRequested(sourceKey),
                    )
                },
                availableUploadTargets = viewState.availableUploadTargets,
                unavailableUploadTargets = viewState.unavailableUploadTargets,
                isResolvingUploadTargets = viewState.isResolvingUploadTargets,
                availableDownloadTargets = viewState.availableDownloadTargets,
                readerCacheTargets = viewState.readerCacheTargets,
                readingProgressBySourceAndMediaType =
                    viewState.readingProgressBySourceAndMediaType,
                downloadStates = viewState.downloadStates,
                isPreparingReader = viewState.isPreparingReader,
                onOpenDownloadedResource = { target ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnCachedMediaOpened(target),
                    )
                },
                allowDownload = !viewState.isManagingMembers &&
                    !viewState.isRemovingReplica &&
                    !viewState.isDeletingRemoteReplica &&
                    !viewState.isRemovingRemoteBook &&
                    viewState.pendingRemoteReplicaDeletionTarget == null &&
                    viewState.pendingRemoteBookRemovalTarget == null &&
                    viewState.replicaRemovalTarget == null &&
                    viewState.pendingReplicaRemoval == null &&
                    !viewState.showSplitConfirmation &&
                    !viewState.showUploadConfirmation &&
                    !viewState.isExecutingUpload &&
                    !viewState.isExecutingDownload &&
                    !viewState.isPreparingReader &&
                    !viewState.isResolvingDownloadTargets,
                onDownload = { target ->
                    intentDispatcher(LibraryGroupDetailIntent.OnDownloadRequested(target))
                },
                allowUpload = !viewState.isManagingMembers &&
                    !viewState.isRemovingReplica &&
                    !viewState.isDeletingRemoteReplica &&
                    !viewState.isRemovingRemoteBook &&
                    viewState.pendingRemoteReplicaDeletionTarget == null &&
                    viewState.pendingRemoteBookRemovalTarget == null &&
                    viewState.replicaRemovalTarget == null &&
                    viewState.pendingReplicaRemoval == null &&
                    !viewState.showSplitConfirmation &&
                    !viewState.showUploadConfirmation &&
                    !viewState.isResolvingUploadTargets &&
                    !viewState.isExecutingUpload &&
                    !viewState.isExecutingDownload &&
                    !viewState.isPreparingReader,
                onUpload = { target ->
                    intentDispatcher(LibraryGroupDetailIntent.OnUploadRequested(target))
                },
            )
            LibraryGroupTransferSection(
                transfers = viewState.transfers,
                isLoading = viewState.isLoadingTransfers,
                errorMessage = viewState.transferErrorMessage,
                activeAction = viewState.activeTransferAction,
                onCancel = { actionKey ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnCancelTransfer(
                            adapterId = actionKey.adapterId,
                            transferId = actionKey.transferId,
                        ),
                    )
                },
                onRetry = { actionKey ->
                    intentDispatcher(
                        LibraryGroupDetailIntent.OnRetryTransfer(
                            adapterId = actionKey.adapterId,
                            transferId = actionKey.transferId,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun LibraryGroupUnavailableContent(
    errorMessage: String?,
    intentDispatcher: IntentDispatcher<LibraryGroupDetailIntent>,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(StringRes.books_detail_group_unavailable))
        errorMessage?.let { message -> Text(message) }
        TextButton(onClick = { intentDispatcher(LibraryGroupDetailIntent.OnRetryClicked) }) {
            Text(stringResource(StringRes.books_detail_retry))
        }
    }
}
