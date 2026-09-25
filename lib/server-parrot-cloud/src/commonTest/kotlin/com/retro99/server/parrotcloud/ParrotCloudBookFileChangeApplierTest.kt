package com.retro99.server.parrotcloud

import com.retro99.base.result.AppResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.cloudfiles.PendingCloudFileFeedChange
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceProvenance
import com.retro99.database.api.library.LibraryEvidenceProvenanceKind
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibraryGroupMembershipRecord
import com.retro99.database.api.library.LibraryGroupRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryManualSeparationRecord
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ParrotCloudBookFileChangeApplierTest {
    @Test
    fun availableFileEventMapsServerStateToTheLocalMirror() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("available"))

        assertEquals(1, cloudFiles.upserts.size)
        val file = cloudFiles.upserts.single()
        assertEquals("library-book", file.libraryBookId)
        assertEquals("cloud-book", file.cloudBookId)
        assertEquals("cloud-file", file.cloudBookFileId)
        assertEquals("ebook", file.mediaType)
        assertEquals("extras/cover.epub", file.relativePath)
        assertEquals("cover.epub", file.fileName)
        assertEquals("available", file.status)
        assertEquals(321L, file.sizeBytes)
        assertEquals("content-hash", file.contentHash)
        assertEquals("sha-256-v1", file.contentHashAlgorithm)
        assertEquals(8L, file.remoteRevision)
        assertTrue(manager.invalidated.isEmpty())
        assertTrue(cloudFiles.deleted.isEmpty())
    }

    @Test
    fun deletingEventCancelsActiveDownloadsButPreservesCompletedReplicas() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("deleting"))

        assertEquals(listOf("cloud-file"), manager.cancelledDownloads)
        assertTrue(manager.invalidated.isEmpty())
        assertEquals("deleting", cloudFiles.upserts.single().status)
        assertTrue(cloudFiles.deleted.isEmpty())
    }

    @Test
    fun removedEventCancelsActiveDownloadsAndRemovesOnlyItsMirrorEntry() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("removed"))

        assertEquals(listOf("cloud-file"), manager.cancelledDownloads)
        assertTrue(manager.invalidated.isEmpty())
        assertTrue(cloudFiles.upserts.isEmpty())
        assertEquals(listOf(Triple("library-book", "ebook", "extras/cover.epub")), cloudFiles.deleted)
    }

    @Test
    fun removedFileFeedRetiresItsAccountScopedCloudResource() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val snapshots = RecordingLibrarySourceSnapshotsDatabase()
        val applier = applier(
            cloudFiles,
            manager,
            snapshotsDatabase = snapshots,
            repositoryProvider = authenticatedRepositoryProvider(),
        )

        applier.apply(payload("removed"))

        val retired = snapshots.retired.single()
        assertEquals(
            SourceResourceRef(
                book = SourceBookKey(
                    profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID),
                    adapterId = LibraryAdapterId("parrot-cloud"),
                    accountIdentity = SourceAccountIdentity.Portable(
                        "parrot-cloud",
                        "cloud-account",
                    ),
                    nativeBookId = NativeBookId("cloud-book"),
                ),
                nativeResourceId = "cloud-file",
            ),
            retired.first,
        )
        assertEquals(RemoteResourceRef("cloud-file"), retired.second)
        assertEquals(
            listOf(Triple("library-book", "ebook", "extras/cover.epub")),
            cloudFiles.deleted,
        )
    }

    @Test
    fun staleRemovalDoesNotTriggerLifecycleOrTombstone() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val snapshots = RecordingLibrarySourceSnapshotsDatabase()
        cloudFiles.seedFileState(
            CloudBookFileEntity(
                libraryBookId = "library-book",
                cloudBookId = "cloud-book",
                cloudBookFileId = "cloud-file",
                mediaType = "ebook",
                relativePath = "extras/cover.epub",
                fileName = "cover.epub",
                status = "available",
                sizeBytes = 321,
                contentHash = "content-hash",
                contentHashAlgorithm = "sha-256-v1",
                remoteRevision = 8,
                updatedAt = "2026-09-24T00:00:00Z",
            ),
        )
        val applier = applier(cloudFiles, manager, snapshotsDatabase = snapshots)

        applier.apply(payload("removed", remoteRevision = 7))

        assertTrue(manager.cancelledDownloads.isEmpty())
        assertTrue(snapshots.retired.isEmpty())
        assertTrue(cloudFiles.deleted.isEmpty())
    }

    @Test
    fun explicitMandatoryInvalidationRemovesTheCloudReplica() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val applier = applier(cloudFiles, manager)

        applier.apply(payload("removed", "mandatory_invalidation"))

        assertEquals(listOf("cloud-file"), manager.invalidated)
        assertTrue(manager.cancelledDownloads.isEmpty())
    }

    @Test
    fun feedBeforeMetadataIsDurableAndReplaysOnceMetadataArrives() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val libraryBooks = RecordingLibraryBooksDatabase(book = null)
        val applier = applier(cloudFiles, manager, libraryBooks)
        val feedPayload = payload("available")

        applier.apply(feedPayload, feedRevision = 12L)
        applier.apply(feedPayload, feedRevision = 12L)

        assertTrue(cloudFiles.upserts.isEmpty())
        assertEquals(1, cloudFiles.pending.size)
        assertEquals(12L, cloudFiles.pending.single().feedRevision)

        libraryBooks.book = libraryBook
        applier.replayPending()

        assertEquals(listOf("available"), cloudFiles.upserts.map { file -> file.status })
        assertTrue(cloudFiles.pending.isEmpty())
    }

    @Test
    fun queuedFeedEventsReplayInRevisionOrder() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase()
        val manager = RecordingTransferManager()
        val libraryBooks = RecordingLibraryBooksDatabase(book = null)
        val applier = applier(cloudFiles, manager, libraryBooks)

        applier.apply(payload("available", remoteRevision = 8), feedRevision = 8L)
        applier.apply(payload("uploading", remoteRevision = 7), feedRevision = 7L)
        libraryBooks.book = libraryBook

        applier.applyPendingForCloudBook("cloud-book")

        assertEquals(
            listOf("uploading", "available"),
            cloudFiles.upserts.map { file -> file.status },
        )
        assertEquals(8L, cloudFiles.upserts.last().remoteRevision)
        assertTrue(cloudFiles.pending.isEmpty())
    }

    @Test
    fun availableFeedBeforeTransferCompletionIsAssociatedWhenCompletionArrives() = runTest {
        val transfer = completedUploadTransfer().copy(state = "transferring")
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer))
        val evidence = RecordingLibraryEvidenceDatabase()
        val applier = applier(cloudFiles, RecordingTransferManager(), evidenceDatabase = evidence)

        applier.apply(payload("available"))
        assertTrue(evidence.records.isEmpty())

        cloudFiles.replaceTransfer(transfer.copy(state = "completed"))
        applier.apply(payload("available"))

        val completed =
            evidence.records.single().evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals(transfer.transferId, completed.transferId.value)
        assertEquals("cloud-file", completed.destination.nativeResourceId)
    }

    @Test
    fun matchingCompletedTransferRecordsImmutableEndpointEvidence() = runTest {
        val transfer = completedUploadTransfer()
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer))
        val evidence = RecordingLibraryEvidenceDatabase()
        val applier = applier(
            cloudFiles,
            RecordingTransferManager(),
            evidenceDatabase = evidence,
            repositoryProvider = authenticatedRepositoryProvider(),
        )

        applier.apply(payload("available"))
        applier.apply(payload("available"))

        val record = evidence.records.single()
        val completed = record.evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals(transfer.transferId, completed.transferId.value)
        assertEquals("local-book", completed.source.nativeResourceId)
        assertEquals("cloud-file", completed.destination.nativeResourceId)
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            completed.destination.book.accountIdentity,
        )
    }

    @Test
    fun completedTransferReplayWithLocalSnapshotUsesANewEvidenceIdForChangedEndpoint() =
        runTest {
            val profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID)
            val localHash =
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val transfer = completedUploadTransfer().copy(contentHash = localHash)
            val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer))
            val evidence = RecordingLibraryEvidenceDatabase()
            val repositoryProvider = authenticatedRepositoryProvider()
            val manager = RecordingTransferManager()

            applier(
                cloudFiles = cloudFiles,
                manager = manager,
                evidenceDatabase = evidence,
                repositoryProvider = repositoryProvider,
            ).apply(payload("available", contentHash = localHash))

            val original = evidence.activeRecords().single()
            val originalTransfer = assertIs<SourceIdentityEvidence.CompletedTransfer>(
                original.evidence,
            )
            assertIs<SourceAccountIdentity.Unresolved>(originalTransfer.source.book.accountIdentity)

            val localKey = SourceBookKey(
                profileId = profileId,
                adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
                accountIdentity = SourceAccountIdentity.Portable(
                    LocalContentIdentity.BACKEND_ID,
                    LocalContentIdentity.ACCOUNT_ID,
                ),
                nativeBookId = LocalContentIdentity.nativeBookId(localHash),
            )
            val localBookUuid = requireNotNull(transfer.localSourceUuid)
            val localSnapshot = SourceBookSnapshot(
                source = SourceBookRef(
                    key = localKey,
                    connectionId = SourceConnectionId(LOCAL_SERVER_ID),
                ),
                metadata = SourceBookMetadata("Local title"),
                resources = listOf(
                    SourceMediaResource(
                        reference = SourceResourceRef(localKey, localBookUuid),
                        mediaType = "ebook",
                        availability = SourceResourceAvailability.DevicePresent,
                        localStorageReference = DeviceStorageRef("/imports/book.epub"),
                    ),
                ),
                status = SourceSnapshotStatus(
                    observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                    presence = SourcePresence.Present,
                    isAuthoritative = true,
                ),
            )
            val replay = applier(
                cloudFiles = cloudFiles,
                manager = manager,
                evidenceDatabase = evidence,
                snapshotsDatabase = RecordingLibrarySourceSnapshotsDatabase(
                    listOf(localSnapshot),
                ),
                repositoryProvider = repositoryProvider,
            )

            replay.apply(payload("available", contentHash = localHash))
            replay.apply(payload("available", contentHash = localHash))

            assertEquals(listOf(original.evidenceId), evidence.retiredIds)
            val active = evidence.activeRecords()
            assertEquals(1, active.size)
            assertTrue(active.single().evidenceId != original.evidenceId)
            val updatedTransfer = assertIs<SourceIdentityEvidence.CompletedTransfer>(
                active.single().evidence,
            )
            val updatedLocalResource = listOf(
                updatedTransfer.source,
                updatedTransfer.destination,
            ).single { resource ->
                resource.book.adapterId == LibraryAdapterId(LocalContentIdentity.ADAPTER_ID)
            }
            assertEquals(localKey, updatedLocalResource.book)
        }

    @Test
    fun linkedAccountReassociatesPreviouslyUnresolvedTransferEvidence() = runTest {
        val transfer = completedUploadTransfer()
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer))
        val evidence = RecordingLibraryEvidenceDatabase().apply {
            seed(legacyCompletedTransferEvidence(transfer))
        }
        val applier = applier(
            cloudFiles,
            RecordingTransferManager(),
            evidenceDatabase = evidence,
            repositoryProvider = authenticatedRepositoryProvider(),
        )

        applier.apply(payload("available"))

        assertEquals(listOf("completed-transfer:${transfer.transferId}"), evidence.retiredIds)
        val active = evidence.activeRecords()
        assertEquals(1, active.size)
        val completed = active.single().evidence as SourceIdentityEvidence.CompletedTransfer
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            completed.destination.book.accountIdentity,
        )
    }

    @Test
    fun syncReconcilesUnresolvedTransferEvidenceFromPersistedAvailableFileState() = runTest {
        val transfer = completedUploadTransfer()
        val profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID)
        val cloudKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            nativeBookId = NativeBookId("cloud-book"),
        )
        val cloudMembership = LibraryGroupMembershipRecord(
            groupId = LibraryGroupId("group-cloud"),
            source = SourceBookRef(
                key = cloudKey,
                connectionId = SourceConnectionId(PARROT_CLOUD_SERVER_ID),
            ),
        )
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer)).apply {
            seedFileState(
                CloudBookFileEntity(
                    libraryBookId = transfer.libraryBookId,
                    cloudBookId = requireNotNull(transfer.cloudBookId),
                    cloudBookFileId = requireNotNull(transfer.cloudBookFileId),
                    mediaType = transfer.mediaType,
                    relativePath = "extras/cover.epub",
                    fileName = "cover.epub",
                    status = "available",
                    sizeBytes = transfer.sizeBytes,
                    contentHash = requireNotNull(transfer.contentHash),
                    contentHashAlgorithm = requireNotNull(transfer.contentHashAlgorithm),
                    remoteRevision = 8L,
                    updatedAt = "2026-09-24T00:02:00Z",
                ),
            )
        }
        val evidence = RecordingLibraryEvidenceDatabase().apply {
            seed(legacyCompletedTransferEvidence(transfer))
        }
        val applier = applier(
            cloudFiles,
            RecordingTransferManager(),
            evidenceDatabase = evidence,
            libraryGroupsDatabase = RecordingLibraryGroupsDatabase(cloudMembership),
        )

        applier.reconcileCompletedTransferEvidence(UserRegistry.DEFAULT_USER_ID)
        applier.reconcileCompletedTransferEvidence(UserRegistry.DEFAULT_USER_ID)

        assertEquals(listOf("completed-transfer:${transfer.transferId}"), evidence.retiredIds)
        val active = evidence.activeRecords()
        assertEquals(1, active.size)
        val completed = assertIs<SourceIdentityEvidence.CompletedTransfer>(active.single().evidence)
        assertEquals(
            SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            completed.destination.book.accountIdentity,
        )
    }

    @Test
    fun syncDoesNotUseCurrentSessionIdentityForUnpromotedTransferEvidence() = runTest {
        val transfer = completedUploadTransfer()
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer)).apply {
            seedFileState(
                CloudBookFileEntity(
                    libraryBookId = transfer.libraryBookId,
                    cloudBookId = requireNotNull(transfer.cloudBookId),
                    cloudBookFileId = requireNotNull(transfer.cloudBookFileId),
                    mediaType = transfer.mediaType,
                    relativePath = "extras/cover.epub",
                    fileName = "cover.epub",
                    status = "available",
                    sizeBytes = transfer.sizeBytes,
                    contentHash = requireNotNull(transfer.contentHash),
                    contentHashAlgorithm = requireNotNull(transfer.contentHashAlgorithm),
                    remoteRevision = 8L,
                    updatedAt = "2026-09-24T00:02:00Z",
                ),
            )
        }
        val originalEvidence = legacyCompletedTransferEvidence(transfer)
        val evidence = RecordingLibraryEvidenceDatabase().apply { seed(originalEvidence) }
        val applier = applier(
            cloudFiles,
            RecordingTransferManager(),
            evidenceDatabase = evidence,
            repositoryProvider = authenticatedRepositoryProvider(),
        )

        applier.reconcileCompletedTransferEvidence(UserRegistry.DEFAULT_USER_ID)

        assertEquals(listOf(originalEvidence.evidenceId), evidence.retiredIds)
        val active = evidence.activeRecords()
        assertEquals(1, active.size)
        val completed = assertIs<SourceIdentityEvidence.CompletedTransfer>(active.single().evidence)
        assertIs<SourceAccountIdentity.Unresolved>(completed.destination.book.accountIdentity)
    }

    @Test
    fun cloudMembershipRekeysTransferEvidenceToTheLocalHashIdentity() = runTest {
        val profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID)
        val localHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val transfer = completedUploadTransfer().copy(contentHash = localHash)
        val localKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                LocalContentIdentity.BACKEND_ID,
                LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(localHash),
        )
        val importedBookUuid = requireNotNull(transfer.localSourceUuid)
        val localSnapshot = SourceBookSnapshot(
            source = SourceBookRef(
                key = localKey,
                connectionId = SourceConnectionId(LOCAL_SERVER_ID),
            ),
            metadata = SourceBookMetadata("Local title"),
            resources = listOf(
                SourceMediaResource(
                    reference = SourceResourceRef(localKey, importedBookUuid),
                    mediaType = "ebook",
                    availability = SourceResourceAvailability.DevicePresent,
                    localStorageReference = DeviceStorageRef("/imports/book.epub"),
                ),
            ),
            status = SourceSnapshotStatus(
                observedAt = Instant.parse("2026-09-25T00:00:00Z"),
                presence = SourcePresence.Present,
                isAuthoritative = true,
            ),
        )
        val cloudKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "cloud-account"),
            nativeBookId = NativeBookId("cloud-book"),
        )
        val cloudMembership = LibraryGroupMembershipRecord(
            groupId = LibraryGroupId("group-cloud"),
            source = SourceBookRef(
                key = cloudKey,
                connectionId = SourceConnectionId(PARROT_CLOUD_SERVER_ID),
            ),
        )
        val snapshots = RecordingLibrarySourceSnapshotsDatabase(listOf(localSnapshot))
        val evidence = RecordingLibraryEvidenceDatabase().apply {
            seed(legacyCompletedTransferEvidence(transfer))
        }
        val applier = applier(
            cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(transfer)),
            manager = RecordingTransferManager(),
            libraryBooks = RecordingLibraryBooksDatabase(
                book = object : LibraryBookEntity by libraryBook {
                    override val contentHash: String = localHash
                },
            ),
            evidenceDatabase = evidence,
            snapshotsDatabase = snapshots,
            libraryGroupsDatabase = RecordingLibraryGroupsDatabase(cloudMembership),
        )

        applier.apply(payload("available", contentHash = localHash))

        assertEquals(listOf("completed-transfer:${transfer.transferId}"), evidence.retiredIds)
        val completed = assertIs<SourceIdentityEvidence.CompletedTransfer>(
            evidence.activeRecords().single().evidence,
        )
        val localResource = listOf(completed.source, completed.destination).single { resource ->
            resource.book.adapterId == LibraryAdapterId(LocalContentIdentity.ADAPTER_ID)
        }
        val cloudResource = listOf(completed.source, completed.destination).single { resource ->
            resource.book.adapterId == LibraryAdapterId("parrot-cloud")
        }
        assertEquals(localKey, localResource.book)
        assertEquals(importedBookUuid, localResource.nativeResourceId)
        assertEquals(cloudKey, cloudResource.book)
        assertEquals("cloud-file", cloudResource.nativeResourceId)
    }

    @Test
    fun mismatchedFeedHashDoesNotAssociateCompletedTransfer() = runTest {
        val cloudFiles = RecordingCloudFilesDatabase(transfers = listOf(completedUploadTransfer()))
        val evidence = RecordingLibraryEvidenceDatabase()
        val applier = applier(cloudFiles, RecordingTransferManager(), evidenceDatabase = evidence)

        applier.apply(payload("available", contentHash = "different-hash"))

        assertTrue(evidence.records.isEmpty())
    }

    private fun applier(
        cloudFiles: RecordingCloudFilesDatabase,
        manager: RecordingTransferManager,
        libraryBooks: RecordingLibraryBooksDatabase = RecordingLibraryBooksDatabase(libraryBook),
        evidenceDatabase: LibraryEvidenceDatabase? = null,
        snapshotsDatabase: LibrarySourceSnapshotsDatabase? = null,
        repositoryProvider: AuthenticatedRepositoryProvider? = null,
        libraryGroupsDatabase: LibraryGroupsDatabase? = null,
    ): ParrotCloudBookFileChangeApplier = ParrotCloudBookFileChangeApplier(
        cloudFilesDatabase = cloudFiles,
        libraryBooksDatabase = libraryBooks,
        bookFileTransferManager = manager,
        libraryEvidenceDatabase = evidenceDatabase,
        librarySourceSnapshotsDatabase = snapshotsDatabase,
        authenticatedRepositoryProvider = repositoryProvider,
        libraryGroupsDatabase = libraryGroupsDatabase,
    )

    private fun authenticatedRepositoryProvider(): AuthenticatedRepositoryProvider {
        val repository = object : ServerBooksRepository {
            override val serverId = PARROT_CLOUD_SERVER_ID
            override val libraryAdapterId = LibraryAdapterId("parrot-cloud")

            override suspend fun libraryAccountIdentity() =
                SourceAccountIdentity.Portable("parrot-cloud", "cloud-account")

            override fun getBooks(): Flow<AppResult<List<ServerBook>>> = emptyFlow()
            override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = emptyFlow()
            override suspend fun saveBook(book: ServerBook) = error("unused")
            override suspend fun searchBooks(query: String) = error("unused")
        }
        return object : AuthenticatedRepositoryProvider {
            override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
                flowOf(listOf(repository))

            override suspend fun getBooksRepositories(): List<ServerBooksRepository> =
                listOf(repository)

            override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
                repository.takeIf { it.serverId == serverId }

            override suspend fun getReaderRepository(
                serverId: String,
            ): ServerReaderRepository? = null

            override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
                emptyFlow()

            override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
        }
    }

    private fun legacyCompletedTransferEvidence(
        transfer: CloudFileTransferEntity,
    ): LibraryEvidenceRecord {
        val profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID)
        val localBookId = requireNotNull(transfer.localSourceUuid)
        val cloudBookId = requireNotNull(transfer.cloudBookId)
        val cloudFileId = requireNotNull(transfer.cloudBookFileId)
        val localKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("local"),
            accountIdentity = SourceAccountIdentity.Unresolved(SourceConnectionId(LOCAL_SERVER_ID)),
            nativeBookId = NativeBookId(localBookId),
        )
        val cloudKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Unresolved(
                SourceConnectionId(transfer.serverId),
            ),
            nativeBookId = NativeBookId(cloudBookId),
        )
        val localResource = SourceResourceRef(localKey, localBookId)
        val cloudResource = SourceResourceRef(cloudKey, cloudFileId)
        return LibraryEvidenceRecord(
            evidenceId = "completed-transfer:${transfer.transferId}",
            evidence = SourceIdentityEvidence.CompletedTransfer(
                transferId = LibraryTransferId(transfer.transferId),
                source = localResource,
                destination = cloudResource,
            ),
            provenance = LibraryEvidenceProvenance(
                kind = LibraryEvidenceProvenanceKind.Transfer,
                referenceId = transfer.transferId,
            ),
            observedAt = transfer.createdAt,
        )
    }

    private fun payload(
        status: String,
        invalidationReason: String? = null,
        remoteRevision: Long = 8L,
        contentHash: String = "content-hash",
    ) = Json.parseToJsonElement(
        """
        {
          "cloud_book_id":"cloud-book",
          "cloud_book_file_id":"cloud-file",
          "media_type":"ebook",
          "relative_path":"extras/cover.epub",
          "file_name":"cover.epub",
          "status":"$status",
          "size_bytes":321,
          "content_hash":"$contentHash",
          "content_hash_algorithm":"sha-256-v1",
          ${invalidationReason?.let { reason ->
            "\"invalidation_reason\":\"$reason\","
        }.orEmpty()}
          "remote_revision":$remoteRevision
        }
        """.trimIndent(),
    ).jsonObject

    private val libraryBook = object : LibraryBookEntity {
        override val libraryBookId = "library-book"
        override val contentHash: String? = "content-hash"
        override val contentHashAlgorithm = "sha-256-v1"
        override val title = "Title"
        override val author: String? = null
        override val format = "ebook"
        override val cloudBookId = "cloud-book"
    }

    private fun completedUploadTransfer() = CloudFileTransferEntity(
        transferId = "transfer-1",
        serverId = "parrot-cloud",
        direction = "upload",
        libraryBookId = "library-book",
        cloudBookId = "cloud-book",
        cloudBookFileId = "cloud-file",
        mediaType = "ebook",
        localSourceUuid = "local-book",
        stagingPath = null,
        sizeBytes = 321L,
        bytesTransferred = 321L,
        contentHash = "content-hash",
        contentHashAlgorithm = "sha-256-v1",
        uploadId = "upload-1",
        storagePath = "books/book.epub",
        tusUploadUrl = null,
        tusExpiresAt = null,
        rightsAttestation = "{}",
        state = "completed",
        attemptCount = 1,
        nextAttemptAt = null,
        lastError = null,
        createdAt = "2026-09-24T00:00:00Z",
        updatedAt = "2026-09-24T00:01:00Z",
    )

    private class RecordingCloudFilesDatabase(
        transfers: List<CloudFileTransferEntity> = emptyList(),
    ) : CloudFilesDatabase {
        val upserts = mutableListOf<CloudBookFileEntity>()
        val deleted = mutableListOf<Triple<String, String, String>>()
        val pending = mutableListOf<PendingCloudFileFeedChange>()
        private val fileStates = mutableListOf<CloudBookFileEntity>()
        private val transfers = transfers.toMutableList()
        private var nextPendingId = 1L

        fun seedFileState(file: CloudBookFileEntity) {
            fileStates += file
        }

        fun replaceTransfer(transfer: CloudFileTransferEntity) {
            val index = this.transfers.indexOfFirst { candidate ->
                candidate.transferId == transfer.transferId
            }
            if (index < 0) this.transfers += transfer else this.transfers[index] = transfer
        }

        override suspend fun upsertFileState(file: CloudBookFileEntity) {
            upserts += file
            fileStates.removeAll { current ->
                current.libraryBookId == file.libraryBookId &&
                    current.mediaType == file.mediaType &&
                    current.relativePath == file.relativePath
            }
            fileStates += file
        }
        override suspend fun getFileStates(libraryBookId: String): List<CloudBookFileEntity> =
            fileStates.filter { file -> file.libraryBookId == libraryBookId }
        override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = flowOf(upserts.toList())
        override suspend fun deleteFileState(libraryBookId: String, mediaType: String, relativePath: String) {
            deleted += Triple(libraryBookId, mediaType, relativePath)
            fileStates.removeAll { file ->
                file.libraryBookId == libraryBookId && file.mediaType == mediaType &&
                    file.relativePath == relativePath
            }
        }
        override suspend fun insertTransfer(transfer: CloudFileTransferEntity) = Unit
        override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? = null
        override suspend fun updateTransfer(transfer: CloudFileTransferEntity) = Unit
        override suspend fun deleteTransfer(transferId: String) = Unit
        override suspend fun getTransfers(
            serverId: String,
            states: List<String>,
        ): List<CloudFileTransferEntity> = transfers.filter { transfer ->
            transfer.serverId == serverId && transfer.state in states
        }
        override suspend fun getTransfersForCloudFile(
            cloudBookFileId: String,
        ): List<CloudFileTransferEntity> =
            transfers.filter { transfer -> transfer.cloudBookFileId == cloudBookFileId }
        override suspend fun enqueuePendingFileFeedChange(
            cloudBookId: String,
            feedRevision: Long?,
            payloadJson: String,
            receivedAt: String,
        ): PendingCloudFileFeedChange {
            val existing = pending.firstOrNull { change ->
                change.cloudBookId == cloudBookId && change.payloadJson == payloadJson
            }
            if (existing != null) return existing
            return PendingCloudFileFeedChange(
                id = nextPendingId++,
                cloudBookId = cloudBookId,
                feedRevision = feedRevision,
                payloadJson = payloadJson,
                receivedAt = receivedAt,
            ).also(pending::add)
        }
        override suspend fun getPendingFileFeedChanges(
            cloudBookId: String,
        ): List<PendingCloudFileFeedChange> = pending
            .filter { change -> change.cloudBookId == cloudBookId }
            .sortedWith(
                compareBy<PendingCloudFileFeedChange> { change ->
                    change.feedRevision == null
                }.thenBy { change -> change.feedRevision ?: Long.MAX_VALUE }
                    .thenBy { change -> change.id },
            )
        override suspend fun getPendingFileFeedCloudBookIds(): List<String> =
            pending.map { change -> change.cloudBookId }.distinct().sorted()
        override suspend fun deletePendingFileFeedChange(changeId: Long) {
            pending.removeAll { change -> change.id == changeId }
        }
        override fun observeTransfers(serverId: String, libraryBookId: String): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = emptyFlow()
        override suspend fun clearAllData() = Unit
    }

    private class RecordingLibrarySourceSnapshotsDatabase(
        private val snapshots: List<SourceBookSnapshot> = emptyList(),
    ) : LibrarySourceSnapshotsDatabase by EmptyLibrarySourceSnapshotsDatabase {
        val retired = mutableListOf<Pair<SourceResourceRef, RemoteResourceRef>>()

        override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
            snapshots.filter { snapshot -> snapshot.source.key.profileId == profileId }

        override suspend fun retireRemoteReplica(
            resource: SourceResourceRef,
            expectedRemoteRef: RemoteResourceRef,
        ): RemoteReplicaRetirementResult {
            retired += resource to expectedRemoteRef
            return RemoteReplicaRetirementResult.Removed
        }
    }

    private object EmptyLibrarySourceSnapshotsDatabase : LibrarySourceSnapshotsDatabase {
        override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> = emptyFlow()

        override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId =
            error("unused")

        override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? = null

        override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
            emptyList()

        override suspend fun retireDeviceReplica(
            source: SourceBookRef,
            resource: SourceResourceRef,
            expectedStorageRef: DeviceStorageRef,
        ): DeviceReplicaRetirementResult = error("unused")

        override suspend fun retireRemoteReplica(
            resource: SourceResourceRef,
            expectedRemoteRef: RemoteResourceRef,
        ): RemoteReplicaRetirementResult = error("unused")

        override suspend fun backfillGroups(
            profileId: LibraryProfileId,
            migrationId: String,
            startedAt: String,
            completedAt: String,
        ): LibraryBackfillResult = error("unused")
    }

    private class RecordingLibraryBooksDatabase(
        var book: LibraryBookEntity?,
    ) : LibraryBooksDatabase by EmptyLibraryBooksDatabase {
        override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? =
            book?.takeIf { current -> current.cloudBookId == cloudBookId }
    }

    private class RecordingLibraryEvidenceDatabase : LibraryEvidenceDatabase {
        val records = mutableListOf<LibraryEvidenceRecord>()
        val retiredIds = mutableListOf<String>()

        fun seed(record: LibraryEvidenceRecord) {
            records += record
        }

        fun activeRecords(): List<LibraryEvidenceRecord> = records.filter { record ->
            record.evidenceId !in retiredIds
        }

        override suspend fun recordResource(resource: SourceResourceRef) = Unit

        override suspend fun getResources(
            book: SourceBookKey,
        ): List<SourceResourceRef> = emptyList()

        override suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean {
            val existing = records.firstOrNull { current ->
                current.evidenceId == record.evidenceId
            }
            if (existing != null) {
                assertEquals(existing, record)
                return false
            }
            records += record
            return true
        }

        override suspend fun getActiveEvidence(
            profileId: LibraryProfileId,
        ): List<LibraryEvidenceRecord> = activeRecords().filter { record ->
            val completed = record.evidence as? SourceIdentityEvidence.CompletedTransfer
            completed?.source?.book?.profileId == profileId
        }

        override suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String) {
            retiredIds += evidenceId
        }
    }

    private class RecordingTransferManager : BookFileTransferManager {
        val invalidated = mutableListOf<String>()
        val cancelledDownloads = mutableListOf<String>()
        override fun supportsUpload(serverId: String) = false
        override fun supportsDownload(serverId: String) = false
        override fun supportsDeletion(serverId: String) = false
        override suspend fun enqueueUpload(serverId: String, localBookUuid: String, rightsAttestation: UploadRightsAttestation): String = error("unused")
        override suspend fun backupAll(
            serverId: String,
            rightsAttestation: UploadRightsAttestation,
        ): BackupAllResult = error("unused")
        override suspend fun enqueueDownload(serverId: String, libraryBookId: String, mediaType: String): String = error("unused")
        override suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun deleteRemoteBackup(serverId: String, libraryBookId: String, mediaType: String) = Unit
        override suspend fun cancelDownloadsForCloudFile(cloudBookFileId: String) {
            cancelledDownloads += cloudBookFileId
        }
        override suspend fun invalidateCloudFile(cloudBookFileId: String) {
            invalidated += cloudBookFileId
        }
        override suspend fun cancel(serverId: String, libraryBookId: String) = Unit
        override suspend fun cancelTransfer(transferId: String) = Unit
        override suspend fun retry(transferId: String) = Unit
        override fun observeForBook(serverId: String, libraryBookId: String): Flow<List<BookFileTransfer>> = emptyFlow()
    }

    private object EmptyLibraryBooksDatabase : LibraryBooksDatabase {
        override suspend fun upsertLibraryBook(book: LibraryBookEntity) = Unit
        override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = Unit
        override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()
        override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = null
        override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = null
        override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? = null
        override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit
        override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit
        override suspend fun getLocalBookFiles(libraryBookId: String): List<LocalBookFileEntity> = emptyList()
        override suspend fun getLocalBookFileByImportedBookUuid(importedBookUuid: String): LocalBookFileEntity? = null
        override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
    }
}

private class RecordingLibraryGroupsDatabase(
    private val membership: LibraryGroupMembershipRecord,
) : LibraryGroupsDatabase {
    override fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit> = emptyFlow()

    override suspend fun ensureGroupForSource(
        source: SourceBookRef,
        proposedGroupId: LibraryGroupId,
        createdAt: String,
    ): LibraryGroupId = unused()

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupRecord? = unused()

    override suspend fun getMembership(key: SourceBookKey): LibraryGroupMembershipRecord? = unused()

    override suspend fun getMemberships(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): List<LibraryGroupMembershipRecord> = unused()

    override suspend fun getAllMemberships(
        profileId: LibraryProfileId,
    ): List<LibraryGroupMembershipRecord> = listOf(membership).filter { record ->
        record.source.key.profileId == profileId
    }

    override suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean = unused()

    override suspend fun applyManualMerge(merge: LibraryManualGroupMerge) = unused<Unit>()

    override suspend fun applyManualSplit(split: LibraryManualGroupSplit) = unused<Unit>()

    override suspend fun applySynchronizedDecision(
        decision: LibrarySynchronizedGroupDecision,
    ): Boolean = unused()

    override suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    ) = unused<Unit>()

    override suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    ) = unused<Unit>()

    override suspend fun resolveGroupId(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupId? = unused()

    override suspend fun addManualSeparation(
        first: SourceBookKey,
        second: SourceBookKey,
        decisionId: String,
        createdAt: String,
    ): Boolean = unused()

    override suspend fun getManualSeparations(
        profileId: LibraryProfileId,
    ): List<LibraryManualSeparationRecord> = unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}
