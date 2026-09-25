package com.retro99.books.ui.detail

import com.github.michaelbull.result.Ok
import io.github.vinceglb.filekit.core.PlatformFile

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.DeviceStorageAvailability
import com.retro99.books.domain.FavoritesRepository
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.BackupAllBooksUseCase
import com.retro99.books.domain.usecase.CancelBookFileTransferUseCase
import com.retro99.books.domain.usecase.ImportEpubUseCase
import com.retro99.books.domain.usecase.ObserveAllFavoritesUseCase
import com.retro99.books.domain.usecase.ObserveBookFileTransferUseCase
import com.retro99.books.domain.usecase.ObserveFavoriteUseCase
import com.retro99.books.domain.usecase.ObserveUnifiedServerBooksUseCase
import com.retro99.books.domain.usecase.RemoveBookFileDownloadUseCase
import com.retro99.books.domain.usecase.RetryBookFileTransferUseCase
import com.retro99.books.domain.usecase.StartBookFileDownloadUseCase
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.books.domain.usecase.ToggleFavoriteUseCase
import com.retro99.books.ui.list.BooksListIntent
import com.retro99.books.ui.list.BooksListViewModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.CloudRegistrationResult
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import com.retro99.cloudaccount.domain.usecase.GetCurrentUploadRightsAttestationUseCase
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibraryGroupMembershipRecord
import com.retro99.database.api.library.LibraryGroupRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryManualSeparationRecord
import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.library.data.LibraryGroupingDataRepository
import com.retro99.library.domain.grouping.LibraryGroupPreferenceRepository
import com.retro99.library.domain.grouping.LibraryManualGroupingRepository
import com.retro99.library.domain.grouping.SetPreferredLibraryMediaSourceUseCase
import com.retro99.library.domain.operation.CancelLibraryTransferUseCase
import com.retro99.library.domain.operation.ExecuteLibraryBookOperationUseCase
import com.retro99.library.domain.operation.ExecuteLibraryOperationUseCase
import com.retro99.library.domain.operation.LibraryReplicaRemovalRepository
import com.retro99.library.domain.operation.LibraryReplicaRemovalResult
import com.retro99.library.domain.operation.LibraryUploadTargetRepository
import com.retro99.library.domain.operation.LibraryUploadTargetResolution
import com.retro99.library.domain.operation.ObserveLibraryTransfersUseCase
import com.retro99.library.domain.operation.RecoverLibraryDeviceReplicaRemovalsUseCase
import com.retro99.library.domain.operation.RemoveLibraryDeviceReplicaUseCase
import com.retro99.library.domain.operation.ResolveLibraryUploadTargetsUseCase
import com.retro99.library.domain.operation.RetryLibraryTransferUseCase
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.library.domain.projection.LibraryGroupMember
import com.retro99.library.domain.projection.LibraryGroupProjectionRepository
import com.retro99.library.domain.projection.LibraryReaderTarget
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.BookDownloadManager
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.BookmarkDomainModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.DownloadKey
import com.retro99.reader.domain.model.DownloadState
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.usecase.CancelDownloadUseCase
import com.retro99.reader.domain.usecase.DeleteMediaCacheUseCase
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.reader.domain.usecase.ObserveAllBooksWithProgressUseCase
import com.retro99.reader.domain.usecase.ObserveBookWithProgressUseCase
import com.retro99.reader.domain.usecase.ObserveDownloadStateUseCase
import com.retro99.reader.domain.usecase.PrepareEbookUseCase
import com.retro99.reader.domain.usecase.ResolvePositionConflictUseCase
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationAvailability
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationAdapterRegistry
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceAdapterRegistry
import com.retro99.server.api.library.LibrarySourceRecord
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LibraryTransferProgress
import com.retro99.server.api.library.LibraryTransferStatus
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.ServerBookSourceAdapter
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDetailRouteRegressionTest {
    @Test
    fun remoteBookRemovalUsesTheSourceCapabilityAndWaitsForConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: one connected source advertises a book-level removal capability.
            val sourceMember = member(
                "source",
                "native-book",
                "resource",
                snapshotRevision = "revision-1",
            )
            val currentGroup = group("remote-book-group", listOf(sourceMember))
            val adapter = TestRemoteBookOperationAdapter(sourceMember.sourceKey.adapterId)
            val adapterRegistry = TestOperationAdapterRegistry(listOf(adapter))
            val projectionRepository = TestGroupProjectionRepository(
                activeGroups = listOf(currentGroup),
                groups = listOf(currentGroup),
            )
            val viewModel = libraryGroupDetailViewModel(
                groupId = currentGroup.groupId.value,
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
                operationAdapterRegistry = adapterRegistry,
            )
            advanceUntilIdle()

            // When: the user opens and dismisses the confirmation.
            viewModel.onIntent(
                LibraryGroupDetailIntent.OnRemoveRemoteBookRequested(sourceMember.sourceKey),
            )
            assertEquals(true, viewModel.viewState.value.showRemoteBookRemovalConfirmation)
            viewModel.onIntent(LibraryGroupDetailIntent.OnRemoveRemoteBookDismissed)
            advanceUntilIdle()

            // Then: dismissal does not execute the source operation.
            assertEquals(emptyList(), adapter.requests)

            // When: the user confirms the same current source book.
            viewModel.onIntent(
                LibraryGroupDetailIntent.OnRemoveRemoteBookRequested(sourceMember.sourceKey),
            )
            viewModel.onIntent(LibraryGroupDetailIntent.OnRemoveRemoteBookConfirmed)
            advanceUntilIdle()

            // Then: the request carries the exact source and explicit confirmation.
            val request = adapter.requests.single()
            assertEquals(sourceMember.snapshot.source, request.source)
            assertEquals(LibraryBookOperation.RemoveRemoteBook, request.operation)
            assertEquals(sourceMember.snapshot.status.revision, request.expectedSourceRevision)
            assertEquals(true, request.userConfirmed)
            assertEquals(
                setOf(sourceMember.sourceKey),
                viewModel.viewState.value.remoteBookRemovalQueuedSourceKeys,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun groupDetailsKeepsConcurrentTransfersVisibleAcrossMediaAndSourceChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: transfers are active for two different source members in the same group.
            val firstMember = member("first", "first-book", "first-epub")
            val secondMember = member("second", "second-book", "second-audio")
            val replacementMember = member("replacement", "replacement-book", "replacement-audio")
            val firstTransfer = activeTransfer(
                adapterId = firstMember.sourceKey.adapterId,
                source = firstMember.snapshot.source,
                transferId = "exact-first-transfer-42",
            )
            val secondTransfer = activeTransfer(
                adapterId = secondMember.sourceKey.adapterId,
                source = secondMember.snapshot.source,
                transferId = "exact-second-transfer-107",
            )
            val replacementTransfer = activeTransfer(
                adapterId = replacementMember.sourceKey.adapterId,
                source = replacementMember.snapshot.source,
                transferId = "exact-replacement-transfer-9",
            )
            val currentGroup = group("concurrent-transfers", listOf(firstMember, secondMember))
            val projectionRepository = TestGroupProjectionRepository(
                activeGroups = listOf(currentGroup),
                groups = listOf(currentGroup),
            )
            val firstAdapter = RecordingTransferOperationAdapter(
                firstMember.sourceKey.adapterId,
                mapOf(firstMember.snapshot.source to listOf(firstTransfer)),
            )
            val secondAdapter = RecordingTransferOperationAdapter(
                secondMember.sourceKey.adapterId,
                mapOf(secondMember.snapshot.source to listOf(secondTransfer)),
            )
            val replacementAdapter = RecordingTransferOperationAdapter(
                replacementMember.sourceKey.adapterId,
                mapOf(replacementMember.snapshot.source to listOf(replacementTransfer)),
            )
            val viewModel = libraryGroupDetailViewModel(
                groupId = currentGroup.groupId.value,
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
                operationAdapterRegistry = TestOperationAdapterRegistry(
                    listOf(firstAdapter, secondAdapter, replacementAdapter),
                ),
            )
            advanceUntilIdle()

            // Then: Details exposes both simultaneous rows with their exact native IDs.
            assertEquals(
                listOf("exact-first-transfer-42", "exact-second-transfer-107"),
                viewModel.viewState.value.transfers.map { transfer ->
                    transfer.transferId.value
                },
            )

            // When: the first member's media changes and the second member is replaced by a
            // different source in the live group projection.
            val firstMemberWithDifferentMedia = firstMember.copy(
                snapshot = firstMember.snapshot.copy(
                    resources = firstMember.snapshot.resources.map { resource ->
                        resource.copy(mediaType = "audiobook")
                    },
                ),
            )
            projectionRepository.setGroup(
                currentGroup.copy(
                    members = listOf(firstMemberWithDifferentMedia, replacementMember),
                ),
            )
            advanceUntilIdle()

            // Then: the unchanged source's transfer remains visible, the new source appears,
            // and the removed source's transfer is no longer shown.
            assertEquals(
                listOf("exact-first-transfer-42", "exact-replacement-transfer-9"),
                viewModel.viewState.value.transfers.map { transfer ->
                    transfer.transferId.value
                },
            )

            // When: the user cancels one row by its exact adapter and transfer ID.
            viewModel.onIntent(
                LibraryGroupDetailIntent.OnCancelTransfer(
                    adapterId = firstTransfer.adapterId,
                    transferId = firstTransfer.transferId,
                ),
            )
            advanceUntilIdle()

            // Then: only the owning adapter receives the unchanged native ID.
            assertEquals(listOf(firstTransfer.transferId), firstAdapter.cancelledIds)
            assertEquals(emptyList(), secondAdapter.cancelledIds)
            assertEquals(emptyList(), replacementAdapter.cancelledIds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteBookRemovalCapabilityRefreshesWhenSourceRevisionChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: capability is initially unavailable for the observed source revision.
            val sourceMember = member(
                "source",
                "native-book",
                "resource",
                snapshotRevision = "revision-1",
            )
            val currentGroup = group("remote-book-group", listOf(sourceMember))
            val adapter = TestRemoteBookOperationAdapter(sourceMember.sourceKey.adapterId).apply {
                isBookRemovalAvailable = false
            }
            val projectionRepository = TestGroupProjectionRepository(
                activeGroups = listOf(currentGroup),
                groups = listOf(currentGroup),
            )
            val viewModel = libraryGroupDetailViewModel(
                groupId = currentGroup.groupId.value,
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
                operationAdapterRegistry = TestOperationAdapterRegistry(listOf(adapter)),
            )
            advanceUntilIdle()

            assertEquals(1, adapter.bookAvailabilityCount)
            assertEquals(emptySet(), viewModel.viewState.value.availableRemoteBookRemovalTargets)

            // When: the same source publishes a newer snapshot revision and capability is enabled.
            adapter.isBookRemovalAvailable = true
            val revisedGroup = group(
                currentGroup.groupId.value,
                listOf(sourceMember.withSnapshotRevision("revision-2")),
            )
            projectionRepository.setGroup(revisedGroup)
            advanceUntilIdle()

            // Then: revision identity triggers a new capability lookup for the current target.
            assertEquals(2, adapter.bookAvailabilityCount)
            assertEquals(
                setOf("revision-2"),
                viewModel.viewState.value.availableRemoteBookRemovalTargets.map { target ->
                    target.sourceRevision
                }.toSet(),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteBookRemovalConfirmationIsStaleWhenSourceRevisionChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: a source removal confirmation is open for a specific snapshot revision.
            val sourceMember = member(
                "source",
                "native-book",
                "resource",
                snapshotRevision = "revision-1",
            )
            val currentGroup = group("remote-book-group", listOf(sourceMember))
            val adapter = TestRemoteBookOperationAdapter(sourceMember.sourceKey.adapterId)
            val projectionRepository = TestGroupProjectionRepository(
                activeGroups = listOf(currentGroup),
                groups = listOf(currentGroup),
            )
            val viewModel = libraryGroupDetailViewModel(
                groupId = currentGroup.groupId.value,
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
                operationAdapterRegistry = TestOperationAdapterRegistry(listOf(adapter)),
            )
            advanceUntilIdle()
            viewModel.onIntent(
                LibraryGroupDetailIntent.OnRemoveRemoteBookRequested(sourceMember.sourceKey),
            )
            assertEquals(true, viewModel.viewState.value.showRemoteBookRemovalConfirmation)

            // When: the source revision changes before the user confirms.
            projectionRepository.setGroup(
                group(
                    currentGroup.groupId.value,
                    listOf(sourceMember.withSnapshotRevision("revision-2")),
                ),
            )
            advanceUntilIdle()
            viewModel.onIntent(LibraryGroupDetailIntent.OnRemoveRemoteBookConfirmed)
            advanceUntilIdle()

            // Then: confirmation is rejected as stale and no source removal is executed.
            assertEquals(emptyList(), adapter.requests)
            assertEquals(false, viewModel.viewState.value.showRemoteBookRemovalConfirmation)
            assertEquals(true, viewModel.viewState.value.remoteBookRemovalIsStale)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteBookRemovalQueuedMarkerClearsWhenSourceRevisionAdvances() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sourceMember = member(
                "source",
                "native-book",
                "resource",
                snapshotRevision = "revision-1",
            )
            val currentGroup = group("remote-book-group", listOf(sourceMember))
            val adapter = TestRemoteBookOperationAdapter(sourceMember.sourceKey.adapterId)
            val projectionRepository = TestGroupProjectionRepository(
                activeGroups = listOf(currentGroup),
                groups = listOf(currentGroup),
            )
            val viewModel = libraryGroupDetailViewModel(
                groupId = currentGroup.groupId.value,
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
                operationAdapterRegistry = TestOperationAdapterRegistry(listOf(adapter)),
            )
            advanceUntilIdle()
            viewModel.onIntent(
                LibraryGroupDetailIntent.OnRemoveRemoteBookRequested(sourceMember.sourceKey),
            )
            viewModel.onIntent(LibraryGroupDetailIntent.OnRemoveRemoteBookConfirmed)
            advanceUntilIdle()

            assertEquals(
                setOf(sourceMember.sourceKey),
                viewModel.viewState.value.remoteBookRemovalQueuedSourceKeys,
            )

            projectionRepository.setGroup(
                group(
                    currentGroup.groupId.value,
                    listOf(sourceMember.withSnapshotRevision("revision-2")),
                ),
            )
            advanceUntilIdle()

            assertEquals(emptySet(), viewModel.viewState.value.remoteBookRemovalQueuedSourceKeys)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cloudResourceUpdateKeepsTheBooksListRouteOnTheCurrentGroupProjection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: a Cloud listing is normalized and persisted by the real grouping repository.
            val booksRepository = TestCloudBooksRepository(listOf(cloudServerBook("v1")))
            val groupsDatabase = TestLibraryGroupsDatabase()
            val snapshotsDatabase = TestLibrarySourceSnapshotsDatabase(groupsDatabase)
            val repositoryProvider = TestAuthenticatedRepositoryProvider(booksRepository)
            val projectionRepository = LibraryGroupingDataRepository(
                groupsDatabase = groupsDatabase,
                evidenceDatabase = EmptyLibraryEvidenceDatabase,
                sourceIdentityPromotionDatabase = EmptySourceIdentityPromotionDatabase,
                snapshotsDatabase = snapshotsDatabase,
                repositoryProvider = repositoryProvider,
                sourceAdapterRegistry = TestCloudLibrarySourceAdapterRegistry,
                userRegistry = TestUserRegistry,
                recoverLibraryDeviceReplicaRemovalsUseCase =
                    RecoverLibraryDeviceReplicaRemovalsUseCase(
                        EmptyLibraryReplicaRemovalRepository,
                    ),
            )
            val routedGroupIds = mutableListOf<String>()
            val booksList = booksListViewModel(
                repositoryProvider = repositoryProvider,
                groupProjectionRepository = projectionRepository,
                onNavigateToBookDetail = { book ->
                    routedGroupIds += requireNotNull(book.unifiedGroupId)
                },
            )
            advanceUntilIdle()

            val initialGroupId = requireNotNull(
                booksList.viewState.value.books.single().unifiedGroupId,
            )
            assertEquals(
                "cloud-resource-v1",
                snapshotsDatabase.snapshots.value.single().resources.single()
                    .reference.nativeResourceId,
            )

            // When: the Cloud feed replaces its file resource and the real repository saves it.
            booksRepository.setBooks(listOf(cloudServerBook("v2")))
            advanceUntilIdle()

            val currentBook = booksList.viewState.value.books.single()
            assertEquals(initialGroupId, currentBook.unifiedGroupId)
            assertEquals(
                listOf("cloud-resource-v2"),
                (currentBook as BookUiModel.StorytellerBook).mediaResources
                    .map { resource -> resource.cloudBookFileId },
            )
            booksList.onIntent(BooksListIntent.OnBookClicked(currentBook))
            val groupDetail = libraryGroupDetailViewModel(
                groupId = routedGroupIds.single(),
                groupProjectionRepository = projectionRepository,
                onReplaceWithCanonicalGroup = {},
            )
            advanceUntilIdle()

            // Then: selecting the current row opens the persisted group projection with v2.
            assertEquals(listOf(initialGroupId), routedGroupIds)
            assertEquals(
                listOf("cloud-resource-v2"),
                groupDetail.viewState.value.group?.members
                    ?.flatMap { member -> member.snapshot.resources }
                    ?.map { resource -> resource.reference.nativeResourceId },
            )
            assertEquals(
                "cloud-resource-v2",
                snapshotsDatabase.snapshots.value.single().resources.single()
                    .reference.nativeResourceId,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun audiobookshelfDetailsDownloadsBundleThenOpensCachedResourceWithNativeProgressOwner() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                // Given: one Audiobookshelf audiobook exposes two files for the same bundle.
                val adapterId = LibraryAdapterId("audiobookshelf")
                val nativeBookId = "audiobookshelf-item-42"
                val sourceKey = SourceBookKey(
                    profileId = LibraryProfileId(PROFILE_ID),
                    adapterId = adapterId,
                    accountIdentity = SourceAccountIdentity.Portable(
                        "audiobookshelf",
                        "stable-library-account",
                    ),
                    nativeBookId = NativeBookId(nativeBookId),
                )
                val source = SourceBookRef(
                    key = sourceKey,
                    connectionId = SourceConnectionId("audiobookshelf-connection"),
                )
                val resources = listOf("audio-inode-1", "audio-inode-2").map { nativeId ->
                    SourceMediaResource(
                        reference = SourceResourceRef(sourceKey, nativeId),
                        mediaType = BookType.AUDIOBOOK.value,
                        format = "audio/mpeg",
                        availability = SourceResourceAvailability.AvailableRemotely,
                        remoteResourceReference = RemoteResourceRef(
                            "/api/items/$nativeBookId/file/$nativeId",
                        ),
                    )
                }
                val member = LibraryGroupMember(
                    snapshot = SourceBookSnapshot(
                        source = source,
                        metadata = SourceBookMetadata("Audiobookshelf bundle"),
                        resources = resources,
                        status = SourceSnapshotStatus(
                            observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                            presence = SourcePresence.Present,
                            isAuthoritative = false,
                        ),
                    ),
                    membershipOrigin = LibraryMembershipOrigin.Automatic,
                    membershipRevision = 1,
                    decisionId = null,
                )
                val group = LibraryBookGroup(
                    profileId = LibraryProfileId(PROFILE_ID),
                    groupId = LibraryGroupId("audiobookshelf-group"),
                    displayMetadata = member.snapshot.metadata,
                    members = listOf(member),
                )
                val projectionRepository = TestGroupProjectionRepository(
                    activeGroups = listOf(group),
                    groups = listOf(group),
                )
                val downloadManager = RecordingBookDownloadManager()
                val operationAdapter = RecordingAudiobookshelfOperationAdapter(
                    resources = resources,
                    downloadManager = downloadManager,
                )
                val operationRegistry = TestOperationAdapterRegistry(listOf(operationAdapter))
                val readerSettingsRepository = RecordingReaderSettingsRepository()
                var openedTarget: LibraryReaderTarget? = null
                var openedBookType: BookType? = null
                val details = libraryGroupDetailViewModel(
                    groupId = group.groupId.value,
                    groupProjectionRepository = projectionRepository,
                    onReplaceWithCanonicalGroup = {},
                    onNavigateToReader = { target, bookType ->
                        openedTarget = target
                        openedBookType = bookType
                    },
                    operationAdapterRegistry = operationRegistry,
                    downloadManager = downloadManager,
                    readerSettingsRepository = readerSettingsRepository,
                )
                advanceUntilIdle()

                // When: Details downloads the available representative resource for the bundle.
                val selectedTarget = details.viewState.value.availableDownloadTargets.single()
                assertEquals(resources.first().reference, selectedTarget.target.resource)
                details.onIntent(LibraryGroupDetailIntent.OnDownloadRequested(selectedTarget))
                advanceUntilIdle()

                // Then: the operation retains that resource while the adapter caches the bundle.
                val request = operationAdapter.requests.single()
                val startedDownload = downloadManager.downloads.single()
                assertEquals(LibraryOperation.Download, request.operation)
                assertEquals(selectedTarget.target, request.target)
                assertEquals(resources.first().reference, request.target.resource)
                assertEquals(selectedTarget.assetId, request.assetId)
                assertEquals(selectedTarget.downloadCacheId, request.downloadCacheId)
                assertEquals(selectedTarget.downloadCacheId, startedDownload.bookUuid)
                assertEquals(BookType.AUDIOBOOK, startedDownload.bookType)
                assertEquals("Audiobookshelf bundle", startedDownload.bookTitle)
                assertEquals(source.connectionId?.value, startedDownload.serverId)
                assertEquals(
                    resources.mapNotNull { resource ->
                        resource.remoteResourceReference?.value
                    }.joinToString("|"),
                    startedDownload.filePath,
                )
                assertEquals(
                    mapOf(resources.first().reference to DownloadState.Downloading(0f)),
                    details.viewState.value.downloadStates,
                )

                // When: the shared reader cache reports completion and the selected row is opened.
                downloadManager.markCached(startedDownload.bookUuid, startedDownload.bookType)
                advanceUntilIdle()
                assertEquals(
                    mapOf(resources.first().reference to DownloadState.Cached),
                    details.viewState.value.downloadStates,
                )
                details.onIntent(
                    LibraryGroupDetailIntent.OnDownloadedResourceOpened(selectedTarget),
                )
                advanceUntilIdle()

                // Then: preparation uses the cache key and navigation owns ABS progress.
                val selectedRemotePath = requireNotNull(
                    resources.first().remoteResourceReference?.value,
                )
                assertEquals(
                    listOf(
                        PreparedEbook(
                            bookUuid = selectedTarget.downloadCacheId,
                            ebookFilePath = selectedRemotePath,
                            bookType = BookType.AUDIOBOOK,
                        ),
                    ),
                    readerSettingsRepository.preparedBooks,
                )
                assertEquals(BookType.AUDIOBOOK, openedBookType)
                assertEquals(resources.first().reference, openedTarget?.resource)
                assertEquals(
                    "/reader-cache/${selectedTarget.downloadCacheId}",
                    openedTarget?.storage?.value,
                )
                assertEquals(
                    ProgressOwnerRef(
                        adapterId = adapterId,
                        source = source,
                        nativeProgressId = nativeBookId,
                    ),
                    openedTarget?.progressOwner,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun legacyBookDetailsRoutesResolveSourceRowsToCanonicalGroup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: local imports use their UUID as a resource alias; server books use native IDs.
            val legacyRoutes = listOf(
                "local-installation" to "imported-book-uuid",
                "parrot-cloud" to "cloud-book-uuid",
                "storyteller-server" to "storyteller-book-uuid",
                "audiobookshelf-server" to "audiobookshelf-book-uuid",
            )
            val canonicalGroup = group(
                groupId = "canonical-group",
                members = listOf(
                    member(
                        serverId = legacyRoutes[0].first,
                        nativeBookId = "portable-content-hash",
                        resourceId = legacyRoutes[0].second,
                    ),
                    member(
                        serverId = legacyRoutes[1].first,
                        nativeBookId = legacyRoutes[1].second,
                        resourceId = "cloud-resource-id",
                    ),
                    member(
                        serverId = legacyRoutes[2].first,
                        nativeBookId = legacyRoutes[2].second,
                        resourceId = "storyteller-resource-id",
                    ),
                    member(
                        serverId = legacyRoutes[3].first,
                        nativeBookId = legacyRoutes[3].second,
                        resourceId = "audiobookshelf-resource-id",
                    ),
                ),
            )
            val repository = TestGroupProjectionRepository(
                activeGroups = listOf(canonicalGroup),
                groups = listOf(canonicalGroup),
            )
            val destinations = mutableListOf<String>()

            // When: Details is opened through each serialized legacy server/book route.
            val viewModels = legacyRoutes.map { (serverId, bookUuid) ->
                bookDetailViewModel(
                    serverId = serverId,
                    bookUuid = bookUuid,
                    groupProjectionRepository = repository,
                    onNavigateToLibraryGroup = destinations::add,
                )
            }
            advanceUntilIdle()

            // Then: local aliases and server-native IDs all redirect to the canonical group.
            assertEquals(List(legacyRoutes.size) { canonicalGroup.groupId.value }, destinations)
            assertEquals(
                List(legacyRoutes.size) { canonicalGroup },
                viewModels.map { viewModel -> viewModel.viewState.value.libraryGroup },
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retiredGroupDetailsRouteReplacesItselfWithTheCanonicalGroupId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Given: resolving the retired ID returns the canonical group from the repository.
            val canonicalGroup = group(groupId = "canonical-group")
            val retiredGroupId = LibraryGroupId("retired-group")
            val repository = TestGroupProjectionRepository(
                activeGroups = emptyList(),
                groups = listOf(canonicalGroup),
                resolvedGroupId = retiredGroupId to canonicalGroup,
            )
            val replacements = mutableListOf<String>()

            // When: Details is entered using a route that still contains the retired ID.
            val viewModel = libraryGroupDetailViewModel(
                groupId = retiredGroupId.value,
                groupProjectionRepository = repository,
                onReplaceWithCanonicalGroup = replacements::add,
            )
            advanceUntilIdle()

            // Then: the view state and replacement callback both use the canonical ID.
            assertEquals(listOf(canonicalGroup.groupId.value), replacements)
            assertEquals(canonicalGroup, viewModel.viewState.value.group)
            assertEquals(retiredGroupId, repository.lastRequestedGroupId)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private fun bookDetailViewModel(
    serverId: String,
    bookUuid: String,
    groupProjectionRepository: LibraryGroupProjectionRepository,
    onNavigateToLibraryGroup: (String) -> Unit,
) = BookDetailViewModel(
    serverId = serverId,
    bookUuid = bookUuid,
    onNavigateToLibraryGroup = onNavigateToLibraryGroup,
    onNavigateToReader = { _, _, _, _ -> },
    onNavigateToSeriesDetail = { _, _ -> },
    onBack = {},
    observeBookWithProgressUseCase = ObserveBookWithProgressUseCase(
        repositoryProvider = EmptyAuthenticatedRepositoryProvider,
        readerSettingsRepository = EmptyReaderSettingsRepository,
        positionLocalSource = EmptyServerPositionLocalSource,
    ),
    downloadMediaUseCase = DownloadMediaUseCase(EmptyBookDownloadManager),
    cancelDownloadUseCase = CancelDownloadUseCase(EmptyBookDownloadManager),
    observeDownloadStateUseCase = ObserveDownloadStateUseCase(EmptyBookDownloadManager),
    deleteMediaCacheUseCase = DeleteMediaCacheUseCase(EmptyBookDownloadManager),
    toggleFavoriteUseCase = ToggleFavoriteUseCase(EmptyFavoritesRepository),
    observeFavoriteUseCase = ObserveFavoriteUseCase(EmptyFavoritesRepository),
    resolvePositionConflictUseCase = ResolvePositionConflictUseCase(
        EmptyAuthenticatedRepositoryProvider,
    ),
    fileImportManager = EmptyFileImportManager,
    bookFileTransferManager = EmptyBookFileTransferManager,
    observeBookFileTransferUseCase = ObserveBookFileTransferUseCase(EmptyBookFileTransferManager),
    startBookFileUploadUseCase = StartBookFileUploadUseCase(
        EmptyBookFileTransferManager,
        GetCurrentUploadRightsAttestationUseCase(EmptyUploadRightsAttestationRepository),
    ),
    cancelBookFileTransferUseCase = CancelBookFileTransferUseCase(EmptyBookFileTransferManager),
    retryBookFileTransferUseCase = RetryBookFileTransferUseCase(EmptyBookFileTransferManager),
    startBookFileDownloadUseCase = StartBookFileDownloadUseCase(EmptyBookFileTransferManager),
    removeBookFileDownloadUseCase = RemoveBookFileDownloadUseCase(EmptyBookFileTransferManager),
    uploadRightsAttestationRepository = EmptyUploadRightsAttestationRepository,
    cloudAccountRepository = EmptyCloudAccountRepository,
    cloudProfileLinkRepository = EmptyCloudProfileLinkRepository,
    userRegistry = TestUserRegistry,
    groupProjectionRepository = groupProjectionRepository,
    analytics = EmptyAnalytics,
)

private fun booksListViewModel(
    repositoryProvider: AuthenticatedRepositoryProvider,
    groupProjectionRepository: LibraryGroupProjectionRepository,
    onNavigateToBookDetail: (com.retro99.books.ui.model.BookUiModel) -> Unit,
): BooksListViewModel {
    val preferences = EmptyPreferences
    val observeUnifiedBooks = ObserveUnifiedServerBooksUseCase(
        repositoryProvider,
        groupProjectionRepository,
    )
    val observeBooksWithProgress = ObserveAllBooksWithProgressUseCase(
        repositoryProvider = repositoryProvider,
        readerSettingsRepository = EmptyReaderSettingsRepository,
        positionLocalSource = EmptyBooksListServerPositionLocalSource,
        observeUnifiedServerBooksUseCase = observeUnifiedBooks,
    )
    val attestationUseCase = GetCurrentUploadRightsAttestationUseCase(
        EmptyUploadRightsAttestationRepository,
    )
    val transferManager = EmptyBookFileTransferManager
    return BooksListViewModel(
        onNavigateToBookDetail = onNavigateToBookDetail,
        toggleFavoriteUseCase = ToggleFavoriteUseCase(EmptyFavoritesRepository),
        observeAllFavoritesUseCase = ObserveAllFavoritesUseCase(EmptyFavoritesRepository),
        importEpubUseCase = ImportEpubUseCase(EmptyFileImportManager),
        observeAllBooksWithProgressUseCase = observeBooksWithProgress,
        analytics = EmptyAnalytics,
        observeUserPreferenceUseCase = ObserveUserPreferenceUseCase(
            preferences,
            TestUserRegistry,
        ),
        saveUserPreferenceUseCase = SaveUserPreferenceUseCase(preferences, TestUserRegistry),
        bookFileTransferManager = transferManager,
        backupAllBooksUseCase = BackupAllBooksUseCase(transferManager, attestationUseCase),
        uploadRightsAttestationRepository = EmptyUploadRightsAttestationRepository,
        cloudProfileLinkRepository = EmptyCloudProfileLinkRepository,
        cloudAccountRepository = EmptyCloudAccountRepository,
        startBookFileUploadUseCase = StartBookFileUploadUseCase(
            transferManager,
            attestationUseCase,
        ),
        userRegistry = TestUserRegistry,
        groupProjectionRepository = groupProjectionRepository,
        manualGroupingRepository = EmptyManualGroupingRepository,
    )
}

private fun libraryGroupDetailViewModel(
    groupId: String,
    groupProjectionRepository: LibraryGroupProjectionRepository,
    onReplaceWithCanonicalGroup: (String) -> Unit,
    onNavigateToReader: (LibraryReaderTarget, BookType) -> Unit = { _, _ -> },
    operationAdapterRegistry: LibraryOperationAdapterRegistry =
        EmptyLibraryOperationAdapterRegistry,
    downloadManager: BookDownloadManager = EmptyBookDownloadManager,
    readerSettingsRepository: ReaderSettingsRepository = EmptyReaderSettingsRepository,
) = LibraryGroupDetailViewModel(
    groupId = groupId,
    onBack = {},
    onNavigateToReader = onNavigateToReader,
    onReplaceWithCanonicalGroup = onReplaceWithCanonicalGroup,
    groupProjectionRepository = groupProjectionRepository,
    userRegistry = TestUserRegistry,
    manualGroupingRepository = EmptyManualGroupingRepository,
    setPreferredLibraryMediaSourceUseCase = SetPreferredLibraryMediaSourceUseCase(
        EmptyLibraryGroupPreferenceRepository,
    ),
    removeLibraryDeviceReplicaUseCase = RemoveLibraryDeviceReplicaUseCase(
        EmptyLibraryReplicaRemovalRepository,
    ),
    resolveLibraryUploadTargetsUseCase = ResolveLibraryUploadTargetsUseCase(
        EmptyLibraryUploadTargetRepository,
    ),
    executeLibraryOperationUseCase = ExecuteLibraryOperationUseCase(
        operationAdapterRegistry,
    ),
    executeLibraryBookOperationUseCase = ExecuteLibraryBookOperationUseCase(
        operationAdapterRegistry,
    ),
    observeLibraryTransfersUseCase = ObserveLibraryTransfersUseCase(
        operationAdapterRegistry,
    ),
    cancelLibraryTransferUseCase = CancelLibraryTransferUseCase(
        operationAdapterRegistry,
    ),
    retryLibraryTransferUseCase = RetryLibraryTransferUseCase(
        operationAdapterRegistry,
    ),
    libraryOperationAdapterRegistry = operationAdapterRegistry,
    observeDownloadStateUseCase = ObserveDownloadStateUseCase(downloadManager),
    observeAllBooksWithProgressUseCase = ObserveAllBooksWithProgressUseCase(
        repositoryProvider = EmptyAuthenticatedRepositoryProvider,
        readerSettingsRepository = EmptyReaderSettingsRepository,
        positionLocalSource = EmptyBooksListServerPositionLocalSource,
        observeUnifiedServerBooksUseCase = ObserveUnifiedServerBooksUseCase(
            repositoryProvider = EmptyAuthenticatedRepositoryProvider,
            libraryGroupProjectionRepository = groupProjectionRepository,
        ),
    ),
    prepareEbookUseCase = PrepareEbookUseCase(readerSettingsRepository),
    deviceStorageAvailability = DeviceStorageAvailability { false },
)

private fun group(
    groupId: String,
    members: List<LibraryGroupMember> = emptyList(),
) = LibraryBookGroup(
    profileId = LibraryProfileId(PROFILE_ID),
    groupId = LibraryGroupId(groupId),
    displayMetadata = SourceBookMetadata("Book title"),
    members = members,
)

private fun member(
    serverId: String,
    nativeBookId: String,
    resourceId: String,
    adapterId: String = "adapter-$serverId",
    snapshotRevision: String? = null,
): LibraryGroupMember {
    val profileId = LibraryProfileId(PROFILE_ID)
    val connectionId = SourceConnectionId(serverId)
    val sourceKey = SourceBookKey(
        profileId = profileId,
        adapterId = LibraryAdapterId(adapterId),
        accountIdentity = SourceAccountIdentity.Portable("backend-$serverId", "account-$serverId"),
        nativeBookId = NativeBookId(nativeBookId),
    )
    val resource = SourceResourceRef(sourceKey, resourceId)
    return LibraryGroupMember(
        snapshot = SourceBookSnapshot(
            source = SourceBookRef(sourceKey, connectionId),
            metadata = SourceBookMetadata("Imported title"),
            resources = listOf(SourceMediaResource(resource, mediaType = "ebook")),
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                presence = SourcePresence.Present,
                isAuthoritative = false,
                revision = snapshotRevision,
            ),
        ),
        membershipOrigin = LibraryMembershipOrigin.Automatic,
        membershipRevision = 1,
        decisionId = null,
    )
}

private fun LibraryGroupMember.withSnapshotRevision(revision: String): LibraryGroupMember =
    copy(
        snapshot = snapshot.copy(
            status = snapshot.status.copy(revision = revision),
        ),
    )

private fun cloudServerBook(resourceVersion: String) =
    ServerBook(
        uuid = CLOUD_NATIVE_BOOK_ID,
        serverId = PARROT_CLOUD_SERVER_ID,
        title = "Cloud title",
        description = null,
        coverUrl = null,
        authors = listOf("Cloud author"),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
        remoteFileAvailability = RemoteFileAvailability.Available,
        mediaResources = listOf(
            MediaResource(
                mediaType = "ebook",
                remoteAvailability = RemoteFileAvailability.Available,
                cloudBookFileId = "cloud-resource-$resourceVersion",
                nativeResourceId = "cloud-resource-$resourceVersion",
            ),
        ),
    )

private class TestGroupProjectionRepository(
    private val activeGroups: List<LibraryBookGroup>,
    groups: List<LibraryBookGroup>,
    private val resolvedGroupId: Pair<LibraryGroupId, LibraryBookGroup>? = null,
) : LibraryGroupProjectionRepository {
    private val groupsState = MutableStateFlow(groups)

    var lastRequestedGroupId: LibraryGroupId? = null
        private set

    override fun observeGroups(profileId: LibraryProfileId): Flow<List<LibraryBookGroup>> =
        groupsState.map { currentGroups ->
            currentGroups.filter { group -> group.profileId == profileId }
        }

    override fun observeActiveGroups(): Flow<List<LibraryBookGroup>> = flowOf(activeGroups)

    fun setGroup(group: LibraryBookGroup) {
        groupsState.value = groupsState.value.map { currentGroup ->
            if (currentGroup.groupId == group.groupId) group else currentGroup
        }
    }

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryBookGroup? {
        lastRequestedGroupId = groupId
        return resolvedGroupId?.takeIf { it.first == groupId }?.second
            ?: groupsState.value.firstOrNull { group ->
                group.profileId == profileId && group.groupId == groupId
            }
    }
}

private class TestRemoteBookOperationAdapter(
    override val adapterId: LibraryAdapterId,
) : LibraryOperationAdapter {
    val requests = mutableListOf<LibraryBookOperationRequest>()
    var bookAvailabilityCount = 0
        private set
    var isBookRemovalAvailable = true

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> = emptyList()

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult = error("Resource operations are unused")

    override suspend fun bookAvailability(
        source: SourceBookRef,
        operation: LibraryBookOperation,
    ): LibraryBookOperationAvailability {
        bookAvailabilityCount += 1
        return LibraryBookOperationAvailability(
            operation,
            isAvailable = isBookRemovalAvailable,
        )
    }

    override suspend fun executeBookOperation(
        request: LibraryBookOperationRequest,
    ): LibraryOperationResult {
        requests += request
        return LibraryOperationResult.Accepted(request.operationId)
    }
}

private class RecordingTransferOperationAdapter(
    override val adapterId: LibraryAdapterId,
    private val transfersBySource: Map<SourceBookRef, List<LibraryTransferProgress>>,
) : LibraryOperationAdapter {
    val cancelledIds = mutableListOf<LibraryTransferId>()

    override fun observeTransfers(source: SourceBookRef): Flow<List<LibraryTransferProgress>> =
        flowOf(transfersBySource[source].orEmpty())

    override suspend fun cancelTransfer(transferId: LibraryTransferId) {
        cancelledIds += transferId
    }

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> = emptyList()

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult = LibraryOperationResult.Rejected("Unused in transfer test")
}

private fun activeTransfer(
    adapterId: LibraryAdapterId,
    source: SourceBookRef,
    transferId: String,
) = LibraryTransferProgress(
    adapterId = adapterId,
    source = source,
    transferId = LibraryTransferId(transferId),
    operation = LibraryOperation.Download,
    status = LibraryTransferStatus.Transferring,
    bytesTransferred = 1,
    totalBytes = 10,
    attemptCount = 1,
    canCancel = true,
    canRetry = false,
)

private class TestAuthenticatedRepositoryProvider(
    private val repository: ServerBooksRepository,
) : AuthenticatedRepositoryProvider {
    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
        flowOf(listOf(repository))

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = listOf(repository)
    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
        repository.takeIf { it.serverId == serverId }
    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = emptyFlow()
    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

private class TestCloudBooksRepository(
    books: List<ServerBook>,
) : ServerBooksRepository {
    override val serverId: String = PARROT_CLOUD_SERVER_ID
    override val libraryAdapterId = LibraryAdapterId(PARROT_CLOUD_SERVER_ID)
    private val bookResults = MutableStateFlow<AppResult<List<ServerBook>>>(Ok(books))

    override suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable =
        SourceAccountIdentity.Portable("parrot-cloud", "cloud-regression-account")

    fun setBooks(books: List<ServerBook>) {
        bookResults.value = Ok(books)
    }

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = bookResults

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = emptyFlow()
    override suspend fun saveBook(book: ServerBook): CompletableResult = error("unused")
    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = error("unused")
}

private object TestCloudLibrarySourceAdapterRegistry : LibrarySourceAdapterRegistry {
    override fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter? =
        TestCloudLibrarySourceAdapter.takeIf { adapter -> adapter.adapterId == adapterId }
}

private object TestCloudLibrarySourceAdapter : ServerBookSourceAdapter {
    override val adapterId = LibraryAdapterId(PARROT_CLOUD_SERVER_ID)

    override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot = error("unused")

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ) = SourceBookSnapshot(
        source = source,
        metadata = SourceBookMetadata(
            title = book.title,
            description = book.description,
            coverReference = book.coverUrl,
            authors = book.authors,
            narrators = book.narrators,
            mediaTypes = book.mediaResources.map { resource -> resource.mediaType },
            publicationDate = book.publicationDate,
        ),
        resources = book.mediaResources.map { mediaResource ->
            val nativeResourceId = requireNotNull(mediaResource.nativeResourceId)
            SourceMediaResource(
                reference = SourceResourceRef(source.key, nativeResourceId),
                mediaType = mediaResource.mediaType,
                format = mediaResource.format,
                sizeBytes = mediaResource.size,
                availability = when (mediaResource.remoteAvailability) {
                    RemoteFileAvailability.Available ->
                        SourceResourceAvailability.AvailableRemotely
                    RemoteFileAvailability.UploadPending, RemoteFileAvailability.Uploading ->
                        SourceResourceAvailability.TransferPending
                    RemoteFileAvailability.None,
                    RemoteFileAvailability.UploadFailed,
                    RemoteFileAvailability.Deleting,
                    -> SourceResourceAvailability.Unavailable
                },
                remoteResourceReference = mediaResource.cloudBookFileId?.let(::RemoteResourceRef),
            )
        },
        status = status,
    )
}

private class TestLibraryGroupsDatabase : LibraryGroupsDatabase {
    private val memberships = MutableStateFlow(emptyList<LibraryGroupMembershipRecord>())
    private val projectionRevision = MutableStateFlow(0)
    private val groups = mutableMapOf<LibraryGroupId, LibraryGroupRecord>()
    private val aliases = mutableMapOf<LibraryGroupId, LibraryGroupId>()

    override fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit> =
        projectionRevision.map { _ -> Unit }

    override suspend fun ensureGroupForSource(
        source: SourceBookRef,
        proposedGroupId: LibraryGroupId,
        createdAt: String,
    ): LibraryGroupId {
        val existingMembership = memberships.value.firstOrNull { membership ->
            membership.source.key == source.key
        }
        if (existingMembership != null) {
            memberships.value = memberships.value.map { membership ->
                if (membership.source.key == source.key) {
                    membership.copy(source = source)
                } else {
                    membership
                }
            }
            return existingMembership.groupId
        }

        groups[proposedGroupId] = LibraryGroupRecord(
            profileId = source.key.profileId,
            groupId = proposedGroupId,
            createdAt = createdAt,
        )
        memberships.value += LibraryGroupMembershipRecord(
            groupId = proposedGroupId,
            source = source,
            origin = LibraryMembershipOrigin.Automatic,
            revision = 1,
        )
        projectionRevision.value++
        return proposedGroupId
    }

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupRecord? = groups[groupId]?.takeIf { group -> group.profileId == profileId }

    override suspend fun getMembership(key: SourceBookKey): LibraryGroupMembershipRecord? =
        memberships.value.firstOrNull { membership -> membership.source.key == key }

    override suspend fun getMemberships(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): List<LibraryGroupMembershipRecord> = memberships.value.filter { membership ->
        membership.source.key.profileId == profileId && membership.groupId == groupId
    }

    override suspend fun getAllMemberships(
        profileId: LibraryProfileId,
    ): List<LibraryGroupMembershipRecord> = memberships.value.filter { membership ->
        membership.source.key.profileId == profileId
    }

    override suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean =
        error("unused")

    override suspend fun applyManualMerge(merge: LibraryManualGroupMerge) = error("unused")
    override suspend fun applyManualSplit(split: LibraryManualGroupSplit) = error("unused")

    override suspend fun applySynchronizedDecision(
        decision: LibrarySynchronizedGroupDecision,
    ): Boolean = error("unused")

    override suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    ) = error("unused")

    override suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    ) {
        aliases[aliasId] = targetId
    }

    override suspend fun resolveGroupId(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupId? {
        val resolvedGroupId = aliases[groupId] ?: groupId
        return groups[resolvedGroupId]?.takeIf { group -> group.profileId == profileId }?.groupId
    }

    override suspend fun addManualSeparation(
        first: SourceBookKey,
        second: SourceBookKey,
        decisionId: String,
        createdAt: String,
    ): Boolean = error("unused")

    override suspend fun getManualSeparations(
        profileId: LibraryProfileId,
    ): List<LibraryManualSeparationRecord> = emptyList()
}

private class TestLibrarySourceSnapshotsDatabase(
    private val groupsDatabase: LibraryGroupsDatabase,
) : LibrarySourceSnapshotsDatabase {
    val snapshots = MutableStateFlow(emptyList<SourceBookSnapshot>())

    override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> =
        snapshots.map { _ -> Unit }

    override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId {
        val groupId = groupsDatabase.ensureGroupForSource(
            source = snapshot.source,
            proposedGroupId = LibraryGroupId("group-${snapshot.source.key.nativeBookId.value}"),
            createdAt = snapshot.status.observedAt.toString(),
        )
        val currentSnapshots = snapshots.value.toMutableList()
        val existingIndex = currentSnapshots.indexOfFirst { current ->
            current.source.key == snapshot.source.key
        }
        if (existingIndex < 0) {
            currentSnapshots += snapshot
        } else {
            currentSnapshots[existingIndex] = snapshot
        }
        snapshots.value = currentSnapshots
        return groupId
    }

    override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? =
        snapshots.value.firstOrNull { snapshot -> snapshot.source.key == source }

    override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
        snapshots.value.filter { snapshot -> snapshot.source.key.profileId == profileId }

    override suspend fun retireDeviceReplica(
        source: SourceBookRef,
        resource: SourceResourceRef,
        expectedStorageRef: DeviceStorageRef,
    ): DeviceReplicaRetirementResult = DeviceReplicaRetirementResult.NotFound

    override suspend fun retireRemoteReplica(
        resource: SourceResourceRef,
        expectedRemoteRef: RemoteResourceRef,
    ): RemoteReplicaRetirementResult = RemoteReplicaRetirementResult.NotFound

    override suspend fun backfillGroups(
        profileId: LibraryProfileId,
        migrationId: String,
        startedAt: String,
        completedAt: String,
    ): LibraryBackfillResult = LibraryBackfillResult(processedSnapshots = 0, isComplete = true)
}

private object EmptyLibraryEvidenceDatabase : LibraryEvidenceDatabase {
    override suspend fun recordResource(resource: SourceResourceRef) = Unit
    override suspend fun getResources(book: SourceBookKey): List<SourceResourceRef> = emptyList()
    override suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean = false
    override suspend fun getActiveEvidence(
        profileId: LibraryProfileId,
    ): List<LibraryEvidenceRecord> = emptyList()
    override suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String) = Unit
}

private object EmptySourceIdentityPromotionDatabase : LibrarySourceIdentityPromotionDatabase {
    override suspend fun promoteUnresolvedSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus = LibrarySourceIdentityPromotionStatus.NotFound

    override suspend fun resolveSourceIdentityAlias(key: SourceBookKey): SourceBookKey? = null

    override suspend fun resolvePromotedParrotCloudSource(
        unresolvedKey: SourceBookKey,
        linkedConnectionId: SourceConnectionId,
        cloudUserId: String,
    ): SourceBookKey? = null

    override suspend fun promoteUnresolvedParrotCloudSources(
        profileId: LibraryProfileId,
        connectionId: SourceConnectionId,
        cloudUserId: String,
    ) = emptyList<com.retro99.database.api.library.LibrarySourceIdentityPromotionResult>()
}

private object EmptyPreferences : Preferences {
    override fun getStringOrNull(key: PreferencesKey): String? = null
    override fun putString(key: PreferencesKey, value: String) = Unit
    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(null)
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean = defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = Unit
    override fun observeBoolean(
        key: PreferencesKey,
        defaultValue: Boolean,
    ): Flow<Boolean> = flowOf(defaultValue)
    override fun getLong(key: PreferencesKey, defaultValue: Long): Long = defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = Unit
    override fun remove(key: PreferencesKey) = Unit
}

private object EmptyAnalytics : Analytics {
    override fun logException(throwable: Throwable, message: String?) = Unit
    override fun logEvent(event: AnalyticsEvent) = Unit
    override fun setUserId(userId: String?) = Unit
}

private object EmptyAuthenticatedRepositoryProvider : AuthenticatedRepositoryProvider {
    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> = emptyFlow()
    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = emptyList()
    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = null
    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = emptyFlow()
    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

private object EmptyServerPositionLocalSource : ServerPositionLocalSource {
    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> = error("unused")
    override suspend fun savePosition(position: ServerPosition): CompletableResult = error("unused")
    override suspend fun savePositionWithSync(
        position: ServerPosition,
        remoteAccountId: String,
    ): CompletableResult = error("unused")
    override suspend fun getAllPositions(): AppResult<List<ServerPosition>> = error("unused")
    override suspend fun deletePosition(bookUuid: String): CompletableResult = error("unused")
    override fun observePosition(bookUuid: String): Flow<ServerPosition?> = emptyFlow()
    override fun observeAllPositions(): Flow<List<ServerPosition>> = emptyFlow()
}

private object EmptyBooksListServerPositionLocalSource : ServerPositionLocalSource by
    EmptyServerPositionLocalSource {
    override fun observeAllPositions(): Flow<List<ServerPosition>> = flowOf(emptyList())
}

private object EmptyReaderSettingsRepository : ReaderSettingsRepository {
    override suspend fun prepareEbook(
        bookUuid: String,
        ebookFilePath: String,
        bookType: BookType,
    ): AppResult<String> = error("unused")
    override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> = emptyFlow()
    override suspend fun saveReaderSettings(
        settings: ReaderSettingsDomainModel,
    ): CompletableResult =
        error("unused")
    override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = emptyFlow()
    override suspend fun saveCustomFonts(
        fonts: List<CustomReaderFontDomainModel>,
    ): CompletableResult =
        error("unused")
    override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean = false
    override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean = false
    override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = null
    override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> = emptyFlow()
    override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
    override fun clearCurrentlyReading() = Unit
    override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> = error("unused")
    override fun observeBookmarks(bookUuid: String): Flow<List<BookmarkDomainModel>> = emptyFlow()
    override suspend fun addBookmark(
        bookmark: BookmarkDomainModel,
    ): CompletableResult = error("unused")
    override suspend fun deleteBookmark(id: String): CompletableResult = error("unused")
    override suspend fun updateBookmarkTitle(id: String, title: String): CompletableResult =
        error("unused")
    override suspend fun updateBookmarkSortOrders(
        orders: List<Pair<String, Int>>,
    ): CompletableResult = error("unused")
}

private object EmptyBookDownloadManager : BookDownloadManager {
    override fun observeDownloadState(bookUuid: String, bookType: BookType): Flow<DownloadState> =
        flowOf(DownloadState.Idle)
    override fun observeAllDownloads(): Flow<Map<DownloadKey, DownloadState>> = flowOf(emptyMap())
    override suspend fun startDownload(
        bookUuid: String,
        bookType: BookType,
        filePath: String,
        bookTitle: String,
        serverId: String,
    ) = Unit
    override suspend fun cancelDownload(bookUuid: String, bookType: BookType) = Unit
    override fun clearError(bookUuid: String, bookType: BookType) = Unit
    override fun getDownloadState(bookUuid: String, bookType: BookType): DownloadState =
        DownloadState.Idle
    override suspend fun deleteCache(bookUuid: String, bookType: BookType): Boolean = true
}

private data class StartedBookDownload(
    val bookUuid: String,
    val bookType: BookType,
    val filePath: String,
    val bookTitle: String,
    val serverId: String,
)

private class RecordingBookDownloadManager : BookDownloadManager {
    private val downloadStates = MutableStateFlow<Map<DownloadKey, DownloadState>>(emptyMap())
    val downloads = mutableListOf<StartedBookDownload>()

    override fun observeDownloadState(
        bookUuid: String,
        bookType: BookType,
    ): Flow<DownloadState> = downloadStates.map { states ->
        states[DownloadKey(bookUuid, bookType)] ?: DownloadState.Idle
    }

    override fun observeAllDownloads(): Flow<Map<DownloadKey, DownloadState>> = downloadStates

    override suspend fun startDownload(
        bookUuid: String,
        bookType: BookType,
        filePath: String,
        bookTitle: String,
        serverId: String,
    ) {
        downloads += StartedBookDownload(bookUuid, bookType, filePath, bookTitle, serverId)
        downloadStates.value = downloadStates.value + (
            DownloadKey(bookUuid, bookType) to DownloadState.Downloading(0f)
        )
    }

    override suspend fun cancelDownload(bookUuid: String, bookType: BookType) {
        downloadStates.value = downloadStates.value + (
            DownloadKey(bookUuid, bookType) to DownloadState.Idle
        )
    }

    override fun clearError(bookUuid: String, bookType: BookType) = Unit

    override fun getDownloadState(bookUuid: String, bookType: BookType): DownloadState =
        downloadStates.value[DownloadKey(bookUuid, bookType)] ?: DownloadState.Idle

    override suspend fun deleteCache(bookUuid: String, bookType: BookType): Boolean {
        downloadStates.value = downloadStates.value + (
            DownloadKey(bookUuid, bookType) to DownloadState.Idle
        )
        return true
    }

    fun markCached(bookUuid: String, bookType: BookType) {
        downloadStates.value = downloadStates.value + (
            DownloadKey(bookUuid, bookType) to DownloadState.Cached
        )
    }
}

private class RecordingAudiobookshelfOperationAdapter(
    private val resources: List<SourceMediaResource>,
    downloadManager: BookDownloadManager,
) : LibraryOperationAdapter {
    override val adapterId = LibraryAdapterId("audiobookshelf")
    private val downloadMediaUseCase = DownloadMediaUseCase(downloadManager)
    val requests = mutableListOf<LibraryOperationRequest>()

    override fun downloadOperationResources(
        resources: List<SourceMediaResource>,
    ): List<SourceMediaResource> = resources.firstOrNull { resource ->
        resource.mediaType == BookType.AUDIOBOOK.value &&
            resource.availability == SourceResourceAvailability.AvailableRemotely &&
            resource.remoteResourceReference != null
    }?.let { representative -> listOf(representative) }.orEmpty()

    override fun supportsReaderCache(
        target: LibraryOperationTarget.RemoteReplica,
    ): Boolean = target.source.key.adapterId == adapterId &&
        target.mediaType == BookType.AUDIOBOOK.value &&
        resources.any { resource -> resource.reference == target.resource }

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> {
        val remoteTarget = target as? LibraryOperationTarget.RemoteReplica
        val isAvailable = remoteTarget != null && resources.any { resource ->
            resource.reference == remoteTarget.resource &&
                resource.remoteResourceReference == remoteTarget.remoteRef &&
                resource.availability == SourceResourceAvailability.AvailableRemotely
        }
        return listOf(
            OperationAvailability(
                operation = LibraryOperation.Download,
                isAvailable = isAvailable,
                reason = if (isAvailable) null else "Audiobookshelf resource is unavailable",
            ),
        )
    }

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult {
        val target = request.target as? LibraryOperationTarget.RemoteReplica
            ?: return LibraryOperationResult.Rejected("Audiobookshelf requires a remote file")
        val selectedResource = resources.firstOrNull { resource ->
            resource.reference == target.resource &&
                resource.remoteResourceReference == target.remoteRef
        } ?: return LibraryOperationResult.Rejected("Audiobookshelf file is unavailable")
        if (request.operation != LibraryOperation.Download ||
            !supportsReaderCache(target) || selectedResource.mediaType != BookType.AUDIOBOOK.value
        ) {
            return LibraryOperationResult.Rejected("Audiobookshelf download is unavailable")
        }

        requests += request
        downloadMediaUseCase(
            bookUuid = request.downloadCacheId ?: target.source.key.nativeBookId.value,
            bookType = BookType.AUDIOBOOK,
            filePath = resources.mapNotNull { resource ->
                resource.remoteResourceReference?.value
            }.joinToString("|"),
            bookTitle = request.displayTitle ?: "Audiobookshelf audiobook",
            serverId = requireNotNull(target.source.connectionId).value,
        )
        return LibraryOperationResult.Accepted(request.operationId)
    }
}

private data class PreparedEbook(
    val bookUuid: String,
    val ebookFilePath: String,
    val bookType: BookType,
)

private class RecordingReaderSettingsRepository :
    ReaderSettingsRepository by EmptyReaderSettingsRepository {
    val preparedBooks = mutableListOf<PreparedEbook>()

    override suspend fun prepareEbook(
        bookUuid: String,
        ebookFilePath: String,
        bookType: BookType,
    ): AppResult<String> {
        preparedBooks += PreparedEbook(bookUuid, ebookFilePath, bookType)
        return Ok("/reader-cache/$bookUuid")
    }
}

private object EmptyFavoritesRepository : FavoritesRepository {
    override suspend fun addToFavorites(bookUuid: String): CompletableResult = error("unused")
    override suspend fun removeFromFavorites(bookUuid: String): CompletableResult = error("unused")
    override fun observeIsFavorite(bookUuid: String): Flow<Boolean> = flowOf(false)
    override suspend fun isFavorite(bookUuid: String): Boolean = false
    override fun observeAllFavoriteUuids(): Flow<Set<String>> = flowOf(emptySet())
}

private object EmptyFileImportManager : FileImportManager {
    override suspend fun importEpubFile(platformFile: PlatformFile) = error("unused")
    override fun getImportedBookPath(uuid: String): String? = null
    override fun deleteImportedBookFiles(uuid: String): Boolean = true
    override fun deleteImportedBookReplicaFile(uuid: String, storageReference: String): Boolean =
        true
    override suspend fun deleteLocalBook(uuid: String): CompletableResult = error("unused")
}

private object EmptyBookFileTransferManager : BookFileTransferManager {
    override fun supportsUpload(serverId: String): Boolean = false
    override fun supportsDownload(serverId: String): Boolean = false
    override fun supportsDeletion(serverId: String): Boolean = false
    override suspend fun enqueueUpload(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String = error("unused")
    override suspend fun backupAll(
        serverId: String,
        rightsAttestation: UploadRightsAttestation,
    ): BackupAllResult = BackupAllResult(0, 0)
    override suspend fun enqueueDownload(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
    ) = error("unused")
    override suspend fun removeDownload(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
    ) = Unit
    override suspend fun deleteRemoteBackup(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
    ) = Unit
    override suspend fun cancelDownloadsForCloudFile(cloudBookFileId: String) = Unit
    override suspend fun invalidateCloudFile(cloudBookFileId: String) = Unit
    override suspend fun cancel(serverId: String, libraryBookId: String) = Unit
    override suspend fun cancelTransfer(transferId: String) = Unit
    override suspend fun retry(transferId: String) = Unit
    override fun observeForBook(
        serverId: String,
        libraryBookId: String,
    ): Flow<List<BookFileTransfer>> = flowOf(emptyList())
}

private object EmptyUploadRightsAttestationRepository : UploadRightsAttestationRepository {
    override suspend fun current(localProfileId: String): UploadAttestationRecord? = null
    override suspend fun record(localProfileId: String): UploadAttestationRecord = error("unused")
    override suspend fun requiresReattestation(localProfileId: String): Boolean = true
}

private object EmptyCloudAccountRepository : CloudAccountRepository {
    override fun observeAuthState(): Flow<CloudAuthState> = flowOf(CloudAuthState.SignedOut)
    override fun currentAuthState(): CloudAuthState = CloudAuthState.SignedOut
    override suspend fun <T> withProfileSession(
        localProfileId: String,
        operation: suspend () -> T,
    ): T = operation()
    override suspend fun register(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudRegistrationResult = error("unused")
    override suspend fun signIn(
        localProfileId: String,
        email: String,
        password: String,
    ): CloudAccount = error("unused")
    override suspend fun signInWithGoogle(localProfileId: String): CloudAccount = error("unused")
    override suspend fun restoreSession(localProfileId: String): CloudAuthState =
        CloudAuthState.SignedOut
    override suspend fun signOut(localProfileId: String) = Unit
    override suspend fun deleteAccount(localProfileId: String) = Unit
}

private object EmptyCloudProfileLinkRepository : CloudProfileLinkRepository {
    override suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink? = null
    override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? = null
    override fun observeForLocalProfile(
        localProfileId: String,
    ): Flow<CloudProfileLink?> = flowOf(null)
    override suspend fun link(localProfileId: String, cloudUserId: String): CloudProfileLinkResult =
        error("unused")
    override suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean) = Unit
    override suspend fun setAutoBackupEnabled(localProfileId: String, enabled: Boolean) = Unit
    override suspend fun setUploadAttestation(
        localProfileId: String,
        cloudUserId: String,
        attestation: UploadAttestationRecord,
    ) = Unit
    override suspend fun deactivate(localProfileId: String) = Unit
    override suspend fun unlink(localProfileId: String) = Unit
}

private object TestUserRegistry : UserRegistry {
    override fun observeAllProfiles(): Flow<List<UserProfile>> = flowOf(emptyList())
    override suspend fun getAllProfiles(): List<UserProfile> = emptyList()
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile =
        error("unused")
    override suspend fun updateProfile(profile: UserProfile) = Unit
    override suspend fun deleteProfile(profileId: String) = Unit
    override suspend fun getProfile(profileId: String): UserProfile? = null
    override fun observeActiveProfile(): Flow<UserProfile?> = flowOf(
        UserProfile(id = PROFILE_ID, name = "Test", createdAt = 0),
    )
    override suspend fun getActiveProfile(): UserProfile? =
        UserProfile(id = PROFILE_ID, name = "Test", createdAt = 0)
    override fun getActiveProfileId(): String = PROFILE_ID
    override suspend fun setActiveProfile(profileId: String) = Unit
    override suspend fun clearActiveProfile() = Unit
    override suspend fun hasProfiles(): Boolean = true
    override fun isProfileActive(): Boolean = true
}

private object EmptyManualGroupingRepository : LibraryManualGroupingRepository {
    override suspend fun mergeMembers(
        profileId: LibraryProfileId,
        members: List<com.retro99.library.domain.grouping.LibraryGroupMemberSelection>,
        preferredMetadataSourceKey: SourceBookKey?,
    ): LibraryGroupId = error("unused")
    override suspend fun splitMembers(
        profileId: LibraryProfileId,
        sourceGroupId: LibraryGroupId,
        movedSourceKeys: List<SourceBookKey>,
    ): LibraryGroupId = error("unused")
}

private object EmptyLibraryGroupPreferenceRepository : LibraryGroupPreferenceRepository {
    override suspend fun setPreferredMediaSource(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
        mediaType: String,
        sourceKey: SourceBookKey,
    ): Boolean = false
}

private object EmptyLibraryReplicaRemovalRepository : LibraryReplicaRemovalRepository {
    override suspend fun remove(
        groupId: LibraryGroupId,
        request: com.retro99.server.api.library.LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult = error("unused")
    override suspend fun recoverPending(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalResult> = emptyList()
}

private object EmptyLibraryUploadTargetRepository : LibraryUploadTargetRepository {
    override suspend fun resolve(
        profileId: LibraryProfileId,
        sources: List<com.retro99.server.api.library.LibraryUploadSource>,
        sourceAvailability: Map<
            com.retro99.server.api.library.LibraryOperationTarget.DeviceReplica,
            com.retro99.server.api.library.SourceResourceAvailability,
        >,
    ) = LibraryUploadTargetResolution(emptyList(), emptyList())
}

private object EmptyLibraryOperationAdapterRegistry : LibraryOperationAdapterRegistry {
    override fun adapter(adapterId: LibraryAdapterId) = null
    override fun adapters() = emptyList<com.retro99.server.api.library.LibraryOperationAdapter>()
}

private class TestOperationAdapterRegistry(
    private val registeredAdapters: List<LibraryOperationAdapter>,
) : LibraryOperationAdapterRegistry {
    override fun adapter(adapterId: LibraryAdapterId): LibraryOperationAdapter? =
        registeredAdapters.firstOrNull { adapter -> adapter.adapterId == adapterId }

    override fun adapters(): List<LibraryOperationAdapter> = registeredAdapters
}

private const val PROFILE_ID = "route-test-profile"
private const val CLOUD_NATIVE_BOOK_ID = "cloud-native-book"
