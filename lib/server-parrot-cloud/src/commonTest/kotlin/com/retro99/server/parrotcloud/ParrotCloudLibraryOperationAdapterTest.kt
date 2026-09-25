package com.retro99.server.parrotcloud

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.books.domain.usecase.StartBookFileUploadUseCase
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.CloudAccount
import com.retro99.cloudaccount.domain.model.CloudAuthState
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import com.retro99.cloudaccount.domain.usecase.GetCurrentUploadRightsAttestationUseCase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.cloudfiles.PendingCloudFileFeedChange
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryBookOperation
import com.retro99.server.api.library.LibraryBookOperationRequest
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParrotCloudLibraryOperationAdapterTest {
    @Test
    fun proposesExactLocalEpubForTheSignedInLinkedAccount() = runTest {
        val source = uploadSource()
        val fixture = Fixture()

        val candidate = fixture.adapter.proposeUploadDestinations(source).single()

        assertEquals(source, candidate.source)
        assertEquals(LibraryAdapterId(PARROT_CLOUD_SERVER_ID), candidate.proposedBy)
        assertEquals(source.sourceReplica.source, candidate.target.source)
        assertEquals(source.sourceReplica.resource, candidate.target.resource)
        assertEquals(source.sourceReplica.replicaId, candidate.target.sourceReplicaId)
        assertEquals(source.sourceReplica.storageRef, candidate.target.sourceStorageRef)
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-user"),
            candidate.target.destinationAccount,
        )
        assertEquals(
            SourceConnectionId(PARROT_CLOUD_SERVER_ID),
            candidate.target.destinationConnectionId,
        )
    }

    @Test
    fun portableLocalIdentityStillUploadsAndObservesTransfersByImportedUuidAndHash() = runTest {
        val source = uploadSource(portableIdentity = true)
        val fixture = Fixture(contentHash = VALID_HASH)

        val candidate = fixture.adapter.proposeUploadDestinations(source).single()
        val observedTransfers = fixture.adapter
            .observeTransfers(source.sourceReplica.source)
            .first()

        assertEquals("local-book", candidate.target.resource.nativeResourceId)
        assertEquals(emptyList(), observedTransfers)
        assertEquals(
            listOf(PARROT_CLOUD_SERVER_ID to "${LocalContentIdentity.HASH_ALGORITHM}:$VALID_HASH"),
            fixture.transferManager.observedBooks,
        )
    }

    @Test
    fun doesNotProposeChangedReplicaUnsupportedBookOrUnlinkedAccount() = runTest {
        val fixture = Fixture()

        val staleReplica = uploadSource(storageRef = "/imports/replaced.epub")
        val audiobook = uploadSource(format = "audiobook")
        fixture.cloudAccount.authState = CloudAuthState.SignedOut

        assertTrue(fixture.adapter.proposeUploadDestinations(staleReplica).isEmpty())
        assertTrue(fixture.adapter.proposeUploadDestinations(audiobook).isEmpty())
        assertTrue(fixture.adapter.proposeUploadDestinations(uploadSource()).isEmpty())
    }

    @Test
    fun executionQueuesTheExactBookThroughThePersistedAttestedTransferFlow() = runTest {
        val fixture = Fixture()
        val source = uploadSource()
        val candidate = fixture.adapter.proposeUploadDestinations(source).single()
        fixture.uploadAttestationRepository.needsReattestation = true
        val request = uploadRequest(source, candidate.target)

        val result = fixture.adapter.execute(request)

        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(
            listOf(Triple(PARROT_CLOUD_SERVER_ID, "local-book", "profile-a")),
            fixture.transferManager.enqueuedUploads,
        )
        assertEquals(
            UploadRightsAttestation("now", "tos-v1", "rights-v1"),
            fixture.transferManager.attestations.single(),
        )
        assertEquals(1, fixture.uploadAttestationRepository.recordCalls)
        assertEquals(
            listOf("attestation-recorded", "transfer-enqueued"),
            fixture.operationEvents,
        )
    }

    @Test
    fun executionRejectsAccountOrProfileThatIsNoLongerActive() = runTest {
        val fixture = Fixture()
        val source = uploadSource()
        val candidate = fixture.adapter.proposeUploadDestinations(source).single()
        fixture.userRegistry.selectedProfileId = "profile-b"
        val request = uploadRequest(source, candidate.target)

        val result = fixture.adapter.execute(request)

        assertTrue(result is LibraryOperationResult.Rejected)
        assertTrue(fixture.transferManager.enqueuedUploads.isEmpty())
    }

    @Test
    fun executionRequiresTheRightsCheckboxBeforeRecordingAttestationOrEnqueueing() = runTest {
        val fixture = Fixture()
        val source = uploadSource()
        val target = fixture.adapter.proposeUploadDestinations(source).single().target
        fixture.uploadAttestationRepository.needsReattestation = true
        val request = uploadRequest(source, target, userConfirmed = false)

        val result = fixture.adapter.execute(request)

        assertTrue(result is LibraryOperationResult.Rejected)
        assertEquals(0, fixture.uploadAttestationRepository.recordCalls)
        assertTrue(fixture.transferManager.enqueuedUploads.isEmpty())
    }

    @Test
    fun transferObservationEmitsAnInitialEmptyListWithoutAnActiveCloudAccount() = runTest {
        val fixture = Fixture()
        fixture.cloudAccount.authState = CloudAuthState.SignedOut

        val transfers = fixture.adapter
            .observeTransfers(uploadSource().sourceReplica.source)
            .first()

        assertTrue(transfers.isEmpty())
    }

    @Test
    fun unavailableReportsTheUploadGateAndWrongOperationIsRejected() = runTest {
        val fixture = Fixture()
        val source = uploadSource()
        val target = fixture.adapter.proposeUploadDestinations(source).single().target
        fixture.transferManager.uploadSupported = false

        val availability = fixture.adapter.availability(target).single()
        val result = fixture.adapter.execute(
            LibraryOperationRequest(
                operationId = "remove-operation",
                operation = LibraryOperation.RemoveDeviceReplica,
                assetId = source.assetId,
                target = target,
            ),
        )

        assertFalse(availability.isAvailable)
        assertEquals(LibraryOperation.Upload, availability.operation)
        assertTrue(result is LibraryOperationResult.Rejected)
        assertTrue(fixture.transferManager.enqueuedUploads.isEmpty())
    }

    @Test
    fun downloadAvailabilityAndExecutionRetainTheSelectedCloudFileId() = runTest {
        val fixture = Fixture()
        fixture.cloudFilesDatabase.fileStates += listOf(
            cloudFile(cloudBookFileId = "cloud-file-one"),
            cloudFile(cloudBookFileId = "cloud-file-two"),
        )
        val source = cloudSource()
        val target = downloadTarget(source, "cloud-file-two")

        val availability = fixture.adapter.availability(target).single { result ->
            result.operation == LibraryOperation.Download
        }
        val request = LibraryOperationRequest(
            operationId = "download-operation",
            operation = LibraryOperation.Download,
            assetId = MediaAssetId("cloud-asset-two"),
            target = target,
        )
        val result = fixture.adapter.execute(request)

        assertEquals(LibraryOperation.Download, availability.operation)
        assertTrue(availability.isAvailable)
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(
            listOf(
                listOf(
                    PARROT_CLOUD_SERVER_ID,
                    "sha-256-v1:cloud-hash",
                    "cloud-book",
                    "cloud-file-two",
                ),
            ),
            fixture.transferManager.enqueuedDownloads,
        )
    }

    @Test
    fun downloadAvailabilityRejectsUnavailableOrMismatchedCloudFiles() = runTest {
        val unavailableFixture = Fixture()
        val source = cloudSource()
        val target = downloadTarget(source, "cloud-file")
        unavailableFixture.cloudFilesDatabase.fileStates += cloudFile(
            cloudBookFileId = "cloud-file",
            status = "deleting",
        )

        val unavailable = unavailableFixture.adapter.availability(target).single { result ->
            result.operation == LibraryOperation.Download
        }
        val unavailableResult = unavailableFixture.adapter.execute(downloadRequest(target))

        assertFalse(unavailable.isAvailable)
        assertEquals(LibraryOperation.Download, unavailable.operation)
        assertTrue(unavailableResult is LibraryOperationResult.Rejected)
        assertTrue(unavailableFixture.transferManager.enqueuedDownloads.isEmpty())

        val mismatchedFixture = Fixture()
        mismatchedFixture.cloudFilesDatabase.fileStates += cloudFile(
            cloudBookFileId = "cloud-file",
            contentHash = "different-hash",
        )

        val mismatched = mismatchedFixture.adapter.availability(target).single { result ->
            result.operation == LibraryOperation.Download
        }
        val mismatchedResult = mismatchedFixture.adapter.execute(downloadRequest(target))

        assertFalse(mismatched.isAvailable)
        assertTrue(mismatchedResult is LibraryOperationResult.Rejected)
        assertTrue(mismatchedFixture.transferManager.enqueuedDownloads.isEmpty())
    }

    @Test
    fun deleteRemoteReplicaRetainsTheExactCloudFileSelectedFromSameMediaResources() = runTest {
        val fixture = Fixture()
        fixture.cloudFilesDatabase.fileStates += listOf(
            cloudFile(cloudBookFileId = "cloud-file-first", relativePath = ""),
            cloudFile(cloudBookFileId = "cloud-file-selected", relativePath = "selected.epub"),
        )
        val target = downloadTarget(cloudSource(), "cloud-file-selected")
        val availability = fixture.adapter.availability(target).single { result ->
            result.operation == LibraryOperation.DeleteRemoteReplica
        }
        val request = LibraryOperationRequest(
            operationId = "delete-cloud-file",
            operation = LibraryOperation.DeleteRemoteReplica,
            assetId = MediaAssetId("selected-asset"),
            target = target,
            userConfirmed = true,
        )

        val result = fixture.adapter.execute(request)

        assertTrue(availability.isAvailable)
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(
            listOf(
                listOf(
                    PARROT_CLOUD_SERVER_ID,
                    "sha-256-v1:cloud-hash",
                    "cloud-book",
                    "cloud-file-selected",
                    "ebook",
                ),
            ),
            fixture.transferManager.deletedCloudFiles,
        )
    }

    @Test
    fun deleteRemoteReplicaRejectsStaleAccountConnectionBookResourceMediaAndFileIds() = runTest {
        val staleResource = downloadTarget(cloudSource(), "cloud-file")
            .copy(resource = SourceResourceRef(cloudSource().key, "different-resource"))
        val wrongRemoteId = downloadTarget(cloudSource(), "missing-cloud-file")
        val wrongMediaType = downloadTarget(cloudSource(), "cloud-file")
            .copy(mediaType = "audiobook")
        val wrongConnection = downloadTarget(
            cloudSource().copy(connectionId = SourceConnectionId("old-cloud-connection")),
            "cloud-file",
        )
        val wrongAccountSource = cloudSource().copy(
            key = cloudSource().key.copy(
                accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "old-user"),
            ),
        )
        val wrongAccount = downloadTarget(wrongAccountSource, "cloud-file")
        val wrongBook = downloadTarget(
            cloudSource().copy(legacyLibraryBookId = LegacyLibraryBookId("other-book")),
            "cloud-file",
        )

        listOf(
            Fixture() to staleResource,
            Fixture() to wrongRemoteId,
            Fixture() to wrongMediaType,
            Fixture() to wrongConnection,
            Fixture() to wrongAccount,
            Fixture() to wrongBook,
        ).forEach { (fixture, target) ->
            fixture.cloudFilesDatabase.fileStates += cloudFile(cloudBookFileId = "cloud-file")
            val availability = fixture.adapter.availability(target).single { result ->
                result.operation == LibraryOperation.DeleteRemoteReplica
            }
            val result = fixture.adapter.execute(
                LibraryOperationRequest(
                    operationId = "stale-delete",
                    operation = LibraryOperation.DeleteRemoteReplica,
                    assetId = MediaAssetId("cloud-asset"),
                    target = target,
                    userConfirmed = true,
                ),
            )

            assertFalse(availability.isAvailable)
            assertTrue(result is LibraryOperationResult.Rejected)
            assertTrue(fixture.transferManager.deletedCloudFiles.isEmpty())
        }
    }

    @Test
    fun deleteRemoteReplicaRequiresConfirmationAndCurrentLinkedAccount() = runTest {
        val fixture = Fixture()
        fixture.cloudFilesDatabase.fileStates += cloudFile(cloudBookFileId = "cloud-file")
        val target = downloadTarget(cloudSource(), "cloud-file")
        val unconfirmed = fixture.adapter.execute(
            LibraryOperationRequest(
                operationId = "unconfirmed-delete",
                operation = LibraryOperation.DeleteRemoteReplica,
                assetId = MediaAssetId("cloud-asset"),
                target = target,
            ),
        )
        fixture.cloudAccount.authState = CloudAuthState.SignedOut
        val signedOutAvailability = fixture.adapter.availability(target).single { result ->
            result.operation == LibraryOperation.DeleteRemoteReplica
        }
        val signedOutResult = fixture.adapter.execute(
            LibraryOperationRequest(
                operationId = "signed-out-delete",
                operation = LibraryOperation.DeleteRemoteReplica,
                assetId = MediaAssetId("cloud-asset"),
                target = target,
                userConfirmed = true,
            ),
        )

        assertTrue(unconfirmed is LibraryOperationResult.Rejected)
        assertFalse(signedOutAvailability.isAvailable)
        assertTrue(signedOutResult is LibraryOperationResult.Rejected)
        assertTrue(fixture.transferManager.deletedCloudFiles.isEmpty())
    }

    @Test
    fun removeRemoteBookDeletesKnownFilesBeforeQueueingTombstone() = runTest {
        val fixture = Fixture()
        val source = cloudSource()
        fixture.cloudFilesDatabase.fileStates += listOf(
            cloudFile(cloudBookFileId = "cloud-file-one", relativePath = "one.epub"),
            cloudFile(cloudBookFileId = "cloud-file-two", relativePath = "two.epub"),
        )

        val availability = fixture.adapter.bookAvailability(
            source,
            LibraryBookOperation.RemoveRemoteBook,
        )
        val request = removeBookRequest(source)

        val result = fixture.adapter.executeBookOperation(request)

        assertTrue(availability.isAvailable)
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        assertEquals(
            listOf("cloud-file-one", "cloud-file-two"),
            fixture.transferManager.deletedCloudFiles.map { deletion -> deletion[3] },
        )
        assertTrue(fixture.cloudFilesDatabase.fileStates.isEmpty())
        assertEquals(
            listOf(
                "delete-requested:cloud-file-one",
                "file-deleted:cloud-file-one",
                "delete-requested:cloud-file-two",
                "file-deleted:cloud-file-two",
                "tombstone-queued",
            ),
            fixture.operationEvents,
        )
    }

    @Test
    fun removeRemoteBookCanResumeAfterFileDeletionFailureWithoutEarlyTombstone() = runTest {
        val fixture = Fixture()
        val source = cloudSource()
        fixture.cloudFilesDatabase.fileStates += listOf(
            cloudFile(cloudBookFileId = "cloud-file-one", relativePath = "one.epub"),
            cloudFile(cloudBookFileId = "cloud-file-two", relativePath = "two.epub"),
        )
        fixture.transferManager.failingDeleteCloudFileIds += "cloud-file-two"

        val firstResult = fixture.adapter.executeBookOperation(removeBookRequest(source))

        assertTrue(firstResult is LibraryOperationResult.Rejected)
        assertTrue(fixture.syncOutboxDatabase.entries.isEmpty())
        assertEquals(
            listOf("cloud-file-two"),
            fixture.cloudFilesDatabase.fileStates.map { file -> file.cloudBookFileId },
        )
        assertEquals("deleting", fixture.cloudFilesDatabase.fileStates.single().status)
        assertFalse("tombstone-queued" in fixture.operationEvents)

        val retryAvailability = fixture.adapter.bookAvailability(
            source,
            LibraryBookOperation.RemoveRemoteBook,
        )
        fixture.transferManager.failingDeleteCloudFileIds.clear()
        val retryRequest = removeBookRequest(source)
        val retryResult = fixture.adapter.executeBookOperation(retryRequest)

        assertTrue(retryAvailability.isAvailable)
        assertEquals(LibraryOperationResult.Accepted(retryRequest.operationId), retryResult)
        assertTrue(fixture.cloudFilesDatabase.fileStates.isEmpty())
        assertEquals(1, fixture.syncOutboxDatabase.entries.size)
        assertTrue(
            fixture.operationEvents.indexOf("file-deleted:cloud-file-two") <
                fixture.operationEvents.indexOf("tombstone-queued"),
        )
    }

    @Test
    fun removeRemoteBookRejectsMissingOrStaleBookAndProfileState() = runTest {
        val missingBook = Fixture().apply {
            libraryBooksDatabase.book = null
        }
        val changedCloudBook = Fixture().apply {
            libraryBooksDatabase.book = cloudBookEntity(cloudBookId = "different-cloud-book")
        }
        val bookNotSynced = Fixture().apply {
            libraryBooksDatabase.book = cloudBookEntity(remoteRevision = null)
        }
        val wrongProfile = Fixture().apply {
            userRegistry.selectedProfileId = "profile-b"
        }
        val wrongAccountSource = cloudSource().copy(
            key = cloudSource().key.copy(
                accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "old-user"),
            ),
        )
        val cases = listOf(
            missingBook to cloudSource(),
            changedCloudBook to cloudSource(),
            bookNotSynced to cloudSource(),
            wrongProfile to cloudSource(),
            Fixture() to wrongAccountSource,
        )

        cases.forEach { (fixture, source) ->
            val availability = fixture.adapter.bookAvailability(
                source,
                LibraryBookOperation.RemoveRemoteBook,
            )
            val result = fixture.adapter.executeBookOperation(removeBookRequest(source))

            assertFalse(availability.isAvailable)
            assertTrue(result is LibraryOperationResult.Rejected)
            assertTrue(fixture.syncOutboxDatabase.entries.isEmpty())
        }
    }

    @Test
    fun removeRemoteBookRequiresConfirmationAndRevalidatesTheLinkedAccount() = runTest {
        val fixture = Fixture()
        val source = cloudSource()

        val unconfirmed = fixture.adapter.executeBookOperation(
            removeBookRequest(source, userConfirmed = false),
        )
        fixture.cloudAccount.authState = CloudAuthState.SignedOut
        val signedOut = fixture.adapter.executeBookOperation(removeBookRequest(source))

        assertTrue(unconfirmed is LibraryOperationResult.Rejected)
        assertTrue(signedOut is LibraryOperationResult.Rejected)
        assertTrue(fixture.syncOutboxDatabase.entries.isEmpty())
    }

    @Test
    fun removeRemoteBookQueuesTombstoneWithTheCurrentCloudRevision() = runTest {
        val fixture = Fixture()
        val source = cloudSource()
        val availability = fixture.adapter.bookAvailability(
            source,
            LibraryBookOperation.RemoveRemoteBook,
        )
        val request = removeBookRequest(source)

        val result = fixture.adapter.executeBookOperation(request)

        assertTrue(availability.isAvailable)
        assertEquals(LibraryOperationResult.Accepted(request.operationId), result)
        val mutation = fixture.syncOutboxDatabase.entries.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK, mutation.entityType)
        assertEquals("sha-256-v1:cloud-hash", mutation.entityId)
        assertEquals(SyncOutboxEntry.OPERATION_DELETE, mutation.operation)
        assertEquals("cloud-user", mutation.cloudUserId)
        assertEquals(7L, mutation.baseRevision)
        val payload = Json.decodeFromString<ParrotCloudBookPayload>(mutation.payload)
        assertEquals("cloud-book", payload.cloudBookId)
        assertEquals("cloud-hash", payload.contentHash)
        assertEquals(7L, payload.remoteRevision)
    }

    @Test
    fun removeRemoteBookRejectsWhenRevisionChangesAfterConfirmationValidation() = runTest {
        val fixture = Fixture()
        fixture.libraryBooksDatabase.afterNextBookRead = {
            fixture.libraryBooksDatabase.book = cloudBookEntity(remoteRevision = 8L)
        }

        val result = fixture.adapter.executeBookOperation(
            removeBookRequest(cloudSource(), expectedSourceRevision = "7"),
        )

        assertTrue(result is LibraryOperationResult.Rejected)
        assertTrue(fixture.syncOutboxDatabase.entries.isEmpty())
    }

    @Test
    fun repeatedRemoveRemoteBookRequestsCoalescePendingDeleteMutations() = runTest {
        val fixture = Fixture()
        val source = cloudSource()

        val first = fixture.adapter.executeBookOperation(removeBookRequest(source))
        val second = fixture.adapter.executeBookOperation(removeBookRequest(source))

        assertTrue(first is LibraryOperationResult.Accepted)
        assertTrue(second is LibraryOperationResult.Accepted)
        assertEquals(1, fixture.syncOutboxDatabase.entries.size)
        assertEquals(
            SyncOutboxEntry.OPERATION_DELETE,
            fixture.syncOutboxDatabase.entries.single().operation,
        )
    }

    @Test
    fun repeatedRemoveRemoteBookRequestDoesNotQueueAnotherDeleteAfterDispatch() = runTest {
        val fixture = Fixture()
        val source = cloudSource()
        val first = fixture.adapter.executeBookOperation(removeBookRequest(source))
        fixture.syncOutboxDatabase.entries[0] = fixture.syncOutboxDatabase.entries.single().copy(
            state = SyncOutboxEntry.STATE_DISPATCHED,
        )

        val second = fixture.adapter.executeBookOperation(removeBookRequest(source))

        assertTrue(first is LibraryOperationResult.Accepted)
        assertTrue(second is LibraryOperationResult.Accepted)
        assertEquals(1, fixture.syncOutboxDatabase.entries.size)
        assertEquals(
            SyncOutboxEntry.STATE_DISPATCHED,
            fixture.syncOutboxDatabase.entries.single().state,
        )
    }

    @Test
    fun removalKeepsConflictPreservedMutationWhenQueuingANewAttempt() = runTest {
        val fixture = Fixture()
        val source = cloudSource()
        fixture.adapter.executeBookOperation(removeBookRequest(source))
        fixture.syncOutboxDatabase.entries[0] = fixture.syncOutboxDatabase.entries.single().copy(
            state = SyncOutboxEntry.STATE_CONFLICT_PRESERVED,
        )

        val result = fixture.adapter.executeBookOperation(removeBookRequest(source))

        assertTrue(result is LibraryOperationResult.Accepted)
        assertEquals(2, fixture.syncOutboxDatabase.entries.size)
        assertEquals(
            setOf(
                SyncOutboxEntry.STATE_CONFLICT_PRESERVED,
                SyncOutboxEntry.STATE_PENDING,
            ),
            fixture.syncOutboxDatabase.entries.map { entry -> entry.state }.toSet(),
        )
    }

    private class Fixture(contentHash: String = "hash") {
        val userRegistry = FakeUserRegistry()
        val cloudAccount = FakeCloudAccountRepository()
        val operationEvents = mutableListOf<String>()
        val transferManager = RecordingTransferManager(operationEvents)
        val cloudFilesDatabase = FakeCloudFilesDatabase()
        val libraryBooksDatabase = FakeLibraryBooksDatabase(
            ParrotCloudLibraryOperationAdapterTest.cloudBookEntity(),
        )
        val syncOutboxDatabase = RecordingSyncOutboxDatabase(operationEvents)
        private val importedBooksDatabase = FakeImportedBooksDatabase(contentHash)
        private val profileLinks = FakeCloudProfileLinkRepository(
            CloudProfileLink(
                localProfileId = "profile-a",
                cloudUserId = "cloud-user",
                syncEnabled = true,
            ),
        )
        val uploadAttestationRepository = FakeUploadRightsAttestationRepository(operationEvents)
        init {
            transferManager.onEnqueue = { operationEvents += "transfer-enqueued" }
            transferManager.onDeleteCloudFile = { libraryBookId, cloudBookFileId ->
                cloudFilesDatabase.deleteFileStateByCloudBookFileId(
                    libraryBookId,
                    cloudBookFileId,
                )
            }
            transferManager.onDeleteFailure = { libraryBookId, cloudBookFileId ->
                val file = cloudFilesDatabase.getFileStates(libraryBookId).single { value ->
                    value.cloudBookFileId == cloudBookFileId
                }
                cloudFilesDatabase.upsertFileState(file.copy(status = "deleting"))
            }
        }
        private val startUpload = StartBookFileUploadUseCase(
            transferManager = transferManager,
            getCurrentUploadRightsAttestationUseCase =
                GetCurrentUploadRightsAttestationUseCase(uploadAttestationRepository),
        )
        val adapter = ParrotCloudLibraryOperationAdapter(
            importedBooksDatabase = importedBooksDatabase,
            cloudFilesDatabase = cloudFilesDatabase,
            libraryBooksDatabase = libraryBooksDatabase,
            syncOutboxDatabase = syncOutboxDatabase,
            profileLinkRepository = profileLinks,
            cloudAccountRepository = cloudAccount,
            uploadRightsAttestationRepository = uploadAttestationRepository,
            userRegistry = userRegistry,
            transferManager = transferManager,
            startBookFileUploadUseCase = startUpload,
        )
    }

    private class FakeLibraryBooksDatabase(
        var book: LibraryBookEntity?,
    ) : LibraryBooksDatabase {
        var afterNextBookRead: (() -> Unit)? = null

        override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
            this.book = book
        }

        override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) {
            this.book = book
        }

        override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = flowOf(
            listOfNotNull(book),
        )

        override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? {
            val result = book?.takeIf { value -> value.libraryBookId == libraryBookId }
            val afterRead = afterNextBookRead
            afterNextBookRead = null
            afterRead?.invoke()
            return result
        }

        override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? =
            book?.takeIf { value -> value.contentHash == contentHash }

        override suspend fun getLibraryBookByContentHash(
            contentHashAlgorithm: String,
            contentHash: String,
        ): LibraryBookEntity? = book?.takeIf { value ->
            value.contentHashAlgorithm == contentHashAlgorithm && value.contentHash == contentHash
        }

        override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? =
            book?.takeIf { value -> value.cloudBookId == cloudBookId }

        override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit

        override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit

        override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> =
            emptyList()

        override suspend fun getLocalBookFileByImportedBookUuid(
            importedBookUuid: String,
        ): LocalBookFileEntity? = null

        override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
    }

    private class RecordingSyncOutboxDatabase(
        private val operationEvents: MutableList<String>,
    ) : SyncOutboxDatabase {
        val entries = mutableListOf<SyncOutboxEntry>()

        override suspend fun enqueue(entry: SyncOutboxEntry) {
            entries += entry
        }

        override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

        override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = entries

        override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

        override suspend fun markDispatched(mutationId: String) = Unit

        override suspend fun markConflict(mutationId: String, error: String) = Unit

        override suspend fun delete(mutationId: String) = Unit

        override suspend fun deleteByEntityType(entityType: String) = Unit

        override suspend fun recordFailure(
            mutationId: String,
            nextAttemptAt: String,
            error: String,
        ) = Unit

        override suspend fun coalesce(
            entityType: String,
            entityId: String,
            entry: SyncOutboxEntry,
        ) {
            entries.removeAll { value ->
                value.cloudUserId == entry.cloudUserId &&
                    value.entityType == entityType &&
                    value.entityId == entityId &&
                    value.state == SyncOutboxEntry.STATE_PENDING
            }
            entries += entry
            if (
                entry.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK &&
                entry.operation == SyncOutboxEntry.OPERATION_DELETE
            ) {
                operationEvents += "tombstone-queued"
            }
        }

        override suspend fun clearAllData() = Unit
    }

    private class FakeCloudFilesDatabase : CloudFilesDatabase {
        val fileStates = mutableListOf<CloudBookFileEntity>()
        private val transfers = mutableMapOf<String, CloudFileTransferEntity>()
        private val pendingChanges = mutableMapOf<Long, PendingCloudFileFeedChange>()
        private var nextChangeId = 1L

        override suspend fun upsertFileState(file: CloudBookFileEntity) {
            fileStates.removeAll { existing ->
                existing.libraryBookId == file.libraryBookId &&
                    existing.mediaType == file.mediaType &&
                    existing.relativePath == file.relativePath
            }
            fileStates += file
        }

        override suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity> =
            fileStates.filter { file -> file.libraryBookId == libraryBookId }

        override fun observeFileStates(): Flow<List<CloudBookFileEntity>> =
            flowOf(fileStates.toList())

        override suspend fun deleteFileState(
            libraryBookId: String,
            mediaType: String,
            relativePath: String,
        ) {
            fileStates.removeAll { file ->
                file.libraryBookId == libraryBookId &&
                    file.mediaType == mediaType &&
                    file.relativePath == relativePath
            }
        }

        override suspend fun insertTransfer(transfer: CloudFileTransferEntity) {
            transfers[transfer.transferId] = transfer
        }

        override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? =
            transfers[transferId]

        override suspend fun updateTransfer(transfer: CloudFileTransferEntity) {
            transfers[transfer.transferId] = transfer
        }

        override suspend fun deleteTransfer(transferId: String) {
            transfers.remove(transferId)
        }

        override suspend fun getTransfers(
            serverId: String,
            states: List<String>,
        ): List<CloudFileTransferEntity> = transfers.values.filter { transfer ->
            transfer.serverId == serverId && transfer.state in states
        }

        override suspend fun getTransfersForCloudFile(
            cloudBookFileId: String,
        ): List<CloudFileTransferEntity> = transfers.values.filter { transfer ->
            transfer.cloudBookFileId == cloudBookFileId
        }

        override suspend fun enqueuePendingFileFeedChange(
            cloudBookId: String,
            feedRevision: Long?,
            payloadJson: String,
            receivedAt: String,
        ): PendingCloudFileFeedChange {
            val change = PendingCloudFileFeedChange(
                id = nextChangeId++,
                cloudBookId = cloudBookId,
                feedRevision = feedRevision,
                payloadJson = payloadJson,
                receivedAt = receivedAt,
            )
            pendingChanges[change.id] = change
            return change
        }

        override suspend fun getPendingFileFeedChanges(
            cloudBookId: String,
        ): List<PendingCloudFileFeedChange> = pendingChanges.values.filter { change ->
            change.cloudBookId == cloudBookId
        }

        override suspend fun getPendingFileFeedCloudBookIds(): List<String> =
            pendingChanges.values.map { change -> change.cloudBookId }.distinct()

        override suspend fun deletePendingFileFeedChange(changeId: Long) {
            pendingChanges.remove(changeId)
        }

        override fun observeTransfers(
            serverId: String,
            libraryBookId: String,
        ): Flow<List<CloudFileTransferEntity>> = flowOf(
            transfers.values.filter { transfer ->
                transfer.serverId == serverId && transfer.libraryBookId == libraryBookId
            },
        )

        override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> = flowOf(
            transfers.values.filter { transfer -> transfer.state in ACTIVE_TRANSFER_STATES },
        )

        override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> =
            flowOf(transfers.values.toList())

        override suspend fun clearAllData() {
            fileStates.clear()
            transfers.clear()
            pendingChanges.clear()
        }

        private companion object {
            val ACTIVE_TRANSFER_STATES = setOf(
                "pending",
                "transferring",
                "verifying",
                "finalizing",
            )
        }
    }

    private class FakeCloudAccountRepository : CloudAccountRepository {
        var authState: CloudAuthState = CloudAuthState.SignedIn(
            CloudAccount(id = "cloud-user", email = "reader@example.com"),
        )

        override fun observeAuthState(): Flow<CloudAuthState> = flowOf(authState)

        override fun currentAuthState(): CloudAuthState = authState

        override suspend fun <T> withProfileSession(
            localProfileId: String,
            operation: suspend () -> T,
        ): T = operation()

        override suspend fun register(
            localProfileId: String,
            email: String,
            password: String,
        ) = error("Unused")

        override suspend fun signIn(
            localProfileId: String,
            email: String,
            password: String,
        ) = error("Unused")

        override suspend fun signInWithGoogle(localProfileId: String) = error("Unused")

        override suspend fun restoreSession(localProfileId: String) = authState

        override suspend fun signOut(localProfileId: String) = Unit

        override suspend fun deleteAccount(localProfileId: String) = Unit
    }

    private class FakeCloudProfileLinkRepository(
        private val link: CloudProfileLink,
    ) : CloudProfileLinkRepository {
        override suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink? =
            link.takeIf { value -> value.localProfileId == localProfileId }

        override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? =
            link.takeIf { value -> value.cloudUserId == cloudUserId }

        override fun observeForLocalProfile(localProfileId: String): Flow<CloudProfileLink?> =
            flowOf(link.takeIf { value -> value.localProfileId == localProfileId })

        override suspend fun link(
            localProfileId: String,
            cloudUserId: String,
        ): CloudProfileLinkResult = error("Unused")

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

    private class FakeUserRegistry : UserRegistry {
        var selectedProfileId = "profile-a"
        private val profiles = listOf(
            UserProfile(id = "profile-a", name = "Profile A", createdAt = 0L),
            UserProfile(id = "profile-b", name = "Profile B", createdAt = 0L),
        )

        override fun observeAllProfiles(): Flow<List<UserProfile>> = flowOf(profiles)

        override suspend fun getAllProfiles(): List<UserProfile> = profiles

        override suspend fun createProfile(
            id: String?,
            name: String,
            avatarId: Int?,
        ): UserProfile = error("Unused")

        override suspend fun updateProfile(profile: UserProfile) = Unit

        override suspend fun deleteProfile(profileId: String) = Unit

        override suspend fun getProfile(profileId: String): UserProfile? =
            profiles.firstOrNull { profile -> profile.id == profileId }

        override fun observeActiveProfile(): Flow<UserProfile?> =
            flowOf(profiles.first { profile -> profile.id == selectedProfileId })

        override suspend fun getActiveProfile(): UserProfile =
            profiles.first { profile -> profile.id == selectedProfileId }

        override fun getActiveProfileId(): String = selectedProfileId

        override suspend fun setActiveProfile(profileId: String) {
            selectedProfileId = profileId
        }

        override suspend fun clearActiveProfile() = Unit

        override suspend fun hasProfiles(): Boolean = true

        override fun isProfileActive(): Boolean = true
    }

    private class FakeUploadRightsAttestationRepository(
        private val operationEvents: MutableList<String>,
    ) : UploadRightsAttestationRepository {
        private val attestation = UploadAttestationRecord(
            attestedAt = "now",
            tosVersion = "tos-v1",
            attestationVersion = "rights-v1",
        )

        var needsReattestation = false
        var recordCalls = 0

        override suspend fun current(localProfileId: String): UploadAttestationRecord? =
            attestation.takeUnless { needsReattestation }

        override suspend fun record(localProfileId: String): UploadAttestationRecord {
            recordCalls += 1
            operationEvents += "attestation-recorded"
            needsReattestation = false
            return attestation
        }

        override suspend fun requiresReattestation(localProfileId: String): Boolean =
            needsReattestation
    }

    private fun uploadRequest(
        source: LibraryUploadSource,
        target: LibraryOperationTarget.UploadDestination,
        userConfirmed: Boolean = true,
    ) = LibraryOperationRequest(
        operationId = "upload-operation",
        operation = LibraryOperation.Upload,
        assetId = source.assetId,
        target = target,
        userConfirmed = userConfirmed,
    )

    private class FakeImportedBooksDatabase(importedContentHash: String) : ImportedBooksDatabase {
        private val book = object : ImportedBookEntity {
            override val uuid = "local-book"
            override val title = "Title"
            override val author: String? = null
            override val description: String? = null
            override val coverPath: String? = null
            override val filePath = "/imports/local-book.epub"
            override val fileSize = 123L
            override val contentHash: String? = importedContentHash
            override val contentHashAlgorithm: String? = "sha-256-v1"
            override val importedAt = "now"
            override val lastOpenedAt: String? = null
            override val bookType = "ebook"
            override val publicationDate: String? = null
        }

        override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? =
            book.takeIf { value -> value.uuid == uuid }

        override suspend fun upsertImportedBook(book: ImportedBookEntity) = error("Unused")

        override suspend fun upsertImportedBookWithLibraryMapping(
            book: ImportedBookEntity,
            mutation: LibraryBookMutation,
        ) = error("Unused")

        override suspend fun saveRestoredBookWithLibraryMapping(
            book: ImportedBookEntity,
            libraryBook: LibraryBookEntity,
            localBookFile: LocalBookFileEntity,
            transfer: CloudFileTransferEntity,
            position: PositionEntity?,
        ) = error("Unused")

        override fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> = emptyFlow()

        override suspend fun getImportedBookByContentHash(
            contentHash: String,
        ): ImportedBookEntity? =
            book.takeIf { value -> value.contentHash == contentHash }

        override suspend fun deleteImportedBook(uuid: String) = error("Unused")

        override suspend fun deleteAllImportedBooks() = error("Unused")

        override suspend fun getImportedBooksCount(): Int = error("Unused")

        override suspend fun updateLastOpenedAt(
            uuid: String,
            lastOpenedAt: String,
        ) = error("Unused")

        override suspend fun searchImportedBooksByTitle(query: String): List<ImportedBookEntity> =
            error("Unused")
    }

    private class RecordingTransferManager(
        private val operationEvents: MutableList<String>,
    ) : BookFileTransferManager {
        var uploadSupported = true
        var downloadSupported = true
        var onEnqueue: () -> Unit = {}
        var onDeleteCloudFile: suspend (String, String) -> Unit = { _, _ -> }
        var onDeleteFailure: suspend (String, String) -> Unit = { _, _ -> }
        val failingDeleteCloudFileIds = mutableSetOf<String>()
        val enqueuedUploads = mutableListOf<Triple<String, String, String>>()
        val enqueuedDownloads = mutableListOf<List<String>>()
        val deletedCloudFiles = mutableListOf<List<String>>()
        val attestations = mutableListOf<UploadRightsAttestation>()
        val observedBooks = mutableListOf<Pair<String, String>>()

        override fun supportsUpload(serverId: String): Boolean = uploadSupported

        override fun supportsDownload(serverId: String): Boolean = downloadSupported

        override fun supportsDeletion(serverId: String): Boolean = true

        override suspend fun enqueueUpload(
            serverId: String,
            localBookUuid: String,
            rightsAttestation: UploadRightsAttestation,
        ): String {
            onEnqueue()
            enqueuedUploads += Triple(serverId, localBookUuid, "profile-a")
            attestations += rightsAttestation
            return "transfer-1"
        }

        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: UploadRightsAttestation,
        ): BackupAllResult = error("Unused")

        override suspend fun enqueueDownload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ): String = error("Unused")

        override suspend fun enqueueDownloadForCloudFile(
            serverId: String,
            libraryBookId: String,
            cloudBookId: String,
            cloudBookFileId: String,
        ): String {
            enqueuedDownloads += listOf(
                serverId,
                libraryBookId,
                cloudBookId,
                cloudBookFileId,
            )
            return "download-transfer"
        }

        override suspend fun removeDownload(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ) =
            Unit

        override suspend fun deleteRemoteBackup(
            serverId: String,
            libraryBookId: String,
            mediaType: String,
        ) = Unit

        override suspend fun deleteRemoteCloudFile(
            serverId: String,
            libraryBookId: String,
            cloudBookId: String,
            cloudBookFileId: String,
            mediaType: String,
        ) {
            deletedCloudFiles += listOf(
                serverId,
                libraryBookId,
                cloudBookId,
                cloudBookFileId,
                mediaType,
            )
            operationEvents += "delete-requested:$cloudBookFileId"
            if (cloudBookFileId in failingDeleteCloudFileIds) {
                onDeleteFailure(libraryBookId, cloudBookFileId)
                throw IllegalStateException("Cloud file deletion failed")
            }
            onDeleteCloudFile(libraryBookId, cloudBookFileId)
            operationEvents += "file-deleted:$cloudBookFileId"
        }

        override suspend fun cancelDownloadsForCloudFile(cloudBookFileId: String) = Unit

        override suspend fun invalidateCloudFile(cloudBookFileId: String) = Unit

        override suspend fun cancel(serverId: String, libraryBookId: String) = Unit

        override suspend fun cancelTransfer(transferId: String) = Unit

        override suspend fun retry(transferId: String) = Unit

        override fun observeForBook(
            serverId: String,
            libraryBookId: String,
        ): Flow<List<BookFileTransfer>> {
            observedBooks += serverId to libraryBookId
            return flowOf(emptyList())
        }
    }

    private fun cloudSource(): SourceBookRef {
        val key = SourceBookKey(
            profileId = LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId(PARROT_CLOUD_SERVER_ID),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "cloud-user"),
            nativeBookId = NativeBookId("cloud-book"),
        )
        return SourceBookRef(
            key = key,
            connectionId = SourceConnectionId(PARROT_CLOUD_SERVER_ID),
            legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:cloud-hash"),
        )
    }

    private fun removeBookRequest(
        source: SourceBookRef,
        expectedSourceRevision: String = "7",
        userConfirmed: Boolean = true,
    ) = LibraryBookOperationRequest(
        operationId = "remove-cloud-book",
        operation = LibraryBookOperation.RemoveRemoteBook,
        source = source,
        expectedSourceRevision = expectedSourceRevision,
        userConfirmed = userConfirmed,
    )

    private fun downloadTarget(
        source: SourceBookRef,
        cloudBookFileId: String,
    ): LibraryOperationTarget.RemoteReplica {
        val resource = SourceResourceRef(
            book = source.key,
            nativeResourceId = cloudBookFileId,
            revision = "1",
        )
        return LibraryOperationTarget.RemoteReplica(
            source = source,
            resource = resource,
            replicaId = StorageReplicaId(cloudBookFileId),
            remoteRef = RemoteResourceRef(cloudBookFileId),
            mediaType = "ebook",
        )
    }

    private fun downloadRequest(
        target: LibraryOperationTarget.RemoteReplica,
    ) = LibraryOperationRequest(
        operationId = "download-operation",
        operation = LibraryOperation.Download,
        assetId = MediaAssetId("cloud-asset"),
        target = target,
    )

    private fun cloudFile(
        cloudBookFileId: String,
        cloudBookId: String = "cloud-book",
        contentHash: String = "cloud-hash",
        mediaType: String = "ebook",
        relativePath: String = "",
        status: String = "available",
    ) = CloudBookFileEntity(
        libraryBookId = "sha-256-v1:cloud-hash",
        cloudBookId = cloudBookId,
        cloudBookFileId = cloudBookFileId,
        mediaType = mediaType,
        relativePath = relativePath,
        fileName = "book.epub",
        status = status,
        sizeBytes = 123L,
        contentHash = contentHash,
        contentHashAlgorithm = "sha-256-v1",
        remoteRevision = 1L,
        updatedAt = "now",
    )

    private fun uploadSource(
        storageRef: String = "/imports/local-book.epub",
        format: String = "ebook",
        portableIdentity: Boolean = false,
    ): LibraryUploadSource {
        val localConnection = SourceConnectionId(LOCAL_SERVER_ID)
        val sourceKey = SourceBookKey(
            profileId = com.retro99.server.api.library.LibraryProfileId("profile-a"),
            adapterId = LibraryAdapterId("local"),
            accountIdentity = if (portableIdentity) {
                SourceAccountIdentity.Portable(
                    LocalContentIdentity.BACKEND_ID,
                    LocalContentIdentity.ACCOUNT_ID,
                )
            } else {
                SourceAccountIdentity.Unresolved(localConnection)
            },
            nativeBookId = if (portableIdentity) {
                LocalContentIdentity.nativeBookId(VALID_HASH)
            } else {
                NativeBookId("local-book")
            },
        )
        val source = SourceBookRef(sourceKey, localConnection)
        val resource = SourceResourceRef(sourceKey, nativeResourceId = "local-book")
        return LibraryUploadSource(
            assetId = MediaAssetId("asset-1"),
            sourceReplica = LibraryOperationTarget.DeviceReplica(
                source = source,
                resource = resource,
                replicaId = StorageReplicaId("replica-1"),
                storageRef = DeviceStorageRef(storageRef),
            ),
            format = format,
        )
    }

    private data class TestLibraryBookEntity(
        override val libraryBookId: String,
        override val contentHash: String?,
        override val contentHashAlgorithm: String?,
        override val title: String,
        override val author: String?,
        override val format: String,
        override val remoteRevision: Long?,
        override val deletedAt: String?,
        override val cloudBookId: String?,
        override val metadataJson: String?,
    ) : LibraryBookEntity

    private companion object {
        const val VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun cloudBookEntity(
            cloudBookId: String? = "cloud-book",
            remoteRevision: Long? = 7L,
            deletedAt: String? = null,
        ) = TestLibraryBookEntity(
            libraryBookId = "sha-256-v1:cloud-hash",
            contentHash = "cloud-hash",
            contentHashAlgorithm = "sha-256-v1",
            title = "Cloud title",
            author = "Cloud author",
            format = "ebook",
            remoteRevision = remoteRevision,
            deletedAt = deletedAt,
            cloudBookId = cloudBookId,
            metadataJson = null,
        )
    }
}
