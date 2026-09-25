package com.retro99.library.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.ServerType
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.cloudfiles.PendingCloudFileFeedChange
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBookMutation
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibraryGroupMembershipRecord
import com.retro99.database.api.library.LibraryGroupRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryManualSeparationRecord
import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionResult
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.library.domain.grouping.LibraryGroupMemberSelection
import com.retro99.library.domain.grouping.AudiobookshelfPairingActionStatus
import com.retro99.library.domain.operation.LibraryReplicaRemovalRepository
import com.retro99.library.domain.operation.LibraryReplicaRemovalResult
import com.retro99.library.domain.operation.RecoverLibraryDeviceReplicaRemovalsUseCase
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerAuthState
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookListing
import com.retro99.server.api.ServerBookListingCompleteness
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceAdapter
import com.retro99.server.api.library.LibrarySourceAdapterRegistry
import com.retro99.server.api.library.LibrarySourceIdentityPromotionAdapter
import com.retro99.server.api.library.LibrarySourceIdentityBinding
import com.retro99.server.api.library.LibrarySourceIdentityPairing
import com.retro99.server.api.library.LibrarySourceIdentityPairingStatus
import com.retro99.server.api.library.LibrarySourceRecord
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
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
import com.retro99.server.local.LocalLibrarySourceAdapter
import com.retro99.server.parrotcloud.ParrotCloudBooksRepository
import com.retro99.server.parrotcloud.ParrotCloudLibrarySourceAdapter
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LibraryGroupingDataRepositoryTest {
    @Test
    fun legacyRepositoryListingIsPartialByDefault() = runBlocking {
        // Given
        val booksRepository = FakeServerBooksRepository(
            books = listOf(serverBook("book-a", "First book")),
        )

        // When
        val listing = booksRepository.getLibraryListing().first().getOrElse {
            error("Book listing unexpectedly failed")
        }

        // Then
        assertEquals(ServerBookListingCompleteness.Partial, listing.completeness)
        assertEquals(listOf("book-a"), listing.books.map { book -> book.uuid })
    }

    @Test
    fun completeListingMarksListedBooksAuthoritativeAndRemovesOnlyOmittedAccountBooks() =
        runBlocking {
            // Given
            val profileId = LibraryProfileId("profile-a")
            val accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account")
            val listed = source("listed", accountIdentity)
            val omitted = source("omitted", accountIdentity)
            val otherAccountBook = source(
                "other-account",
                SourceAccountIdentity.Portable("test-backend", "other-account"),
            )
            val otherAdapterBook = source(
                "other-adapter",
                accountIdentity,
                adapterId = LibraryAdapterId("other-adapter"),
            )
            val otherProfileBook = source(
                "other-profile",
                accountIdentity,
                profileId = LibraryProfileId("profile-b"),
            )
            val snapshots = FakeLibrarySourceSnapshotsDatabase(
                listOf(
                    snapshot(listed, "Old listed title"),
                    snapshot(omitted, "Omitted book"),
                    snapshot(otherAccountBook, "Other account book"),
                    snapshot(otherAdapterBook, "Other adapter book"),
                    snapshot(otherProfileBook, "Other profile book"),
                ),
            )
            val repository = groupingRepository(
                profileId = profileId,
                snapshots = snapshots,
                sourceRepository = FakeServerBooksRepository(
                    books = listOf(serverBook("listed", "Current title")),
                    listingCompleteness = ServerBookListingCompleteness.Complete,
                    accountIdentity = accountIdentity,
                ),
            )
            val collector = launch { repository.observeGroups(profileId).collect() }

            try {
                // When
                val observed = withTimeout(5_000) {
                    snapshots.snapshotState.first { current ->
                        current.any { value ->
                            value.source.key == listed.key && value.status.isAuthoritative
                        } && current.any { value ->
                            value.source.key == omitted.key &&
                                value.status.presence == SourcePresence.Removed
                        }
                    }
                }

                // Then
                val listedSnapshot = observed.first { value -> value.source.key == listed.key }
                assertEquals("Current title", listedSnapshot.metadata.title)
                assertEquals(SourcePresence.Present, listedSnapshot.status.presence)
                assertTrue(listedSnapshot.status.isAuthoritative)
                assertEquals(
                    SourcePresence.Removed,
                    observed.first { value -> value.source.key == omitted.key }.status.presence,
                )
                assertEquals(
                    SourcePresence.Present,
                    observed.first { value -> value.source.key == otherAccountBook.key }
                        .status.presence,
                )
                assertEquals(
                    SourcePresence.Present,
                    observed.first { value -> value.source.key == otherAdapterBook.key }
                        .status.presence,
                )
                assertEquals(
                    SourcePresence.Present,
                    snapshots.snapshotState.value.first { value ->
                        value.source.key == otherProfileBook.key
                    }.status.presence,
                )
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun identityPromotionConflictFromUnknownAdapterProtectsListedSource() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val connectionId = SourceConnectionId("connection-a")
        val adapterId = LibraryAdapterId("third-party-library")
        val accountIdentity = SourceAccountIdentity.Portable(
            backendId = "third-party-backend",
            accountId = "account-a",
        )
        val unresolvedSource = SourceBookRef(
            key = SourceBookKey(
                profileId = profileId,
                adapterId = adapterId,
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("native-book"),
            ),
            connectionId = connectionId,
        )
        val originalSnapshot = snapshot(unresolvedSource, "Original title")
        val groupsDatabase = FakeLibraryGroupsDatabase(
            profileId = profileId,
            memberships = listOf(
                LibraryGroupMembershipRecord(
                    groupId = LibraryGroupId("group-a"),
                    source = unresolvedSource,
                ),
            ),
        )
        val snapshotsDatabase = FakeLibrarySourceSnapshotsDatabase(listOf(originalSnapshot))
        val promotionDatabase = ConflictSourceIdentityPromotionDatabase()
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = groupsDatabase,
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = promotionDatabase,
            snapshotsDatabase = snapshotsDatabase,
            repositoryProvider = SingleSourceAuthenticatedRepositoryProvider(
                FakeServerBooksRepository(
                    books = listOf(serverBook("native-book", "Updated title")),
                    listingCompleteness = ServerBookListingCompleteness.Partial,
                    accountIdentity = accountIdentity,
                    adapterId = adapterId,
                ),
            ),
            sourceAdapterRegistry = SingleLibrarySourceAdapterRegistry(
                PromotingTestSourceAdapter(adapterId, accountIdentity.backendId),
            ),
            userRegistry = UnusedUserRegistry,
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When: the adapter identifies the account, but promotion conflicts with existing data.
            val attemptedKey = withTimeout(5_000) {
                promotionDatabase.attemptedKeyEvents.receive()
            }

            // Then: conflict is recorded and the partial listing cannot overwrite the source.
            assertEquals(unresolvedSource.key, attemptedKey)
            assertEquals(listOf(unresolvedSource.key), promotionDatabase.attemptedKeys)
            assertEquals(listOf(originalSnapshot), snapshotsDatabase.snapshotState.value)
            assertEquals(0, snapshotsDatabase.savedSnapshots)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun audiobookshelfPairingReturnsConflictingNativeBookIds() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val serverId = "connection-a"
        val adapterId = LibraryAdapterId("audiobookshelf")
        val connectionId = SourceConnectionId(serverId)
        val unresolvedSource = SourceBookRef(
            key = SourceBookKey(
                profileId = profileId,
                adapterId = adapterId,
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("abs-book"),
            ),
            connectionId = connectionId,
        )
        val originalSnapshot = snapshot(unresolvedSource, "Audiobookshelf book")
        val groupId = LibraryGroupId("group-a")
        val groupsDatabase = FakeLibraryGroupsDatabase(
            profileId = profileId,
            memberships = listOf(membership(unresolvedSource, groupId)),
        )
        val snapshotsDatabase = FakeLibrarySourceSnapshotsDatabase(listOf(originalSnapshot))
        val promotionDatabase = ConflictSourceIdentityPromotionDatabase()
        val accountIdentity = SourceAccountIdentity.Portable(
            backendId = "audiobookshelf-instance:paired-device",
            accountId = "abs-account-a",
        )
        val booksRepository = PairingServerBooksRepository(
            delegate = FakeServerBooksRepository(
                books = emptyList(),
                accountIdentity = accountIdentity,
                adapterId = adapterId,
            ),
        )
        val serverRegistry = PairingTestServerRegistry(
            server = audiobookshelfServerConfig(serverId),
            credentials = serverCredentials(serverId),
        )
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = groupsDatabase,
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = promotionDatabase,
            snapshotsDatabase = snapshotsDatabase,
            repositoryProvider = SingleSourceAuthenticatedRepositoryProvider(booksRepository),
            sourceAdapterRegistry = SingleLibrarySourceAdapterRegistry(
                PromotingTestSourceAdapter(adapterId, accountIdentity.backendId),
            ),
            userRegistry = PairingTestUserRegistry(profileId.value),
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
            serverRegistry = serverRegistry,
            cloudProfileLinkRepository = LinkedCloudProfileRepository,
        )

        // When
        val result = repository.createAudiobookshelfPairingCode(profileId, serverId)

        // Then
        assertEquals(AudiobookshelfPairingActionStatus.Completed, result.status)
        assertEquals("test-pairing-code", result.code)
        assertEquals(setOf(NativeBookId("abs-book")), result.conflictingNativeBookIds)
        assertEquals(groupId, groupsDatabase.getMembership(unresolvedSource.key)?.groupId)
        assertEquals(originalSnapshot, snapshotsDatabase.getSnapshot(unresolvedSource.key))
    }

    @Test
    fun pairingRevocationFindsSharedPortableMembershipWithoutItsLocalConnection() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val serverId = "connection-a"
        val backendId = "audiobookshelf-instance:paired-device"
        val binding = LibrarySourceIdentityBinding(
            adapterId = LibraryAdapterId("audiobookshelf"),
            cloudAccountId = "cloud-user",
            sourceAccountId = "abs-account-a",
            backendId = backendId,
        )
        val server = audiobookshelfServerConfig(serverId).copy(
            libraryIdentityBindings = listOf(binding),
        )
        val accountIdentity = SourceAccountIdentity.Portable(backendId, "abs-account-a")
        val pairedSource = source(
            nativeBookId = "abs-book",
            accountIdentity = accountIdentity,
            adapterId = LibraryAdapterId("audiobookshelf"),
        ).copy(connectionId = SourceConnectionId("remote-device-not-configured-here"))
        val groupsDatabase = FakeLibraryGroupsDatabase(
            profileId = profileId,
            memberships = listOf(membership(pairedSource, LibraryGroupId("group-a"))),
        )
        val serverRegistry = PairingTestServerRegistry(server, serverCredentials(serverId))
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = groupsDatabase,
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
            snapshotsDatabase = FakeLibrarySourceSnapshotsDatabase(emptyList()),
            repositoryProvider = EmptyAuthenticatedRepositoryProvider,
            sourceAdapterRegistry = EmptyLibrarySourceAdapterRegistry,
            userRegistry = PairingTestUserRegistry(profileId.value),
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
            serverRegistry = serverRegistry,
            cloudProfileLinkRepository = LinkedCloudProfileRepository,
        )

        // When
        val result = repository.revokeAudiobookshelfPairing(profileId, serverId)

        // Then
        assertEquals(AudiobookshelfPairingActionStatus.HasSharedMemberships, result.status)
        assertEquals(1, result.blockingMembershipCount)
        assertEquals(listOf(binding), serverRegistry.server.libraryIdentityBindings)
        assertTrue(serverRegistry.updatedServers.isEmpty())
    }

    @Test
    fun partialListingUpdatesListedBooksWithoutRemovingOmittedBooks() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account")
        val listed = source("listed", accountIdentity)
        val omitted = source("omitted", accountIdentity)
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(omitted, "Omitted book")),
        )
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = FakeServerBooksRepository(
                books = listOf(serverBook("listed", "Current title")),
                accountIdentity = accountIdentity,
            ),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.any { value -> value.source.key == listed.key }
                }
            }

            // Then
            val listedSnapshot = observed.first { value -> value.source.key == listed.key }
            assertEquals(SourcePresence.Present, listedSnapshot.status.presence)
            assertFalse(listedSnapshot.status.isAuthoritative)
            assertEquals(
                SourcePresence.Present,
                observed.first { value -> value.source.key == omitted.key }.status.presence,
            )
            assertEquals(
                "Omitted book",
                observed.first { value -> value.source.key == omitted.key }.metadata.title,
            )
            assertEquals(
                snapshot(omitted, "Omitted book").resources,
                observed.first { value -> value.source.key == omitted.key }.resources,
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun partialListingTombstoneRemovesOnlyNamedRemoteBookAndRetainsItsSnapshot() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account")
        val removed = source("removed", accountIdentity)
        val omitted = source("omitted", accountIdentity)
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(removed, "Removed by tombstone"), snapshot(omitted, "Still present")),
        )
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = FakeServerBooksRepository(
                books = emptyList(),
                listingCompleteness = ServerBookListingCompleteness.Partial,
                removedNativeBookIds = setOf(NativeBookId("removed")),
                accountIdentity = accountIdentity,
            ),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.first { value -> value.source.key == removed.key }
                        .status.presence == SourcePresence.Removed
                }
            }

            // Then
            val removedSnapshot = observed.first { value -> value.source.key == removed.key }
            assertEquals("Removed by tombstone", removedSnapshot.metadata.title)
            assertEquals(
                snapshot(removed, "Removed by tombstone").resources,
                removedSnapshot.resources,
            )
            assertEquals(
                SourcePresence.Present,
                observed.first { value -> value.source.key == omitted.key }.status.presence,
            )
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun parrotCloudListingTombstoneReconcilesTheExistingSourceSnapshot() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val cloudBookId = "cloud-book-removed"
        val accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", "cloud-user")
        val removed = source(
            nativeBookId = cloudBookId,
            accountIdentity = accountIdentity,
            adapterId = LibraryAdapterId("parrot-cloud"),
        )
        val previousSnapshot = snapshot(removed, "Cloud title")
        val snapshots = FakeLibrarySourceSnapshotsDatabase(listOf(previousSnapshot))
        val cloudBooksRepository = ParrotCloudBooksRepository(
            serverConfig = ServerConfig(
                id = "parrot-cloud",
                name = "Parrot Cloud",
                type = ServerType.ParrotCloud,
                baseUrl = "https://cloud.example",
                addedAt = 0L,
            ),
            libraryBooksDatabase = DeletedCloudBookListingDatabase(cloudBookId),
            syncOutboxDatabase = UnusedSyncOutboxDatabase,
            cloudFilesDatabase = EmptyCloudFilesDatabase,
            importedBooksDatabase = EmptyImportedBooksDatabase,
            cloudProfileLinkRepository = LinkedCloudProfileRepository,
            userRegistry = object : UserRegistry by UnusedUserRegistry {
                override fun getActiveProfileId(): String = profileId.value

                override fun getActiveProfileIdOrDefault(): String = profileId.value
            },
        )
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = cloudBooksRepository,
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val reconciled = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.first { value -> value.source.key == removed.key }.status.presence ==
                        SourcePresence.Removed
                }.first { value -> value.source.key == removed.key }
            }

            // Then
            assertEquals(SourcePresence.Removed, reconciled.status.presence)
            assertTrue(reconciled.status.isAuthoritative)
            assertEquals(previousSnapshot.metadata, reconciled.metadata)
            assertEquals(previousSnapshot.resources, reconciled.resources)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun duplicateLocalHashImportsKeepBothDeviceResourcesInOneSnapshot() = runBlocking {
        val profileId = LibraryProfileId("profile-a")
        val contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val firstBook = localServerBook(
            uuid = "imported-a",
            title = "First imported title",
            path = "/imports/imported-a.epub",
            contentHash = contentHash,
        )
        val secondBook = localServerBook(
            uuid = "imported-b",
            title = "Second imported title",
            path = "/imports/imported-b.epub",
            contentHash = contentHash,
        )
        val snapshots = FakeLibrarySourceSnapshotsDatabase(emptyList())
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = FakeServerBooksRepository(
                books = listOf(secondBook, firstBook),
                listingCompleteness = ServerBookListingCompleteness.Complete,
                accountIdentity = null,
                adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            ),
        )
        val expectedKey = SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
            accountIdentity = SourceAccountIdentity.Portable(
                LocalContentIdentity.BACKEND_ID,
                LocalContentIdentity.ACCOUNT_ID,
            ),
            nativeBookId = LocalContentIdentity.nativeBookId(contentHash),
        )
        val expectedResourceIds = setOf("imported-a", "imported-b")
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.any { snapshot ->
                        snapshot.source.key == expectedKey &&
                            snapshot.status.isAuthoritative &&
                            snapshot.resources.map { resource ->
                                resource.reference.nativeResourceId
                            }.toSet() == expectedResourceIds
                    }
                }
            }

            val snapshot = observed.single { value -> value.source.key == expectedKey }
            assertEquals("First imported title", snapshot.metadata.title)
            assertEquals(
                expectedResourceIds,
                snapshot.resources.map { resource ->
                    resource.reference.nativeResourceId
                }.toSet(),
            )
            assertEquals(
                setOf("/imports/imported-a.epub", "/imports/imported-b.epub"),
                snapshot.resources.mapNotNull { resource ->
                    resource.localStorageReference?.value
                }.toSet(),
            )
            assertTrue(snapshot.resources.all { resource ->
                resource.availability == SourceResourceAvailability.DevicePresent
            })
            assertEquals(2, snapshot.identityEvidence.size)
            assertEquals(1, snapshots.savedSnapshots)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun listingErrorMarksKnownSourceUnknownWithoutRemovingIt() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account")
        val existing = source("existing", accountIdentity)
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(existing, "Existing book")),
        )
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = FakeServerBooksRepository(
                books = emptyList(),
                accountIdentity = accountIdentity,
                listingError = IllegalStateException("offline"),
            ),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.any { value ->
                        value.source.key == existing.key &&
                            value.status.presence == SourcePresence.Unknown
                    }
                }
            }

            // Then
            val existingSnapshot = observed.first { value -> value.source.key == existing.key }
            assertEquals(SourcePresence.Unknown, existingSnapshot.status.presence)
            assertFalse(existingSnapshot.status.isAuthoritative)
            assertEquals("Existing book", existingSnapshot.metadata.title)
            assertEquals(snapshot(existing, "Existing book").resources, existingSnapshot.resources)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun cachedPartialListingThenFetchErrorMarksConnectionSnapshotsUnknown() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account")
        val cached = source("cached", accountIdentity)
        val omitted = source("omitted", accountIdentity)
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(cached, "Cached book"), snapshot(omitted, "Omitted book")),
        )
        val repository = groupingRepository(
            profileId = profileId,
            snapshots = snapshots,
            sourceRepository = FakeServerBooksRepository(
                books = listOf(serverBook("cached", "Cached book")),
                listingEmissions = listOf(
                    Ok(
                        ServerBookListing(
                            books = listOf(serverBook("cached", "Cached book")),
                            completeness = ServerBookListingCompleteness.Partial,
                        ),
                    ),
                    Err(AppError.UnknownError(IllegalStateException("offline"))),
                ),
                accountIdentity = accountIdentity,
            ),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.size == 2 && current.all { value ->
                        value.status.presence == SourcePresence.Unknown
                    }
                }
            }

            // Then
            assertEquals("Cached book", observed.first { value ->
                value.source.key == cached.key
            }.metadata.title)
            assertEquals("Omitted book", observed.first { value ->
                value.source.key == omitted.key
            }.metadata.title)
            assertTrue(observed.all { value -> !value.status.isAuthoritative })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun disconnectedPortableSourceSnapshotBecomesUnknown() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val offlineSource = source(
            nativeBookId = "offline-book",
            accountIdentity = SourceAccountIdentity.Portable("test-backend", "test-account"),
        )
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(offlineSource, "Offline book")),
        )
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = FakeLibraryGroupsDatabase(profileId, emptyList()),
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
            snapshotsDatabase = snapshots,
            repositoryProvider = EmptyAuthenticatedRepositoryProvider,
            sourceAdapterRegistry = EmptyLibrarySourceAdapterRegistry,
            userRegistry = UnusedUserRegistry,
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
        )
        val collector = launch { repository.observeGroups(profileId).collect() }

        try {
            // When
            val observed = withTimeout(5_000) {
                snapshots.snapshotState.first { current ->
                    current.any { value ->
                        value.source.key == offlineSource.key &&
                            value.status.presence == SourcePresence.Unknown
                    }
                }
            }

            // Then
            val offlineSnapshot = observed.first { value ->
                value.source.key == offlineSource.key
            }
            assertFalse(offlineSnapshot.status.isAuthoritative)
            assertEquals("Offline book", offlineSnapshot.metadata.title)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun activeProjectionCollectorRefreshesWithoutSourceListingEmissions() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val first = source("book-a")
        val second = source("book-b")
        val groups = FakeLibraryGroupsDatabase(
            profileId = profileId,
            memberships = listOf(
                membership(first, LibraryGroupId("group-a")),
                membership(second, LibraryGroupId("group-b")),
            ),
        )
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(snapshot(first, "First book"), snapshot(second, "Second book")),
        )
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = groups,
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
            snapshotsDatabase = snapshots,
            repositoryProvider = SingleSourceAuthenticatedRepositoryProvider(
                FakeServerBooksRepository(
                    books = emptyList(),
                    listingEmissions = emptyList(),
                ),
            ),
            sourceAdapterRegistry = TestLibrarySourceAdapterRegistry,
            userRegistry = UnusedUserRegistry,
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
        )
        val projections = Channel<List<LibraryBookGroup>>(Channel.UNLIMITED)
        val collector = launch {
            repository.observeGroups(profileId).collect { groups -> projections.send(groups) }
        }

        try {
            val initialGroups = withTimeout(5_000) { projections.receive() }
            assertEquals(
                setOf(LibraryGroupId("group-a"), LibraryGroupId("group-b")),
                initialGroups.map { group -> group.groupId }.toSet(),
            )

            // When
            val mergedGroupId = repository.mergeMembers(
                profileId = profileId,
                members = listOf(
                    selection(first.key, LibraryGroupId("group-a")),
                    selection(second.key, LibraryGroupId("group-b")),
                ),
                preferredMetadataSourceKey = first.key,
            )
            val mergedGroups = withTimeout(5_000) { projections.receive() }

            // Then
            assertEquals(listOf(mergedGroupId), mergedGroups.map { group -> group.groupId })
            assertEquals(2, mergedGroups.single().members.size)

            // When
            val splitGroupId = repository.splitMembers(
                profileId = profileId,
                sourceGroupId = mergedGroupId,
                movedSourceKeys = listOf(second.key),
            )
            val splitGroups = withTimeout(5_000) { projections.receive() }

            // Then
            assertEquals(
                setOf(mergedGroupId, splitGroupId),
                splitGroups.map { group -> group.groupId }.toSet(),
            )
            assertEquals(2, splitGroups.sumOf { group -> group.members.size })

            // When: a decision arriving from sync changes memberships while listings stay quiet.
            val incomingGroupId = LibraryGroupId("group-incoming")
            val decisionApplied = groups.applySynchronizedDecision(
                LibrarySynchronizedGroupDecision(
                    profileId = profileId,
                    decisionId = "incoming-decision",
                    revision = 1L,
                    operation = LibraryGroupDecisionPayload.MERGE,
                    targetGroupId = incomingGroupId,
                    members = listOf(first.key, second.key),
                    payload = "{}",
                    appliedAt = "2026-09-25T00:00:00Z",
                ),
            )
            val incomingGroups = withTimeout(5_000) { projections.receive() }

            // Then
            assertTrue(decisionApplied)
            assertEquals(listOf(incomingGroupId), incomingGroups.map { group -> group.groupId })
            assertEquals(2, incomingGroups.single().members.size)
            assertEquals(0, snapshots.savedSnapshots)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun activeBooksObserverRecoversPendingRemovalBeforeFirstProjection() = runBlocking {
        // Given
        val profileId = LibraryProfileId(UserRegistry.DEFAULT_USER_ID)
        val source = source(
            nativeBookId = "book-a",
            adapterId = LibraryAdapterId("local"),
            profileId = profileId,
        )
        val groupId = LibraryGroupId("group-a")
        val resource = SourceResourceRef(source.key, "resource-book-a")
        val storageReference = DeviceStorageRef("/imports/book-a.epub")
        val snapshots = FakeLibrarySourceSnapshotsDatabase(
            listOf(
                snapshot(source, "Book").copy(
                    resources = listOf(
                        SourceMediaResource(
                            reference = resource,
                            mediaType = "ebook",
                            availability = SourceResourceAvailability.DevicePresent,
                            localStorageReference = storageReference,
                        ),
                    ),
                ),
            ),
        )
        val recoveryRepository = SnapshotRetiringReplicaRemovalRepository(
            snapshotsDatabase = snapshots,
            source = source,
            resource = resource,
            storageReference = storageReference,
        )
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = FakeLibraryGroupsDatabase(
                profileId = profileId,
                memberships = listOf(membership(source, groupId)),
            ),
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
            snapshotsDatabase = snapshots,
            repositoryProvider = EmptyAuthenticatedRepositoryProvider,
            sourceAdapterRegistry = EmptyLibrarySourceAdapterRegistry,
            userRegistry = ActiveProfileUserRegistry(flowOf(null)),
            recoverLibraryDeviceReplicaRemovalsUseCase =
                RecoverLibraryDeviceReplicaRemovalsUseCase(recoveryRepository),
        )

        // When
        val groups = repository.observeActiveGroups().first()

        // Then
        assertEquals(listOf(profileId), recoveryRepository.recoveredProfiles)
        assertEquals(listOf(groupId), groups.map { group -> group.groupId })
        val recoveredResource = groups.single().members.single().snapshot.resources.single()
        assertEquals(SourceResourceAvailability.Unavailable, recoveredResource.availability)
        assertNull(recoveredResource.localStorageReference)
    }

    @Test
    fun groupProjectionIncludesPersistedPreferredMediaSources() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val groupId = LibraryGroupId("group-a")
        val first = source("book-a")
        val preferred = source("book-b")
        val groups = FakeLibraryGroupsDatabase(
            profileId = profileId,
            memberships = listOf(
                membership(first, groupId),
                membership(preferred, groupId),
            ),
            preferredMediaSourcesByGroup = mapOf(
                groupId to mapOf("ebook" to preferred.key),
            ),
        )
        val repository = LibraryGroupingDataRepository(
            groupsDatabase = groups,
            evidenceDatabase = UnusedEvidenceDatabase,
            sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
            snapshotsDatabase = FakeLibrarySourceSnapshotsDatabase(
                listOf(snapshot(first, "Book"), snapshot(preferred, "Book")),
            ),
            repositoryProvider = EmptyAuthenticatedRepositoryProvider,
            sourceAdapterRegistry = EmptyLibrarySourceAdapterRegistry,
            userRegistry = UnusedUserRegistry,
            recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
        )

        // When
        val projection = repository.getGroup(profileId, groupId)

        // Then
        assertEquals(
            mapOf("ebook" to preferred.key),
            projection?.preferredMediaSourceKeys,
        )
    }

    private fun source(
        nativeBookId: String,
        accountIdentity: SourceAccountIdentity =
            SourceAccountIdentity.Portable("test-backend", "test-account"),
        adapterId: LibraryAdapterId = LibraryAdapterId("test"),
        profileId: LibraryProfileId = LibraryProfileId("profile-a"),
    ): SourceBookRef {
        val connectionId = SourceConnectionId("connection-a")
        val key = SourceBookKey(
            profileId = profileId,
            adapterId = adapterId,
            accountIdentity = accountIdentity,
            nativeBookId = NativeBookId(nativeBookId),
        )
        return SourceBookRef(key = key, connectionId = connectionId)
    }

    private fun groupingRepository(
        profileId: LibraryProfileId,
        snapshots: FakeLibrarySourceSnapshotsDatabase,
        sourceRepository: ServerBooksRepository,
        sourceAdapterRegistry: LibrarySourceAdapterRegistry = TestLibrarySourceAdapterRegistry,
    ) = LibraryGroupingDataRepository(
        groupsDatabase = FakeLibraryGroupsDatabase(profileId, emptyList()),
        evidenceDatabase = UnusedEvidenceDatabase,
        sourceIdentityPromotionDatabase = UnusedSourceIdentityPromotionDatabase,
        snapshotsDatabase = snapshots,
        repositoryProvider = SingleSourceAuthenticatedRepositoryProvider(sourceRepository),
        sourceAdapterRegistry = sourceAdapterRegistry,
        userRegistry = UnusedUserRegistry,
        recoverLibraryDeviceReplicaRemovalsUseCase = noPendingReplicaRecoveries(),
    )

    private fun noPendingReplicaRecoveries() =
        RecoverLibraryDeviceReplicaRemovalsUseCase(NoPendingReplicaRemovalRepository)

    private fun serverBook(uuid: String, title: String) = ServerBook(
        uuid = uuid,
        serverId = "connection-a",
        title = title,
        description = null,
        coverUrl = null,
        authors = emptyList(),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = true,
        hasAudiobook = false,
        hasReadaloud = false,
    )

    private fun localServerBook(
        uuid: String,
        title: String,
        path: String,
        contentHash: String,
    ) = serverBook(uuid, title).copy(
        isLocal = true,
        libraryBookId = "${LocalContentIdentity.HASH_ALGORITHM}:$contentHash",
        contentHash = contentHash,
        contentHashAlgorithm = LocalContentIdentity.HASH_ALGORITHM,
        ebookFilepath = path,
        mediaResources = listOf(
            MediaResource(
                mediaType = "ebook",
                localPath = path,
                contentHash = contentHash,
                contentHashAlgorithm = LocalContentIdentity.HASH_ALGORITHM,
                nativeResourceId = uuid,
                format = "epub",
            ),
        ),
    )

    private fun membership(
        source: SourceBookRef,
        groupId: LibraryGroupId,
    ) = LibraryGroupMembershipRecord(
        groupId = groupId,
        source = source,
        origin = LibraryMembershipOrigin.Backfill,
    )

    private fun selection(
        sourceKey: SourceBookKey,
        groupId: LibraryGroupId,
    ) = LibraryGroupMemberSelection(
        sourceKey = sourceKey,
        expectedGroupId = groupId,
    )

    private fun snapshot(source: SourceBookRef, title: String) = SourceBookSnapshot(
        source = source,
        metadata = SourceBookMetadata(title),
        resources = listOf(
            SourceMediaResource(
                reference = SourceResourceRef(
                    source.key,
                    "resource-${source.key.nativeBookId.value}",
                ),
                mediaType = "ebook",
                availability = SourceResourceAvailability.Unavailable,
            ),
        ),
        status = SourceSnapshotStatus(
            observedAt = kotlin.time.Instant.parse("2026-09-25T00:00:00Z"),
            presence = SourcePresence.Present,
            isAuthoritative = false,
        ),
    )
}

private class FakeLibraryGroupsDatabase(
    private val profileId: LibraryProfileId,
    memberships: List<LibraryGroupMembershipRecord>,
    preferredMediaSourcesByGroup: Map<LibraryGroupId, Map<String, SourceBookKey>> = emptyMap(),
) : LibraryGroupsDatabase {
    private val memberships = MutableStateFlow(memberships)
    private val groupRecords = mutableMapOf<LibraryGroupId, LibraryGroupRecord>().apply {
        memberships.map { member -> member.groupId }.distinct().forEach { groupId ->
            put(
                groupId,
                LibraryGroupRecord(
                    profileId = profileId,
                    groupId = groupId,
                    createdAt = "2026-09-25T00:00:00Z",
                    preferredMediaSourceKeys = preferredMediaSourcesByGroup[groupId].orEmpty(),
                ),
            )
        }
    }

    override fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit> =
        memberships.map { Unit }

    override suspend fun ensureGroupForSource(
        source: SourceBookRef,
        proposedGroupId: LibraryGroupId,
        createdAt: String,
    ): LibraryGroupId = unused()

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupRecord? = groupRecords[groupId]

    override suspend fun getMembership(key: SourceBookKey): LibraryGroupMembershipRecord? =
        memberships.value.firstOrNull { member -> member.source.key == key }

    override suspend fun getMemberships(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): List<LibraryGroupMembershipRecord> = memberships.value.filter { member ->
        member.groupId == groupId
    }

    override suspend fun getAllMemberships(
        profileId: LibraryProfileId,
    ): List<LibraryGroupMembershipRecord> = memberships.value

    override suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean = unused()

    override suspend fun applyManualMerge(merge: LibraryManualGroupMerge) {
        groupRecords[merge.newGroupId] = LibraryGroupRecord(
            profileId = merge.profileId,
            groupId = merge.newGroupId,
            createdAt = merge.appliedAt,
            preferredMetadataSourceKey = merge.preferredMetadataSourceKey,
        )
        val selectedKeys = merge.members.map { member -> member.sourceKey }.toSet()
        memberships.value = memberships.value.map { member ->
            if (member.source.key !in selectedKeys) {
                member
            } else {
                member.copy(
                    groupId = merge.newGroupId,
                    origin = LibraryMembershipOrigin.Manual,
                    decisionId = merge.decisionId,
                )
            }
        }
    }

    override suspend fun applyManualSplit(split: LibraryManualGroupSplit) {
        groupRecords[split.newGroupId] = LibraryGroupRecord(
            profileId = split.profileId,
            groupId = split.newGroupId,
            createdAt = split.appliedAt,
        )
        val movedKeys = split.movedSourceKeys.toSet()
        memberships.value = memberships.value.map { member ->
            if (member.source.key !in movedKeys) {
                member
            } else {
                member.copy(
                    groupId = split.newGroupId,
                    origin = LibraryMembershipOrigin.Manual,
                    decisionId = split.decisionId,
                )
            }
        }
    }

    override suspend fun applySynchronizedDecision(
        decision: LibrarySynchronizedGroupDecision,
    ): Boolean {
        check(decision.profileId == profileId)
        check(decision.operation == LibraryGroupDecisionPayload.MERGE)
        check(decision.members.size >= 2)

        groupRecords[decision.targetGroupId] = LibraryGroupRecord(
            profileId = decision.profileId,
            groupId = decision.targetGroupId,
            createdAt = decision.appliedAt,
            preferredMetadataSourceKey = decision.preferredMetadataSourceKey,
            preferredMediaSourceKeys = decision.preferredMediaSourceKeys,
        )
        val selectedKeys = decision.members.toSet()
        memberships.value = memberships.value.map { member ->
            if (member.source.key !in selectedKeys) {
                member
            } else {
                member.copy(
                    groupId = decision.targetGroupId,
                    origin = LibraryMembershipOrigin.Manual,
                    revision = decision.revision,
                    decisionId = decision.decisionId,
                )
            }
        }
        return true
    }

    override suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    ): Unit = unused()

    override suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    ): Unit = unused()

    override suspend fun resolveGroupId(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupId? = groupRecords[groupId]?.groupId

    override suspend fun addManualSeparation(
        first: SourceBookKey,
        second: SourceBookKey,
        decisionId: String,
        createdAt: String,
    ): Boolean = unused()

    override suspend fun getManualSeparations(
        profileId: LibraryProfileId,
    ): List<LibraryManualSeparationRecord> = emptyList()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private class FakeServerBooksRepository(
    private val books: List<ServerBook>,
    private val listingCompleteness: ServerBookListingCompleteness? = null,
    private val accountIdentity: SourceAccountIdentity? =
        SourceAccountIdentity.Portable("test-backend", "test-account"),
    adapterId: LibraryAdapterId = LibraryAdapterId("test"),
    private val listingError: Throwable? = null,
    private val listingEmissions: List<AppResult<ServerBookListing>>? = null,
    private val removedNativeBookIds: Set<NativeBookId> = emptySet(),
) : ServerBooksRepository {
    override val serverId: String = "connection-a"
    override val libraryAdapterId = adapterId

    override suspend fun libraryAccountIdentity(): SourceAccountIdentity.Portable? =
        accountIdentity as? SourceAccountIdentity.Portable

    override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(books))

    override fun getLibraryListing(): Flow<AppResult<ServerBookListing>> {
        listingEmissions?.let { emissions ->
            return flowOf(*emissions.toTypedArray())
        }
        listingError?.let { error ->
            return flowOf(Err(AppError.UnknownError(error)))
        }
        if (listingCompleteness == null) return super.getLibraryListing()
        return flowOf(
            Ok(
                ServerBookListing(
                    books = books,
                    completeness = listingCompleteness,
                    removedNativeBookIds = removedNativeBookIds,
                ),
            ),
        )
    }

    override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = emptyFlow()

    override suspend fun saveBook(book: ServerBook): CompletableResult =
        error("This method is unused in the test")

    override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = Ok(emptyList())
}

private class PairingServerBooksRepository(
    private val delegate: ServerBooksRepository,
) : ServerBooksRepository by delegate,
    LibrarySourceIdentityPairing {
    override fun observePairingStatus() = flowOf(LibrarySourceIdentityPairingStatus.Unpaired)

    override suspend fun createPairingCode(): String = "test-pairing-code"

    override suspend fun importPairingCode(code: String): Unit = error("Not used in this test")

}

private class PairingTestServerRegistry(
    server: ServerConfig,
    private val credentials: ServerCredentials,
) : ServerRegistry {
    var server = server
        private set
    val updatedServers = mutableListOf<ServerConfig>()
    private val servers = MutableStateFlow(listOf(server))
    private val authState = ServerAuthState.Authenticated(
        serverId = server.id,
        username = credentials.username,
        authenticatedAt = 0L,
    )

    override fun observeAllServers(): Flow<List<ServerConfig>> = servers

    override suspend fun getAllServers(): List<ServerConfig> = servers.value

    override suspend fun addServer(
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = error("Server creation is unused in this test")

    override suspend fun addServerWithId(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
    ): ServerConfig = error("Server creation is unused in this test")

    override suspend fun updateServer(config: ServerConfig) {
        updatedServers += config
        server = config
        servers.value = listOf(config)
    }

    override suspend fun removeServer(serverId: String): Unit =
        error("Server removal is unused in this test")

    override suspend fun getServer(serverId: String): ServerConfig? =
        server.takeIf { candidate -> candidate.id == serverId }

    override fun observeAllAuthStates(): Flow<Map<String, ServerAuthState>> =
        flowOf(mapOf(server.id to authState))

    override fun observeAuthState(serverId: String): Flow<ServerAuthState> = flowOf(authState)

    override suspend fun isAuthenticated(serverId: String): Boolean = serverId == server.id

    override fun observeAuthenticatedServers(): Flow<List<ServerConfig>> = flowOf(listOf(server))

    override suspend fun getAuthenticatedServers(): List<ServerConfig> = listOf(server)

    override suspend fun saveCredentials(credentials: ServerCredentials): Unit =
        error("Credential updates are unused in this test")

    override suspend fun getCredentials(serverId: String): ServerCredentials? =
        credentials.takeIf { candidate -> candidate.serverId == serverId }

    override suspend fun clearCredentials(serverId: String): Unit =
        error("Credential clearing is unused in this test")

    override suspend fun clearAllCredentials(): Unit =
        error("Credential clearing is unused in this test")

    override suspend fun deactivateServer(serverId: String): Unit =
        error("Server deactivation is unused in this test")
}

private class PairingTestUserRegistry(
    private val activeProfileId: String,
) : UserRegistry by UnusedUserRegistry {
    override fun getActiveProfileId(): String = activeProfileId

    override fun getActiveProfileIdOrDefault(): String = activeProfileId
}

private fun audiobookshelfServerConfig(serverId: String) = ServerConfig(
    id = serverId,
    name = "Audiobookshelf",
    type = ServerType.Audiobookshelf,
    baseUrl = "http://localhost",
    addedAt = 0L,
)

private fun serverCredentials(serverId: String) = ServerCredentials(
    serverId = serverId,
    username = "reader",
    accessToken = "test-token",
    accountId = "abs-account-a",
)

private class SingleSourceAuthenticatedRepositoryProvider(
    private val repository: ServerBooksRepository,
) : AuthenticatedRepositoryProvider {
    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
        flowOf(listOf(repository))

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = listOf(repository)

    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
        repository.takeIf { candidate -> candidate.serverId == serverId }

    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null

    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
        flowOf(emptyList())

    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

private object TestLibrarySourceAdapterRegistry : LibrarySourceAdapterRegistry {
    private val localAdapter = LocalLibrarySourceAdapter()
    private val parrotCloudAdapter = ParrotCloudLibrarySourceAdapter()

    override fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter? = when (adapterId) {
        TestServerBookSourceAdapter.adapterId -> TestServerBookSourceAdapter
        localAdapter.adapterId -> localAdapter
        parrotCloudAdapter.adapterId -> parrotCloudAdapter
        else -> null
    }
}

private class SingleLibrarySourceAdapterRegistry(
    private val sourceAdapter: LibrarySourceAdapter,
) : LibrarySourceAdapterRegistry {
    override fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter? =
        sourceAdapter.takeIf { adapter -> adapter.adapterId == adapterId }
}

private class PromotingTestSourceAdapter(
    override val adapterId: LibraryAdapterId,
    private val portableBackendId: String,
) : ServerBookSourceAdapter,
    LibrarySourceIdentityPromotionAdapter {
    override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot =
        error("This method is unused in the test")

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ) = SourceBookSnapshot(
        source = source,
        metadata = SourceBookMetadata(book.title),
        resources = emptyList(),
        status = status,
    )

    override fun promoteUnresolvedSourceIdentity(
        unresolvedSource: SourceBookRef,
        portableAccountIdentity: SourceAccountIdentity.Portable,
    ): SourceBookRef? {
        if (portableAccountIdentity.backendId != portableBackendId) return null
        return unresolvedSource.copy(
            key = unresolvedSource.key.copy(accountIdentity = portableAccountIdentity),
        )
    }
}

private class ConflictSourceIdentityPromotionDatabase : LibrarySourceIdentityPromotionDatabase {
    val attemptedKeys = mutableListOf<SourceBookKey>()
    val attemptedKeyEvents = Channel<SourceBookKey>(Channel.UNLIMITED)

    override suspend fun promoteUnresolvedSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus {
        attemptedKeys += unresolvedKey
        attemptedKeyEvents.send(unresolvedKey)
        return LibrarySourceIdentityPromotionStatus.Conflict
    }

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
    ): List<LibrarySourceIdentityPromotionResult> = emptyList()
}

private object TestServerBookSourceAdapter : ServerBookSourceAdapter {
    override val adapterId = LibraryAdapterId("test")

    override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot =
        error("This method is unused in the test")

    override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ) = SourceBookSnapshot(
        source = source,
        metadata = SourceBookMetadata(book.title),
        resources = emptyList(),
        status = status,
    )
}

private class FakeLibrarySourceSnapshotsDatabase(
    initialSnapshots: List<SourceBookSnapshot>,
) : LibrarySourceSnapshotsDatabase {
    val snapshotState = MutableStateFlow(initialSnapshots)

    var savedSnapshots = 0
        private set

    override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> =
        snapshotState.map { Unit }

    override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId {
        savedSnapshots += 1
        val currentSnapshots = snapshotState.value.toMutableList()
        val existingIndex = currentSnapshots.indexOfFirst { current ->
            current.source.key == snapshot.source.key
        }
        if (existingIndex >= 0) {
            currentSnapshots[existingIndex] = snapshot
        } else {
            currentSnapshots += snapshot
        }
        snapshotState.value = currentSnapshots
        return LibraryGroupId("unused")
    }

    override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? =
        snapshotState.value.firstOrNull { snapshot -> snapshot.source.key == source }

    override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
        snapshotState.value.filter { snapshot ->
            snapshot.source.key.profileId == profileId
        }

    override suspend fun retireDeviceReplica(
        source: SourceBookRef,
        resource: SourceResourceRef,
        expectedStorageRef: DeviceStorageRef,
    ): DeviceReplicaRetirementResult {
        val snapshots = snapshotState.value
        val snapshotIndex = snapshots.indexOfFirst { candidate ->
            candidate.source.key == source.key
        }
        if (snapshotIndex < 0) return DeviceReplicaRetirementResult.NotFound

        val sourceSnapshot = snapshots[snapshotIndex]
        val resourceIndex = sourceSnapshot.resources.indexOfFirst { candidate ->
            candidate.reference == resource
        }
        if (resourceIndex < 0) return DeviceReplicaRetirementResult.NotFound

        val current = sourceSnapshot.resources[resourceIndex]
        if (current.localStorageReference == null) {
            return DeviceReplicaRetirementResult.AlreadyRemoved
        }
        if (current.localStorageReference != expectedStorageRef) {
            return DeviceReplicaRetirementResult.ReferenceChanged
        }

        val updatedResources = sourceSnapshot.resources.toMutableList().apply {
            this[resourceIndex] = current.copy(
                availability = SourceResourceAvailability.Unavailable,
                localStorageReference = null,
            )
        }
        snapshotState.value = snapshots.toMutableList().apply {
            this[snapshotIndex] = sourceSnapshot.copy(resources = updatedResources)
        }
        return DeviceReplicaRetirementResult.Removed
    }

    override suspend fun retireRemoteReplica(
        resource: SourceResourceRef,
        expectedRemoteRef: RemoteResourceRef,
    ): RemoteReplicaRetirementResult = error("This method is unused in the test")

    override suspend fun backfillGroups(
        profileId: LibraryProfileId,
        migrationId: String,
        startedAt: String,
        completedAt: String,
    ) = LibraryBackfillResult(processedSnapshots = 0, isComplete = true)
}

private object UnusedEvidenceDatabase : LibraryEvidenceDatabase {
    override suspend fun recordResource(resource: SourceResourceRef): Unit = unused()
    override suspend fun getResources(book: SourceBookKey): List<SourceResourceRef> = unused()
    override suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean = unused()
    override suspend fun getActiveEvidence(
        profileId: LibraryProfileId,
    ): List<LibraryEvidenceRecord> = emptyList()
    override suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String): Unit =
        unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private object UnusedSourceIdentityPromotionDatabase : LibrarySourceIdentityPromotionDatabase {
    override suspend fun promoteUnresolvedSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus = LibrarySourceIdentityPromotionStatus.NotFound

    override suspend fun resolveSourceIdentityAlias(key: SourceBookKey): SourceBookKey? = unused()

    override suspend fun resolvePromotedParrotCloudSource(
        unresolvedKey: SourceBookKey,
        linkedConnectionId: SourceConnectionId,
        cloudUserId: String,
    ): SourceBookKey? = unused()

    override suspend fun promoteUnresolvedParrotCloudSources(
        profileId: LibraryProfileId,
        connectionId: SourceConnectionId,
        cloudUserId: String,
    ): List<LibrarySourceIdentityPromotionResult> = emptyList()

    private fun unused(): Nothing = error("This method is unused in the test")
}

private object EmptyAuthenticatedRepositoryProvider : AuthenticatedRepositoryProvider {
    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> =
        flowOf(emptyList())

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = emptyList()
    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = null
    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
        flowOf(emptyList())

    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

private object EmptyLibrarySourceAdapterRegistry : LibrarySourceAdapterRegistry {
    override fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter? = null
}

private object UnusedUserRegistry : UserRegistry {
    override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()
    override suspend fun getAllProfiles(): List<UserProfile> = unused()
    override suspend fun createProfile(
        id: String?,
        name: String,
        avatarId: Int?,
    ): UserProfile = unused()

    override suspend fun updateProfile(profile: UserProfile): Unit = unused()
    override suspend fun deleteProfile(profileId: String): Unit = unused()
    override suspend fun getProfile(profileId: String): UserProfile? = unused()
    override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
    override suspend fun getActiveProfile(): UserProfile? = unused()
    override fun getActiveProfileId(): String? = unused()
    override suspend fun setActiveProfile(profileId: String): Unit = unused()
    override suspend fun clearActiveProfile(): Unit = unused()
    override suspend fun hasProfiles(): Boolean = unused()
    override fun isProfileActive(): Boolean = false

    private fun <T> unused(): T = error("This method is unused in the test")
}

private class ActiveProfileUserRegistry(
    private val activeProfiles: Flow<UserProfile?>,
) : UserRegistry by UnusedUserRegistry {
    override fun observeActiveProfile(): Flow<UserProfile?> = activeProfiles
}

private object NoPendingReplicaRemovalRepository : LibraryReplicaRemovalRepository {
    override suspend fun remove(
        groupId: LibraryGroupId,
        request: LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult = error("This method is unused in the test")

    override suspend fun recoverPending(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalResult> = emptyList()
}

private class SnapshotRetiringReplicaRemovalRepository(
    private val snapshotsDatabase: FakeLibrarySourceSnapshotsDatabase,
    private val source: SourceBookRef,
    private val resource: SourceResourceRef,
    private val storageReference: DeviceStorageRef,
) : LibraryReplicaRemovalRepository {
    val recoveredProfiles = mutableListOf<LibraryProfileId>()

    override suspend fun remove(
        groupId: LibraryGroupId,
        request: LibraryOperationRequest,
        requestedAt: String,
    ): LibraryReplicaRemovalResult = error("This method is unused in the test")

    override suspend fun recoverPending(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalResult> {
        recoveredProfiles += profileId
        val result = snapshotsDatabase.retireDeviceReplica(
            source = source,
            resource = resource,
            expectedStorageRef = storageReference,
        )
        check(result == DeviceReplicaRetirementResult.Removed)
        return listOf(LibraryReplicaRemovalResult.Completed("remove-1"))
    }
}

private class DeletedCloudBookListingDatabase(
    private val cloudBookId: String,
) : LibraryBooksDatabase {
    override suspend fun upsertLibraryBook(book: LibraryBookEntity): Unit = unused()

    override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity): Unit = unused()

    override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = flowOf(emptyList())

    override fun observeDeletedCloudBookIds(): Flow<List<String>> = flowOf(listOf(cloudBookId))

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = unused()

    override suspend fun getLibraryBookByContentHash(
        contentHash: String,
    ): LibraryBookEntity? = unused()

    override suspend fun getLibraryBookByCloudBookId(
        cloudBookId: String,
    ): LibraryBookEntity? = unused()

    override suspend fun attachCloudBookId(
        libraryBookId: String,
        cloudBookId: String,
    ): Unit = unused()

    override suspend fun upsertLocalBookFile(file: LocalBookFileEntity): Unit = unused()

    override suspend fun getLocalBookFiles(
        libraryBookId: String,
    ): List<LocalBookFileEntity> = unused()

    override suspend fun getLocalBookFileByImportedBookUuid(
        importedBookUuid: String,
    ): LocalBookFileEntity? = unused()

    override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String): Unit =
        unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private object EmptyCloudFilesDatabase : CloudFilesDatabase {
    override suspend fun upsertFileState(file: CloudBookFileEntity): Unit = unused()

    override suspend fun getFileStates(
        libraryBookId: String,
    ): List<CloudBookFileEntity> = emptyList()

    override fun observeFileStates(): Flow<List<CloudBookFileEntity>> = flowOf(emptyList())

    override suspend fun deleteFileState(
        libraryBookId: String,
        mediaType: String,
        relativePath: String,
    ): Unit = unused()

    override suspend fun insertTransfer(transfer: CloudFileTransferEntity): Unit = unused()

    override suspend fun getTransfer(transferId: String): CloudFileTransferEntity? = unused()

    override suspend fun updateTransfer(transfer: CloudFileTransferEntity): Unit = unused()

    override suspend fun deleteTransfer(transferId: String): Unit = unused()

    override suspend fun getTransfers(
        serverId: String,
        states: List<String>,
    ): List<CloudFileTransferEntity> = emptyList()

    override suspend fun getTransfersForCloudFile(
        cloudBookFileId: String,
    ): List<CloudFileTransferEntity> = emptyList()

    override suspend fun enqueuePendingFileFeedChange(
        cloudBookId: String,
        feedRevision: Long?,
        payloadJson: String,
        receivedAt: String,
    ): PendingCloudFileFeedChange = unused()

    override suspend fun getPendingFileFeedChanges(
        cloudBookId: String,
    ): List<PendingCloudFileFeedChange> = emptyList()

    override suspend fun getPendingFileFeedCloudBookIds(): List<String> = emptyList()

    override suspend fun deletePendingFileFeedChange(changeId: Long): Unit = unused()

    override fun observeTransfers(
        serverId: String,
        libraryBookId: String,
    ): Flow<List<CloudFileTransferEntity>> = flowOf(emptyList())

    override fun observeActiveTransfers(): Flow<List<CloudFileTransferEntity>> = flowOf(emptyList())

    override fun observeAllTransfers(): Flow<List<CloudFileTransferEntity>> = flowOf(emptyList())

    override suspend fun clearAllData(): Unit = unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private object EmptyImportedBooksDatabase : ImportedBooksDatabase {
    override suspend fun upsertImportedBook(book: ImportedBookEntity): Unit = unused()

    override suspend fun upsertImportedBookWithLibraryMapping(
        book: ImportedBookEntity,
        mutation: LibraryBookMutation,
    ): Unit = unused()

    override suspend fun saveRestoredBookWithLibraryMapping(
        book: ImportedBookEntity,
        libraryBook: LibraryBookEntity,
        localBookFile: LocalBookFileEntity,
        transfer: CloudFileTransferEntity,
        position: PositionEntity?,
    ): Unit = unused()

    override fun getAllImportedBooks(): Flow<List<ImportedBookEntity>> = flowOf(emptyList())

    override suspend fun getImportedBookByUuid(uuid: String): ImportedBookEntity? = unused()

    override suspend fun getImportedBookByContentHash(
        contentHash: String,
    ): ImportedBookEntity? = unused()

    override suspend fun deleteImportedBook(uuid: String): Unit = unused()

    override suspend fun deleteAllImportedBooks(): Unit = unused()

    override suspend fun getImportedBooksCount(): Int = unused()

    override suspend fun updateLastOpenedAt(uuid: String, lastOpenedAt: String): Unit = unused()

    override suspend fun searchImportedBooksByTitle(
        query: String,
    ): List<ImportedBookEntity> = emptyList()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private object UnusedSyncOutboxDatabase : SyncOutboxDatabase {
    override suspend fun enqueue(entry: SyncOutboxEntry): Unit = unused()

    override suspend fun bindUnassignedMutations(cloudUserId: String): Unit = unused()

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = emptyList()

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long): Unit = unused()

    override suspend fun markDispatched(mutationId: String): Unit = unused()

    override suspend fun markConflict(mutationId: String, error: String): Unit = unused()

    override suspend fun delete(mutationId: String): Unit = unused()

    override suspend fun deleteByEntityType(entityType: String): Unit = unused()

    override suspend fun recordFailure(
        mutationId: String,
        nextAttemptAt: String,
        error: String,
    ): Unit = unused()

    override suspend fun coalesce(
        entityType: String,
        entityId: String,
        entry: SyncOutboxEntry,
    ): Unit = unused()

    override suspend fun clearAllData(): Unit = unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}

private object LinkedCloudProfileRepository : CloudProfileLinkRepository {
    override suspend fun getForLocalProfile(localProfileId: String) = CloudProfileLink(
        localProfileId = localProfileId,
        cloudUserId = "cloud-user",
        syncEnabled = true,
    )

    override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? = null

    override fun observeForLocalProfile(
        localProfileId: String,
    ): Flow<CloudProfileLink?> = flowOf(null)

    override suspend fun link(
        localProfileId: String,
        cloudUserId: String,
    ): com.retro99.cloudaccount.domain.model.CloudProfileLinkResult = unused()

    override suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean): Unit = unused()

    override suspend fun setAutoBackupEnabled(
        localProfileId: String,
        enabled: Boolean,
    ): Unit = unused()

    override suspend fun setUploadAttestation(
        localProfileId: String,
        cloudUserId: String,
        attestation: com.retro99.cloudaccount.domain.model.UploadAttestationRecord,
    ): Unit = unused()

    override suspend fun deactivate(localProfileId: String): Unit = unused()

    override suspend fun unlink(localProfileId: String): Unit = unused()

    private fun <T> unused(): T = error("This method is unused in the test")
}
