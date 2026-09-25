package com.retro99.books.ui.detail

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.getOrElse
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.DeviceStorageAvailability
import com.retro99.books.domain.model.BookType
import com.retro99.library.domain.grouping.LibraryManualGroupingRepository
import com.retro99.library.domain.grouping.SetPreferredLibraryMediaSourceUseCase
import com.retro99.library.domain.grouping.SplitLibraryGroupMembersUseCase
import com.retro99.library.domain.operation.CancelLibraryTransferUseCase
import com.retro99.library.domain.operation.ExecuteLibraryBookOperationUseCase
import com.retro99.library.domain.operation.ExecuteLibraryOperationUseCase
import com.retro99.library.domain.operation.LibraryReplicaRemovalResult
import com.retro99.library.domain.operation.LibraryUploadSourceInventory
import com.retro99.library.domain.operation.ObserveLibraryTransfersUseCase
import com.retro99.library.domain.operation.RemoveLibraryDeviceReplicaUseCase
import com.retro99.library.domain.operation.ResolveLibraryUploadTargetsUseCase
import com.retro99.library.domain.operation.ResolvedLibraryUploadTarget
import com.retro99.library.domain.operation.RetryLibraryTransferUseCase
import com.retro99.library.domain.operation.uploadSourceInventory
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupProjectionRepository
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.library.domain.projection.readerTargetFor
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import com.retro99.reader.domain.usecase.ObserveDownloadStateUseCase
import com.retro99.reader.domain.usecase.PrepareEbookUseCase
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.user.api.UserRegistry
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class LibraryGroupDetailViewModel(
    @InjectedParam private val groupId: String,
    @InjectedParam private val onBack: () -> Unit,
    @InjectedParam private val onNavigateToReader: (LibraryReaderTarget, BookType) -> Unit,
    @InjectedParam private val onReplaceWithCanonicalGroup: (String) -> Unit,
    @Provided private val groupProjectionRepository: LibraryGroupProjectionRepository,
    @Provided private val userRegistry: UserRegistry,
    @Provided manualGroupingRepository: LibraryManualGroupingRepository,
    @Provided private val setPreferredLibraryMediaSourceUseCase:
        SetPreferredLibraryMediaSourceUseCase,
    @Provided private val removeLibraryDeviceReplicaUseCase: RemoveLibraryDeviceReplicaUseCase,
    @Provided private val resolveLibraryUploadTargetsUseCase: ResolveLibraryUploadTargetsUseCase,
    @Provided private val executeLibraryOperationUseCase: ExecuteLibraryOperationUseCase,
    @Provided private val executeLibraryBookOperationUseCase:
        ExecuteLibraryBookOperationUseCase,
    @Provided private val observeLibraryTransfersUseCase: ObserveLibraryTransfersUseCase,
    @Provided private val cancelLibraryTransferUseCase: CancelLibraryTransferUseCase,
    @Provided private val retryLibraryTransferUseCase: RetryLibraryTransferUseCase,
    @Provided private val libraryOperationAdapterRegistry: LibraryOperationAdapterRegistry,
    @Provided private val observeDownloadStateUseCase: ObserveDownloadStateUseCase,
    @Provided private val observeAllBooksWithProgressUseCase:
        ObserveAllBooksWithProgressUseCase,
    @Provided private val prepareEbookUseCase: PrepareEbookUseCase,
    @Provided private val deviceStorageAvailability: DeviceStorageAvailability,
) : BaseViewModel<LibraryGroupDetailViewState, LibraryGroupDetailIntent>(
    LibraryGroupDetailViewState(),
) {
    private var groupObservationJob: Job? = null
    private var progressObservationJob: Job? = null
    private var observedProgressGroupId: LibraryGroupId? = null
    private var observedProgressSourceKeys: Set<SourceBookKey> = emptySet()
    private var deviceReplicaRemovalTargetResolutionJob: Job? = null
    private var remoteReplicaDeletionTargetResolutionJob: Job? = null
    private var remoteBookRemovalTargetResolutionJob: Job? = null
    private var uploadTargetResolutionJob: Job? = null
    private var downloadTargetResolutionJob: Job? = null
    private var transferObservationJob: Job? = null
    private val readerCacheDownloadObservationJobs = mutableMapOf<SourceResourceRef, Job>()
    private val readerCacheDownloadObservationTargets =
        mutableMapOf<SourceResourceRef, LibraryGroupCachedMediaTarget>()
    private var observedUploadInventory: LibraryUploadSourceInventory? = null
    private var observedDeviceReplicaRemovalTargets:
        List<LibraryGroupDeviceReplicaRemovalTarget>? = null
    private var observedRemoteReplicaDeletionTargets:
        List<LibraryGroupRemoteReplicaDeletionTarget>? = null
    private var observedRemoteBookRemovalTargets:
        List<LibraryGroupRemoteBookRemovalTarget>? = null
    private val queuedRemoteBookRemovalRevisions = mutableMapOf<SourceBookKey, String>()
    private var observedDownloadTargets: List<LibraryGroupDownloadTarget>? = null
    private var observedReaderCacheTargets: List<LibraryGroupCachedMediaTarget>? = null
    private var observedTransferSources: List<SourceBookRef>? = null
    private var replacedRouteWithGroupId: LibraryGroupId? = null
    private val splitLibraryGroupMembersUseCase =
        SplitLibraryGroupMembersUseCase(manualGroupingRepository)

    init {
        observeGroup()
    }

    override fun onIntent(intent: LibraryGroupDetailIntent) {
        when (intent) {
            LibraryGroupDetailIntent.OnBackClicked -> onBack()
            LibraryGroupDetailIntent.OnRetryClicked -> observeGroup()
            is LibraryGroupDetailIntent.OnPreferredMediaSourceSelected -> {
                setPreferredMediaSource(intent.sourceKey, intent.mediaType)
            }
            LibraryGroupDetailIntent.OnManageMembersClicked -> startManagingMembers()
            LibraryGroupDetailIntent.OnManageMembersCancelled -> cancelManagingMembers()
            is LibraryGroupDetailIntent.OnMemberSelectionChanged -> {
                setMemberSelected(intent.sourceKey, intent.selected)
            }
            LibraryGroupDetailIntent.OnSplitRequested -> requestSplit()
            LibraryGroupDetailIntent.OnSplitConfirmed -> splitSelectedMembers()
            LibraryGroupDetailIntent.OnSplitDismissed -> updateState {
                it.copy(showSplitConfirmation = false)
            }
            is LibraryGroupDetailIntent.OnRemoveDeviceReplicaRequested -> {
                requestDeviceReplicaRemoval(intent.sourceKey, intent.resource)
            }
            LibraryGroupDetailIntent.OnRemoveDeviceReplicaConfirmed -> {
                confirmDeviceReplicaRemoval()
            }
            LibraryGroupDetailIntent.OnRemoveDeviceReplicaDismissed -> {
                dismissDeviceReplicaRemoval()
            }
            LibraryGroupDetailIntent.OnRemoveDeviceReplicaRetried -> {
                retryDeviceReplicaRemoval()
            }
            LibraryGroupDetailIntent.OnRemoveDeviceReplicaErrorDismissed -> {
                dismissDeviceReplicaRemovalError()
            }
            is LibraryGroupDetailIntent.OnDeleteRemoteReplicaRequested -> {
                requestRemoteReplicaDeletion(intent.sourceKey, intent.resource)
            }
            LibraryGroupDetailIntent.OnDeleteRemoteReplicaConfirmed -> {
                confirmRemoteReplicaDeletion()
            }
            LibraryGroupDetailIntent.OnDeleteRemoteReplicaDismissed -> {
                dismissRemoteReplicaDeletion()
            }
            LibraryGroupDetailIntent.OnDeleteRemoteReplicaErrorDismissed -> {
                dismissRemoteReplicaDeletionError()
            }
            is LibraryGroupDetailIntent.OnRemoveRemoteBookRequested -> {
                requestRemoteBookRemoval(intent.sourceKey)
            }
            LibraryGroupDetailIntent.OnRemoveRemoteBookConfirmed -> {
                confirmRemoteBookRemoval()
            }
            LibraryGroupDetailIntent.OnRemoveRemoteBookDismissed -> {
                dismissRemoteBookRemoval()
            }
            LibraryGroupDetailIntent.OnRemoveRemoteBookErrorDismissed -> {
                dismissRemoteBookRemovalError()
            }
            is LibraryGroupDetailIntent.OnUploadRequested -> requestUpload(intent.target)
            is LibraryGroupDetailIntent.OnUploadRightsAttestationChanged -> {
                updateState { it.copy(uploadRightsAttested = intent.attested) }
            }
            LibraryGroupDetailIntent.OnUploadConfirmed -> confirmUpload()
            LibraryGroupDetailIntent.OnUploadDismissed -> dismissUpload()
            is LibraryGroupDetailIntent.OnDownloadRequested -> requestDownload(intent.target)
            is LibraryGroupDetailIntent.OnDownloadedResourceOpened -> {
                openDownloadedResource(intent.target)
            }
            is LibraryGroupDetailIntent.OnCachedMediaOpened -> {
                openDownloadedResource(intent.target)
            }
            is LibraryGroupDetailIntent.OnDeviceResourceOpenRequested -> {
                openDeviceResource(intent.target, intent.bookType)
            }
            is LibraryGroupDetailIntent.OnCancelTransfer -> cancelTransfer(
                adapterId = intent.adapterId,
                transferId = intent.transferId,
            )
            is LibraryGroupDetailIntent.OnRetryTransfer -> retryTransfer(
                adapterId = intent.adapterId,
                transferId = intent.transferId,
            )
        }
    }

    private fun observeGroup() {
        groupObservationJob?.cancel()
        updateState { state ->
            state.copy(isLoading = true, errorMessage = null)
        }
        val requestedGroupId = LibraryGroupId(groupId)
        groupObservationJob = userRegistry.observeActiveProfile()
            .map { profile ->
                LibraryProfileId(profile?.id ?: UserRegistry.DEFAULT_USER_ID)
            }
            .distinctUntilChanged()
            .flatMapLatest { profileId ->
                groupProjectionRepository.observeGroups(profileId)
                    .map { groups ->
                        val resolvedGroup = groupProjectionRepository.getGroup(
                            profileId = profileId,
                            groupId = requestedGroupId,
                        )
                        groups.firstOrNull { group ->
                            group.groupId == resolvedGroup?.groupId
                        }
                    }
            }
            .onEach { group ->
                if (group != null && group.groupId != requestedGroupId &&
                    replacedRouteWithGroupId != group.groupId
                ) {
                    replacedRouteWithGroupId = group.groupId
                    onReplaceWithCanonicalGroup(group.groupId.value)
                }
                updateState { state ->
                    val hasStaleSelection = state.isManagingMembers &&
                        (group == null || state.managementGroupId != group.groupId ||
                            state.selectedMemberKeys.any { sourceKey ->
                                group.members.none { member -> member.sourceKey == sourceKey }
                            })
                    state.copy(
                        group = group,
                        isLoading = false,
                        errorMessage = null,
                        splitSelectionError = state.splitSelectionError || hasStaleSelection,
                    )
                }
                observeGroupProgress(group)
                reconcileQueuedRemoteBookRemovals(group)
                observeGroupOperations(group)
            }
            .catch { exception ->
                updateState { state ->
                    state.copy(
                        group = null,
                        isLoading = false,
                        errorMessage = exception.message,
                    )
                }
                observeGroupProgress(null)
                reconcileQueuedRemoteBookRemovals(null)
                observeGroupOperations(null)
            }
            .launchIn(viewModelScope)
    }

    private fun observeGroupProgress(group: LibraryBookGroup?) {
        val requestedGroupId = group?.groupId
        val sourceKeys = group?.members?.map { member -> member.sourceKey }?.toSet().orEmpty()
        if (requestedGroupId == observedProgressGroupId &&
            sourceKeys == observedProgressSourceKeys
        ) {
            return
        }

        progressObservationJob?.cancel()
        observedProgressGroupId = requestedGroupId
        observedProgressSourceKeys = sourceKeys
        updateState { state ->
            state.copy(readingProgressBySourceAndMediaType = emptyMap())
        }
        if (group == null) return

        val observedGroup = group
        progressObservationJob = observeAllBooksWithProgressUseCase()
            .map { result -> result.getOrElse { emptyList() } }
            .map { booksWithProgress ->
                projectLibraryGroupReadingProgress(observedGroup, booksWithProgress)
            }
            .onEach { progressBySourceAndMediaType ->
                updateState { state ->
                    if (state.group?.groupId == observedGroup.groupId) {
                        state.copy(
                            readingProgressBySourceAndMediaType =
                                progressBySourceAndMediaType,
                        )
                    } else {
                        state
                    }
                }
            }
            .catch {
                updateState { state ->
                    if (state.group?.groupId == observedGroup.groupId) {
                        state.copy(readingProgressBySourceAndMediaType = emptyMap())
                    } else {
                        state
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun startManagingMembers() {
        val group = viewState.value.group ?: return
        if (group.members.size < 2 || viewState.value.isSplittingMembers) return
        updateState {
            it.copy(
                isManagingMembers = true,
                managementGroupId = group.groupId,
                selectedMemberKeys = emptySet(),
                showSplitConfirmation = false,
                splitSelectionError = false,
            )
        }
    }

    private fun setPreferredMediaSource(sourceKey: SourceBookKey, mediaType: String) {
        val state = viewState.value
        val group = state.group ?: return
        if (state.isSavingPreferredMediaSource || state.isManagingMembers ||
            group.members.none { member -> member.sourceKey == sourceKey }
        ) {
            return
        }
        updateState {
            it.copy(
                isSavingPreferredMediaSource = true,
                preferredMediaSourceError = false,
            )
        }
        viewModelScope.launch {
            try {
                setPreferredLibraryMediaSourceUseCase(
                    profileId = group.profileId,
                    groupId = group.groupId,
                    mediaType = mediaType,
                    sourceKey = sourceKey,
                )
                updateState { currentState ->
                    currentState.copy(
                        isSavingPreferredMediaSource = false,
                        preferredMediaSourceError = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        isSavingPreferredMediaSource = false,
                        preferredMediaSourceError = true,
                    )
                }
            }
        }
    }

    private fun cancelManagingMembers() {
        if (viewState.value.isSplittingMembers) return
        updateState {
            it.copy(
                isManagingMembers = false,
                managementGroupId = null,
                selectedMemberKeys = emptySet(),
                showSplitConfirmation = false,
                splitSelectionError = false,
            )
        }
    }

    private fun setMemberSelected(sourceKey: SourceBookKey, selected: Boolean) {
        val state = viewState.value
        val group = state.group ?: return
        if (!state.isManagingMembers || state.isSplittingMembers ||
            state.managementGroupId != group.groupId ||
            group.members.none { member -> member.sourceKey == sourceKey }
        ) {
            updateState { it.copy(splitSelectionError = true) }
            return
        }
        updateState { currentState ->
            val selectedKeys = if (selected) {
                currentState.selectedMemberKeys + sourceKey
            } else {
                currentState.selectedMemberKeys - sourceKey
            }
            currentState.copy(
                selectedMemberKeys = selectedKeys,
                splitSelectionError = false,
            )
        }
    }

    private fun requestSplit() {
        val state = viewState.value
        if (state.canSplitSelectedMembers) {
            updateState {
                it.copy(showSplitConfirmation = true, splitSelectionError = false)
            }
        } else if (state.selectedAllMembers) {
            updateState { it.copy(splitSelectionError = true) }
        }
    }

    private fun splitSelectedMembers() {
        val state = viewState.value
        val sourceGroupId = state.managementGroupId ?: return
        if (!state.canSplitSelectedMembers) return
        updateState {
            it.copy(
                showSplitConfirmation = false,
                isSplittingMembers = true,
                splitSelectionError = false,
            )
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                splitLibraryGroupMembersUseCase(
                    profileId = profileId,
                    sourceGroupId = sourceGroupId,
                    movedSourceKeys = state.selectedMemberKeys.toList(),
                )
                updateState {
                    it.copy(
                        isManagingMembers = false,
                        managementGroupId = null,
                        selectedMemberKeys = emptySet(),
                        isSplittingMembers = false,
                        splitSelectionError = false,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                updateState {
                    it.copy(
                        selectedMemberKeys = emptySet(),
                        showSplitConfirmation = false,
                        isSplittingMembers = false,
                        splitSelectionError = true,
                    )
                }
            }
        }
    }

    private fun requestDeviceReplicaRemoval(
        sourceKey: SourceBookKey,
        resourceReference: SourceResourceRef,
    ) {
        val state = viewState.value
        val group = state.group ?: return
        if (state.isRemovingReplica || state.isDeletingRemoteReplica ||
            state.isSplittingMembers ||
            state.isManagingMembers || state.pendingReplicaRemoval != null ||
            state.pendingRemoteReplicaDeletionTarget != null ||
            state.pendingUploadTarget != null || state.isExecutingUpload ||
            state.isResolvingDeviceReplicaRemovalTargets
        ) {
            return
        }
        val target = state.availableDeviceReplicaRemovalTargets.firstOrNull { candidate ->
            candidate.groupId == group.groupId && candidate.sourceKey == sourceKey &&
                candidate.resource == resourceReference
        } ?: return
        updateState {
            it.copy(
                replicaRemovalTarget = target,
                showReplicaRemovalConfirmation = true,
                replicaRemovalError = null,
                replicaRemovalIsStale = false,
            )
        }
    }

    private fun confirmDeviceReplicaRemoval() {
        val state = viewState.value
        val target = state.replicaRemovalTarget ?: return
        if (state.isRemovingReplica || !isCurrentDeviceReplica(target)) {
            updateState {
                it.copy(
                    showReplicaRemovalConfirmation = false,
                    replicaRemovalTarget = null,
                    replicaRemovalError = null,
                    replicaRemovalIsStale = true,
                )
            }
            return
        }
        val pending = PendingLibraryGroupReplicaRemoval(
            groupId = target.groupId,
            request = LibraryOperationRequest(
                operationId = newRemovalOperationId(),
                operation = LibraryOperation.RemoveDeviceReplica,
                assetId = MediaAssetId(
                    listOf(
                        target.sourceKey.adapterId.value,
                        target.sourceKey.nativeBookId.value,
                        target.resource.nativeResourceId,
                    ).joinToString(":"),
                ),
                target = target.replica,
            ),
            requestedAt = Clock.System.now().toString(),
        )
        performDeviceReplicaRemoval(pending)
    }

    private fun isCurrentDeviceReplica(
        target: LibraryGroupDeviceReplicaRemovalTarget,
    ): Boolean {
        val group = viewState.value.group ?: return false
        if (group.groupId != target.groupId) return false
        val member = group.members.firstOrNull { candidate ->
            candidate.sourceKey == target.sourceKey
        } ?: return false
        if (member.snapshot.status.presence != SourcePresence.Present) return false
        val resource = member.snapshot.resources.firstOrNull { candidate ->
            candidate.reference == target.resource
        } ?: return false
        return resource.availability == SourceResourceAvailability.DevicePresent &&
            resource.localStorageReference == target.replica.storageRef
    }

    private fun dismissDeviceReplicaRemoval() {
        if (viewState.value.isRemovingReplica) return
        updateState {
            it.copy(
                replicaRemovalTarget = null,
                showReplicaRemovalConfirmation = false,
            )
        }
    }

    private fun retryDeviceReplicaRemoval() {
        val pending = viewState.value.pendingReplicaRemoval ?: return
        performDeviceReplicaRemoval(pending)
    }

    private fun performDeviceReplicaRemoval(
        pending: PendingLibraryGroupReplicaRemoval,
    ) {
        updateState {
            it.copy(
                pendingReplicaRemoval = pending,
                showReplicaRemovalConfirmation = false,
                isRemovingReplica = true,
                replicaRemovalError = null,
                replicaRemovalIsStale = false,
            )
        }
        viewModelScope.launch {
            try {
                when (
                    val result = removeLibraryDeviceReplicaUseCase(
                        groupId = pending.groupId,
                        request = pending.request,
                        requestedAt = pending.requestedAt,
                    )
                ) {
                    is LibraryReplicaRemovalResult.Completed -> updateState {
                        it.copy(
                            replicaRemovalTarget = null,
                            pendingReplicaRemoval = null,
                            isRemovingReplica = false,
                            replicaRemovalError = null,
                        )
                    }
                    is LibraryReplicaRemovalResult.Blocked -> updateState {
                        it.copy(
                        isRemovingReplica = false,
                        replicaRemovalError = result.reason,
                        replicaRemovalIsStale = false,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState {
                    it.copy(
                        isRemovingReplica = false,
                        replicaRemovalError = exception.message
                            ?: "The device copy could not be removed.",
                        replicaRemovalIsStale = false,
                    )
                }
            }
        }
    }

    private fun dismissDeviceReplicaRemovalError() {
        if (viewState.value.isRemovingReplica) return
        updateState {
            it.copy(
                replicaRemovalTarget = null,
                pendingReplicaRemoval = null,
                replicaRemovalError = null,
                replicaRemovalIsStale = false,
            )
        }
    }

    private fun requestRemoteReplicaDeletion(
        sourceKey: SourceBookKey,
        resourceReference: SourceResourceRef,
    ) {
        val state = viewState.value
        val group = state.group ?: return
        if (state.isDeletingRemoteReplica || state.isRemovingReplica || state.isSplittingMembers ||
            state.isManagingMembers || state.pendingReplicaRemoval != null ||
            state.replicaRemovalTarget != null ||
            state.pendingRemoteReplicaDeletionTarget != null ||
            state.pendingUploadTarget != null || state.isExecutingUpload ||
            state.isExecutingDownload || state.isPreparingReader ||
            state.isResolvingRemoteReplicaDeletionTargets
        ) {
            return
        }
        val target = state.availableRemoteReplicaDeletionTargets.firstOrNull { candidate ->
            candidate.groupId == group.groupId && candidate.sourceKey == sourceKey &&
                candidate.resource == resourceReference
        } ?: return
        updateState {
            it.copy(
                pendingRemoteReplicaDeletionTarget = target,
                showRemoteReplicaDeletionConfirmation = true,
                remoteReplicaDeletionError = null,
                remoteReplicaDeletionIsStale = false,
            )
        }
    }

    private fun confirmRemoteReplicaDeletion() {
        val state = viewState.value
        val target = state.pendingRemoteReplicaDeletionTarget ?: return
        val selectedGroup = state.group ?: return
        if (state.isDeletingRemoteReplica) return
        updateState {
            it.copy(
                isDeletingRemoteReplica = true,
                remoteReplicaDeletionError = null,
                remoteReplicaDeletionIsStale = false,
            )
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before the remote file deletion started"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: return@launch markRemoteReplicaDeletionStale()
                val currentTarget = currentGroup.remoteReplicaDeletionTargets()
                    .firstOrNull { item -> item == target }
                    ?: return@launch markRemoteReplicaDeletionStale()
                val adapter = libraryOperationAdapterRegistry.adapter(
                    currentTarget.target.source.key.adapterId,
                ) ?: return@launch markRemoteReplicaDeletionStale()
                if (!adapter.availability(currentTarget.target)
                        .hasAvailableRemoteReplicaDeletion()
                ) {
                    return@launch markRemoteReplicaDeletionStale()
                }
                val request = LibraryOperationRequest(
                    operationId = newRemoteDeletionOperationId(),
                    operation = LibraryOperation.DeleteRemoteReplica,
                    assetId = currentTarget.assetId,
                    target = currentTarget.target,
                    userConfirmed = true,
                )
                when (val result = executeLibraryOperationUseCase(request)) {
                    is LibraryOperationResult.Accepted -> updateState { currentState ->
                        currentState.copy(
                            pendingRemoteReplicaDeletionTarget = null,
                            showRemoteReplicaDeletionConfirmation = false,
                            isDeletingRemoteReplica = false,
                            remoteReplicaDeletionError = null,
                            remoteReplicaDeletionIsStale = false,
                        )
                    }
                    is LibraryOperationResult.Rejected -> updateState { currentState ->
                        currentState.copy(
                            pendingRemoteReplicaDeletionTarget = null,
                            showRemoteReplicaDeletionConfirmation = false,
                            isDeletingRemoteReplica = false,
                            remoteReplicaDeletionError = result.reason,
                            remoteReplicaDeletionIsStale = false,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        pendingRemoteReplicaDeletionTarget = null,
                        showRemoteReplicaDeletionConfirmation = false,
                        isDeletingRemoteReplica = false,
                        remoteReplicaDeletionError = exception.message
                            ?: "The selected remote file could not be deleted.",
                        remoteReplicaDeletionIsStale = false,
                    )
                }
            }
        }
    }

    private fun markRemoteReplicaDeletionStale() {
        updateState { state ->
            state.copy(
                pendingRemoteReplicaDeletionTarget = null,
                showRemoteReplicaDeletionConfirmation = false,
                isDeletingRemoteReplica = false,
                remoteReplicaDeletionError = null,
                remoteReplicaDeletionIsStale = true,
            )
        }
    }

    private fun dismissRemoteReplicaDeletion() {
        if (viewState.value.isDeletingRemoteReplica) return
        updateState { state ->
            state.copy(
                pendingRemoteReplicaDeletionTarget = null,
                showRemoteReplicaDeletionConfirmation = false,
            )
        }
    }

    private fun dismissRemoteReplicaDeletionError() {
        if (viewState.value.isDeletingRemoteReplica) return
        updateState { state ->
            state.copy(
                remoteReplicaDeletionError = null,
                remoteReplicaDeletionIsStale = false,
            )
        }
    }

    private fun requestRemoteBookRemoval(sourceKey: SourceBookKey) {
        val state = viewState.value
        val group = state.group ?: return
        if (state.isRemovingRemoteBook || state.isDeletingRemoteReplica ||
            state.isRemovingReplica ||
            state.isSplittingMembers || state.isManagingMembers ||
            state.pendingRemoteBookRemovalTarget != null ||
            state.pendingRemoteReplicaDeletionTarget != null ||
            state.pendingReplicaRemoval != null ||
            state.pendingUploadTarget != null || state.isExecutingUpload ||
            state.isExecutingDownload || state.isPreparingReader ||
            state.isResolvingRemoteBookRemovalTargets
        ) {
            return
        }
        val target = state.availableRemoteBookRemovalTargets.firstOrNull { candidate ->
            candidate.groupId == group.groupId && candidate.sourceKey == sourceKey
        } ?: return
        updateState {
            it.copy(
                pendingRemoteBookRemovalTarget = target,
                showRemoteBookRemovalConfirmation = true,
                remoteBookRemovalError = null,
                remoteBookRemovalIsStale = false,
            )
        }
    }

    private fun confirmRemoteBookRemoval() {
        val state = viewState.value
        val target = state.pendingRemoteBookRemovalTarget ?: return
        val selectedGroup = state.group ?: return
        if (state.isRemovingRemoteBook) return
        updateState {
            it.copy(
                isRemovingRemoteBook = true,
                remoteBookRemovalError = null,
                remoteBookRemovalIsStale = false,
            )
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before Cloud book removal started"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: return@launch markRemoteBookRemovalStale()
                val currentTarget = currentGroup.remoteBookRemovalTargets()
                    .firstOrNull { candidate -> candidate == target }
                    ?: return@launch markRemoteBookRemovalStale()
                val expectedSourceRevision = target.sourceRevision
                    ?: return@launch markRemoteBookRemovalStale()
                val result = executeLibraryBookOperationUseCase(
                    LibraryBookOperationRequest(
                        operationId = newRemoteBookRemovalOperationId(),
                        operation = LibraryBookOperation.RemoveRemoteBook,
                        source = currentTarget.source,
                        expectedSourceRevision = expectedSourceRevision,
                        userConfirmed = true,
                    ),
                )
                when (result) {
                    is LibraryOperationResult.Accepted -> {
                        queuedRemoteBookRemovalRevisions[currentTarget.sourceKey] =
                            expectedSourceRevision
                        updateState { currentState ->
                            currentState.copy(
                                pendingRemoteBookRemovalTarget = null,
                                showRemoteBookRemovalConfirmation = false,
                                isRemovingRemoteBook = false,
                                remoteBookRemovalError = null,
                                remoteBookRemovalIsStale = false,
                                remoteBookRemovalQueuedSourceKeys =
                                    currentState.remoteBookRemovalQueuedSourceKeys +
                                        currentTarget.sourceKey,
                            )
                        }
                    }
                    is LibraryOperationResult.Rejected -> updateState { currentState ->
                        currentState.copy(
                            pendingRemoteBookRemovalTarget = null,
                            showRemoteBookRemovalConfirmation = false,
                            isRemovingRemoteBook = false,
                            remoteBookRemovalError = result.reason,
                            remoteBookRemovalIsStale = false,
                        )
                    }
                }
                reconcileQueuedRemoteBookRemovals(viewState.value.group)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        pendingRemoteBookRemovalTarget = null,
                        showRemoteBookRemovalConfirmation = false,
                        isRemovingRemoteBook = false,
                        remoteBookRemovalError = exception.message
                            ?: "The Cloud book could not be removed.",
                        remoteBookRemovalIsStale = false,
                    )
                }
            }
        }
    }

    private fun markRemoteBookRemovalStale() {
        updateState { state ->
            state.copy(
                pendingRemoteBookRemovalTarget = null,
                showRemoteBookRemovalConfirmation = false,
                isRemovingRemoteBook = false,
                remoteBookRemovalError = null,
                remoteBookRemovalIsStale = true,
            )
        }
    }

    private fun reconcileQueuedRemoteBookRemovals(group: LibraryBookGroup?) {
        val staleSourceKeys = queuedRemoteBookRemovalRevisions.mapNotNull { (sourceKey, revision) ->
            val member = group?.members?.firstOrNull { candidate ->
                candidate.sourceKey == sourceKey
            }
            if (member == null || member.snapshot.status.presence != SourcePresence.Present ||
                member.snapshot.status.revision != revision
            ) {
                sourceKey
            } else {
                null
            }
        }.toSet()
        if (staleSourceKeys.isEmpty()) return

        staleSourceKeys.forEach { sourceKey ->
            queuedRemoteBookRemovalRevisions.remove(sourceKey)
        }
        updateState { state ->
            state.copy(
                remoteBookRemovalQueuedSourceKeys =
                    state.remoteBookRemovalQueuedSourceKeys - staleSourceKeys,
            )
        }
    }

    private fun dismissRemoteBookRemoval() {
        if (viewState.value.isRemovingRemoteBook) return
        updateState { state ->
            state.copy(
                pendingRemoteBookRemovalTarget = null,
                showRemoteBookRemovalConfirmation = false,
            )
        }
    }

    private fun dismissRemoteBookRemovalError() {
        if (viewState.value.isRemovingRemoteBook) return
        updateState { state ->
            state.copy(
                remoteBookRemovalError = null,
                remoteBookRemovalIsStale = false,
            )
        }
    }

    private fun observeGroupOperations(group: LibraryBookGroup?) {
        if (group == null) {
            deviceReplicaRemovalTargetResolutionJob?.cancel()
            remoteReplicaDeletionTargetResolutionJob?.cancel()
            remoteBookRemovalTargetResolutionJob?.cancel()
            uploadTargetResolutionJob?.cancel()
            downloadTargetResolutionJob?.cancel()
            transferObservationJob?.cancel()
            readerCacheDownloadObservationJobs.values.forEach { job -> job.cancel() }
            readerCacheDownloadObservationJobs.clear()
            readerCacheDownloadObservationTargets.clear()
            observedUploadInventory = null
            observedDeviceReplicaRemovalTargets = null
            observedRemoteReplicaDeletionTargets = null
            observedRemoteBookRemovalTargets = null
            observedDownloadTargets = null
            observedReaderCacheTargets = null
            observedTransferSources = null
            updateState { state ->
                state.copy(
                    availableUploadTargets = emptyList(),
                    unavailableUploadTargets = emptyList(),
                    isResolvingUploadTargets = false,
                    availableDeviceReplicaRemovalTargets = emptySet(),
                    isResolvingDeviceReplicaRemovalTargets = false,
                    availableRemoteReplicaDeletionTargets = emptySet(),
                    isResolvingRemoteReplicaDeletionTargets = false,
                    availableRemoteBookRemovalTargets = emptySet(),
                    isResolvingRemoteBookRemovalTargets = false,
                    availableDownloadTargets = emptyList(),
                    readerCacheTargets = emptyList(),
                    isResolvingDownloadTargets = false,
                    downloadStates = emptyMap(),
                    isPreparingReader = false,
                    transfers = emptyList(),
                    isLoadingTransfers = false,
                )
            }
            return
        }

        val deviceReplicaRemovalTargets = group.deviceReplicaRemovalTargets()
        if (deviceReplicaRemovalTargets != observedDeviceReplicaRemovalTargets) {
            observedDeviceReplicaRemovalTargets = deviceReplicaRemovalTargets
            deviceReplicaRemovalTargetResolutionJob?.cancel()
            updateState { state ->
                state.copy(
                    availableDeviceReplicaRemovalTargets = emptySet(),
                    isResolvingDeviceReplicaRemovalTargets =
                        deviceReplicaRemovalTargets.isNotEmpty(),
                )
            }
            deviceReplicaRemovalTargetResolutionJob = viewModelScope.launch {
                try {
                    val availableTargets = deviceReplicaRemovalTargets.filter { candidate ->
                        val adapter = libraryOperationAdapterRegistry.adapter(
                            candidate.sourceKey.adapterId,
                        ) ?: return@filter false
                        adapter.availability(candidate.replica)
                            .hasAvailableDeviceReplicaRemoval()
                    }.toSet()
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedDeviceReplicaRemovalTargets == deviceReplicaRemovalTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableDeviceReplicaRemovalTargets = availableTargets,
                                isResolvingDeviceReplicaRemovalTargets = false,
                            )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedDeviceReplicaRemovalTargets == deviceReplicaRemovalTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableDeviceReplicaRemovalTargets = emptySet(),
                                isResolvingDeviceReplicaRemovalTargets = false,
                            )
                        }
                    }
                }
            }
        }

        val remoteReplicaDeletionTargets = group.remoteReplicaDeletionTargets()
        if (remoteReplicaDeletionTargets != observedRemoteReplicaDeletionTargets) {
            observedRemoteReplicaDeletionTargets = remoteReplicaDeletionTargets
            remoteReplicaDeletionTargetResolutionJob?.cancel()
            updateState { state ->
                state.copy(
                    availableRemoteReplicaDeletionTargets = emptySet(),
                    isResolvingRemoteReplicaDeletionTargets =
                        remoteReplicaDeletionTargets.isNotEmpty(),
                )
            }
            remoteReplicaDeletionTargetResolutionJob = viewModelScope.launch {
                try {
                    val availableTargets = remoteReplicaDeletionTargets.filter { candidate ->
                        val adapter = libraryOperationAdapterRegistry.adapter(
                            candidate.sourceKey.adapterId,
                        ) ?: return@filter false
                        adapter.availability(candidate.target)
                            .hasAvailableRemoteReplicaDeletion()
                    }.toSet()
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedRemoteReplicaDeletionTargets == remoteReplicaDeletionTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableRemoteReplicaDeletionTargets = availableTargets,
                                isResolvingRemoteReplicaDeletionTargets = false,
                            )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedRemoteReplicaDeletionTargets == remoteReplicaDeletionTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableRemoteReplicaDeletionTargets = emptySet(),
                                isResolvingRemoteReplicaDeletionTargets = false,
                            )
                        }
                    }
                }
            }
        }

        val remoteBookRemovalTargets = group.remoteBookRemovalTargets()
        if (remoteBookRemovalTargets != observedRemoteBookRemovalTargets) {
            observedRemoteBookRemovalTargets = remoteBookRemovalTargets
            remoteBookRemovalTargetResolutionJob?.cancel()
            updateState { state ->
                state.copy(
                    availableRemoteBookRemovalTargets = emptySet(),
                    isResolvingRemoteBookRemovalTargets = remoteBookRemovalTargets.isNotEmpty(),
                )
            }
            remoteBookRemovalTargetResolutionJob = viewModelScope.launch {
                try {
                    val availableTargets = remoteBookRemovalTargets.filter { candidate ->
                        val adapter = libraryOperationAdapterRegistry.adapter(
                            candidate.sourceKey.adapterId,
                        ) ?: return@filter false
                        adapter.bookAvailability(
                            candidate.source,
                            LibraryBookOperation.RemoveRemoteBook,
                        ).isAvailable
                    }.toSet()
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedRemoteBookRemovalTargets == remoteBookRemovalTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableRemoteBookRemovalTargets = availableTargets,
                                isResolvingRemoteBookRemovalTargets = false,
                            )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedRemoteBookRemovalTargets == remoteBookRemovalTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableRemoteBookRemovalTargets = emptySet(),
                                isResolvingRemoteBookRemovalTargets = false,
                            )
                        }
                    }
                }
            }
        }

        val sources = group.members.map { member -> member.snapshot.source }.distinct()
        if (sources != observedTransferSources) {
            observedTransferSources = sources
            transferObservationJob?.cancel()
            if (sources.isEmpty()) {
                updateState { state ->
                    state.copy(transfers = emptyList(), isLoadingTransfers = false)
                }
            } else {
                updateState { state ->
                    state.copy(
                        transfers = emptyList(),
                        isLoadingTransfers = true,
                        transferErrorMessage = null,
                    )
                }
                transferObservationJob = observeLibraryTransfersUseCase(sources)
                    .onEach { transfers ->
                        updateState { state ->
                            state.copy(
                                transfers = transfers,
                                isLoadingTransfers = false,
                                transferErrorMessage = null,
                            )
                        }
                    }
                    .catch { exception ->
                        updateState { state ->
                            state.copy(
                                isLoadingTransfers = false,
                                transferErrorMessage = exception.message,
                            )
                        }
                    }
                    .launchIn(viewModelScope)
            }
        }

        val readerCacheTargets = group.readerCacheTargets(libraryOperationAdapterRegistry)
        if (readerCacheTargets != observedReaderCacheTargets) {
            observedReaderCacheTargets = readerCacheTargets
            observeReaderCacheDownloadStates(readerCacheTargets)
            updateState { state -> state.copy(readerCacheTargets = readerCacheTargets) }
        }

        val downloadTargets = group.downloadTargets(libraryOperationAdapterRegistry)
        if (downloadTargets != observedDownloadTargets) {
            observedDownloadTargets = downloadTargets
            downloadTargetResolutionJob?.cancel()
            updateState { state ->
                state.copy(
                    availableDownloadTargets = emptyList(),
                    isResolvingDownloadTargets = downloadTargets.isNotEmpty(),
                    downloadErrorMessage = null,
                )
            }
            downloadTargetResolutionJob = viewModelScope.launch {
                try {
                    val availableTargets = downloadTargets.mapNotNull { candidate ->
                        val adapter = libraryOperationAdapterRegistry.adapter(
                            candidate.target.source.key.adapterId,
                        ) ?: return@mapNotNull null
                        if (!adapter.availability(candidate.target).hasAvailableDownload()) {
                            return@mapNotNull null
                        }
                        candidate.withReaderCacheSupport(adapter)
                    }
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedDownloadTargets == downloadTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableDownloadTargets = availableTargets,
                                isResolvingDownloadTargets = false,
                            )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedDownloadTargets == downloadTargets
                    ) {
                        updateState { state ->
                            state.copy(
                                availableDownloadTargets = emptyList(),
                                isResolvingDownloadTargets = false,
                                downloadErrorMessage = exception.message
                                    ?: "Download availability could not be checked.",
                            )
                        }
                    }
                }
            }
        }

        val inventory = group.uploadSourceInventory()
        if (inventory != observedUploadInventory) {
            observedUploadInventory = inventory
            uploadTargetResolutionJob?.cancel()
            updateState { state ->
                state.copy(
                    availableUploadTargets = emptyList(),
                    unavailableUploadTargets = emptyList(),
                    isResolvingUploadTargets = true,
                    uploadErrorMessage = null,
                )
            }
            uploadTargetResolutionJob = viewModelScope.launch {
                try {
                    val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                    check(profileId == group.profileId) {
                        "The active profile changed while loading book actions"
                    }
                    val resolution = resolveLibraryUploadTargetsUseCase(
                        profileId = profileId,
                        sources = inventory.sources,
                        sourceAvailability = inventory.availability,
                    )
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedUploadInventory == inventory
                    ) {
                        updateState { state ->
                            state.copy(
                                availableUploadTargets = resolution.available,
                                unavailableUploadTargets = resolution.unavailable,
                                isResolvingUploadTargets = false,
                            )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if (viewState.value.group?.groupId == group.groupId &&
                        observedUploadInventory == inventory
                    ) {
                        updateState { state ->
                            state.copy(
                                availableUploadTargets = emptyList(),
                                unavailableUploadTargets = emptyList(),
                                isResolvingUploadTargets = false,
                                uploadErrorMessage = exception.message
                                    ?: "Upload destinations could not be loaded.",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestUpload(target: ResolvedLibraryUploadTarget) {
        val state = viewState.value
        if (state.isResolvingUploadTargets || state.isExecutingUpload ||
            state.isDeletingRemoteReplica || state.pendingRemoteReplicaDeletionTarget != null ||
            state.isManagingMembers ||
            state.isRemovingReplica || state.replicaRemovalTarget != null ||
            state.pendingReplicaRemoval != null || state.showSplitConfirmation ||
            state.pendingUploadTarget != null || state.showUploadConfirmation ||
            target !in state.availableUploadTargets
        ) {
            return
        }
        updateState {
            it.copy(
                pendingUploadTarget = target,
                showUploadConfirmation = true,
                uploadRightsAttested = false,
                uploadErrorMessage = null,
            )
        }
    }

    private fun confirmUpload() {
        val state = viewState.value
        val target = state.pendingUploadTarget ?: return
        val selectedGroup = state.group ?: return
        if (!state.uploadRightsAttested || state.isExecutingUpload) return
        updateState {
            it.copy(
                isExecutingUpload = true,
                uploadErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before the upload started"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: error("This book group is no longer available")
                val inventory = currentGroup.uploadSourceInventory()
                check(target.source in inventory.sources) {
                    "The selected device copy is no longer available"
                }
                val resolution = resolveLibraryUploadTargetsUseCase(
                    profileId = profileId,
                    sources = inventory.sources,
                    sourceAvailability = inventory.availability,
                )
                val currentTarget = resolution.available.firstOrNull { candidate ->
                    candidate == target
                } ?: error("The selected upload destination is no longer available")
                val request = LibraryOperationRequest(
                    operationId = newUploadOperationId(),
                    operation = LibraryOperation.Upload,
                    assetId = currentTarget.source.assetId,
                    target = currentTarget.target,
                    userConfirmed = true,
                )
                when (val result = executeLibraryOperationUseCase(request)) {
                    is LibraryOperationResult.Accepted -> updateState { currentState ->
                        currentState.copy(
                            pendingUploadTarget = null,
                            showUploadConfirmation = false,
                            uploadRightsAttested = false,
                            isExecutingUpload = false,
                            uploadErrorMessage = null,
                        )
                    }
                    is LibraryOperationResult.Rejected -> updateState { currentState ->
                        currentState.copy(
                            isExecutingUpload = false,
                            uploadErrorMessage = result.reason,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        isExecutingUpload = false,
                        uploadErrorMessage = exception.message
                            ?: "The selected book could not be queued for upload.",
                    )
                }
            }
        }
    }

    private fun dismissUpload() {
        if (viewState.value.isExecutingUpload) return
        updateState { state ->
            state.copy(
                pendingUploadTarget = null,
                showUploadConfirmation = false,
                uploadRightsAttested = false,
                uploadErrorMessage = null,
            )
        }
    }

    private fun observeReaderCacheDownloadStates(
        readerCacheTargets: List<LibraryGroupCachedMediaTarget>,
    ) {
        val currentResources = readerCacheTargets.mapTo(mutableSetOf()) { target ->
            target.resourceReference
        }
        readerCacheDownloadObservationJobs.entries.removeAll { entry ->
            val target = readerCacheTargets.firstOrNull { candidate ->
                candidate.resourceReference == entry.key
            }
            val observedTarget = readerCacheDownloadObservationTargets[entry.key]
            if (target != null && observedTarget != null &&
                observedTarget.matchesCacheIdentity(target)
            ) {
                false
            } else {
                entry.value.cancel()
                readerCacheDownloadObservationTargets.remove(entry.key)
                true
            }
        }
        updateState { state ->
            state.copy(downloadStates = state.downloadStates.filterKeys { resource ->
                resource in currentResources
            })
        }
        readerCacheTargets.forEach { target ->
            val resource = target.resourceReference
            if (readerCacheDownloadObservationTargets[resource]
                    ?.matchesCacheIdentity(target) == true
            ) {
                return@forEach
            }
            readerCacheDownloadObservationJobs.remove(resource)?.cancel()
            readerCacheDownloadObservationTargets[resource] = target
            readerCacheDownloadObservationJobs[resource] = observeDownloadStateUseCase(
                bookUuid = target.cacheBookId,
                bookType = target.bookType,
            ).distinctUntilChanged()
                .onEach { downloadState ->
                    updateState { state ->
                        state.copy(
                            downloadStates = state.downloadStates + (resource to downloadState),
                        )
                    }
                }
                .catch { exception ->
                    if (exception !is CancellationException) {
                        updateState { state ->
                            state.copy(downloadStates = state.downloadStates - resource)
                        }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    private fun openDeviceResource(
        target: LibraryReaderTarget,
        bookType: BookType,
    ) {
        val selectedGroup = viewState.value.group ?: return
        if (viewState.value.isPreparingReader) return
        updateState { state ->
            state.copy(isPreparingReader = true, downloadErrorMessage = null)
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before the book was opened"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: error("This book group is no longer available")
                val currentTarget = currentGroup.members.asSequence()
                    .filter { member -> member.sourceKey == target.progressOwner.source.key }
                    .flatMap { member ->
                        member.snapshot.resources.asSequence().mapNotNull { resource ->
                            if (resource.reference == target.resource) {
                                member.readerTargetFor(resource)
                            } else {
                                null
                            }
                        }
                    }
                    .firstOrNull()
                check(currentTarget == target && target.mediaType == bookType.value) {
                    "The selected media resource is no longer available"
                }
                val availableTarget = target.withAvailableDeviceStorage(deviceStorageAvailability)
                    ?: error("The selected file is no longer available on this device")
                onNavigateToReader(availableTarget, bookType)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { state ->
                    state.copy(
                        downloadErrorMessage = exception.message
                            ?: "The selected file could not be opened.",
                    )
                }
            } finally {
                updateState { state -> state.copy(isPreparingReader = false) }
            }
        }
    }

    private fun openDownloadedResource(target: LibraryGroupCachedMediaTarget) {
        val state = viewState.value
        if (state.isPreparingReader || state.isDeletingRemoteReplica ||
            state.pendingRemoteReplicaDeletionTarget != null ||
            state.downloadStates[target.resourceReference] != DownloadState.Cached
        ) {
            return
        }
        val selectedGroup = state.group ?: return
        updateState {
            it.copy(isPreparingReader = true, downloadErrorMessage = null)
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before the book was opened"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: error("This book group is no longer available")
                val currentTarget = currentGroup.readerCacheTargets(
                    libraryOperationAdapterRegistry,
                ).firstOrNull { candidate ->
                    target.matchesCacheIdentity(candidate)
                } ?: error("The selected media resource is no longer available")
                check(
                    observeDownloadStateUseCase(
                        bookUuid = currentTarget.cacheBookId,
                        bookType = currentTarget.bookType,
                    ).first() == DownloadState.Cached,
                ) {
                    "The selected media has not finished downloading"
                }
                val localPath = prepareEbookUseCase(
                    bookUuid = currentTarget.cacheBookId,
                    ebookFilePath = currentTarget.remoteFilePath,
                    bookType = currentTarget.bookType,
                ).getOrElse { error ->
                    error(error.message ?: "The downloaded file could not be opened")
                }
                val latestGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: error("This book group is no longer available")
                check(
                    latestGroup.readerCacheTargets(libraryOperationAdapterRegistry).any {
                        candidate -> target.matchesCacheIdentity(candidate)
                    },
                ) {
                    "The selected media resource changed before it was opened"
                }
                onNavigateToReader(currentTarget.readerTarget(localPath), currentTarget.bookType)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { state ->
                    state.copy(
                        downloadErrorMessage = exception.message
                            ?: "The downloaded file could not be opened.",
                    )
                }
            } finally {
                updateState { state -> state.copy(isPreparingReader = false) }
            }
        }
    }

    private fun openDownloadedResource(target: LibraryGroupDownloadTarget) {
        if (!target.isReaderCacheDownload()) return
        val cachedTarget = viewState.value.readerCacheTargets.firstOrNull { candidate ->
            candidate.source == target.target.source &&
                candidate.resource.reference == target.target.resource &&
                candidate.resource.remoteResourceReference == target.target.remoteRef &&
                candidate.bookType == target.bookType &&
                candidate.cacheBookId == target.downloadCacheId
        } ?: return
        openDownloadedResource(cachedTarget)
    }

    private fun requestDownload(target: LibraryGroupDownloadTarget) {
        val state = viewState.value
        if (state.isResolvingDownloadTargets || state.isExecutingDownload ||
            state.isDeletingRemoteReplica || state.pendingRemoteReplicaDeletionTarget != null ||
            state.isPreparingReader ||
            state.isManagingMembers || state.isRemovingReplica ||
            state.replicaRemovalTarget != null || state.pendingReplicaRemoval != null ||
            state.showSplitConfirmation || state.pendingUploadTarget != null ||
            state.showUploadConfirmation || state.isExecutingUpload ||
            target !in state.availableDownloadTargets
        ) {
            return
        }
        val selectedGroup = state.group ?: return
        updateState {
            it.copy(isExecutingDownload = true, downloadErrorMessage = null)
        }
        viewModelScope.launch {
            try {
                val profileId = LibraryProfileId(userRegistry.getActiveProfileIdOrDefault())
                check(profileId == selectedGroup.profileId) {
                    "The active profile changed before the download started"
                }
                val currentGroup = groupProjectionRepository.getGroup(
                    profileId = profileId,
                    groupId = selectedGroup.groupId,
                ) ?: error("This book group is no longer available")
                check(
                    currentGroup.downloadTargets(libraryOperationAdapterRegistry).any { candidate ->
                        target.matchesProjectionTarget(candidate)
                    },
                ) {
                    "The selected remote file is no longer available"
                }
                when (
                    val result = executeLibraryOperationUseCase(
                        target.toDownloadRequest(newDownloadOperationId()),
                    )
                ) {
                    is LibraryOperationResult.Accepted -> updateState { currentState ->
                        currentState.copy(
                            isExecutingDownload = false,
                            downloadErrorMessage = null,
                        )
                    }
                    is LibraryOperationResult.Rejected -> updateState { currentState ->
                        currentState.copy(
                            isExecutingDownload = false,
                            downloadErrorMessage = result.reason,
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { currentState ->
                    currentState.copy(
                        isExecutingDownload = false,
                        downloadErrorMessage = exception.message
                            ?: "The selected file could not be queued for download.",
                    )
                }
            }
        }
    }

    private fun cancelTransfer(adapterId: LibraryAdapterId, transferId: LibraryTransferId) {
        val state = viewState.value
        val transfer = state.transfers.firstOrNull { item ->
            item.adapterId == adapterId && item.transferId == transferId
        } ?: return
        if (!transfer.canCancel || state.activeTransferAction != null) return
        performTransferAction(transfer) { selected -> cancelLibraryTransferUseCase(selected) }
    }

    private fun retryTransfer(adapterId: LibraryAdapterId, transferId: LibraryTransferId) {
        val state = viewState.value
        val transfer = state.transfers.firstOrNull { item ->
            item.adapterId == adapterId && item.transferId == transferId
        } ?: return
        if (!transfer.canRetry || state.activeTransferAction != null) return
        performTransferAction(transfer) { selected -> retryLibraryTransferUseCase(selected) }
    }

    private fun performTransferAction(
        transfer: LibraryTransferProgress,
        action: suspend (LibraryTransferProgress) -> Unit,
    ) {
        updateState { state ->
            state.copy(
                activeTransferAction = transfer.actionKey(),
                transferErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                action(transfer)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                updateState { state ->
                    state.copy(transferErrorMessage = exception.message)
                }
            } finally {
                updateState { state ->
                    if (state.activeTransferAction == transfer.actionKey()) {
                        state.copy(activeTransferAction = null)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun newRemovalOperationId(): String =
        "remove-device-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"

    private fun newRemoteDeletionOperationId(): String =
        "delete-remote-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"

    private fun newRemoteBookRemovalOperationId(): String =
        "delete-remote-book-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"

    private fun newUploadOperationId(): String =
        "library-upload-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"

    private fun newDownloadOperationId(): String =
        "library-download-${Clock.System.now().toEpochMilliseconds()}-${Random.nextLong()}"

}
