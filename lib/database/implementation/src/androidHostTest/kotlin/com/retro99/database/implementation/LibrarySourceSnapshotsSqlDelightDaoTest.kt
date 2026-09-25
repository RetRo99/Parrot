package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.database.implementation.dao.library.LibraryEvidenceSqlDelightDao
import com.retro99.database.implementation.dao.library.LibrarySourceKeyCodec
import com.retro99.database.implementation.dao.library.LibrarySourceSnapshotsSqlDelightDao
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookCollection
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookMetadata
import com.retro99.server.api.library.SourceBookSeries
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibrarySourceSnapshotsSqlDelightDaoTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var session: ActiveProfileSession
    private lateinit var classUnderTest: LibrarySourceSnapshotsDatabase
    private lateinit var evidenceDatabase: LibraryEvidenceDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        session = ActiveProfileSession("profile-a")
        classUnderTest = LibrarySourceSnapshotsSqlDelightDao(session) { database }
        evidenceDatabase = LibraryEvidenceSqlDelightDao(session) { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun presentSnapshotPersistsStableMembershipMetadataResourcesAndEvidence() = runBlocking {
        // Given
        val snapshot = snapshot()

        // When
        val groupId = classUnderTest.saveSnapshot(snapshot)
        val replayGroupId = classUnderTest.saveSnapshot(
            snapshot.copy(source = snapshot.source.copy(connectionId = connection("connection-b"))),
        )

        // Then
        assertEquals(groupId, replayGroupId)
        assertEquals(
            snapshot.copy(
                source = snapshot.source.copy(connectionId = connection("connection-b")),
            ),
            classUnderTest.getSnapshot(snapshot.source.key),
        )
        assertEquals(snapshot.metadata, classUnderTest.getSnapshot(snapshot.source.key)?.metadata)
        assertEquals(snapshot.metadata.collections, classUnderTest.getSnapshot(snapshot.source.key)
            ?.metadata?.collections)
        assertEquals(groupId, database.libraryGroupQueries.getLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "test-adapter",
            identity_kind = "portable",
            backend_id = "backend-a",
            account_id = "account-a",
            unresolved_connection_id = "",
            native_book_id = "book-a",
        ).executeAsOne().group_id.let { groupValue -> LibraryGroupId(groupValue) })
        assertEquals(1, evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).size)
    }

    @Test
    fun sourceCollectionsRoundTripAndAuthoritativeRefreshReplacesThem() = runBlocking {
        // Given
        val originalSnapshot = snapshot()
        val original = originalSnapshot.copy(
            metadata = originalSnapshot.metadata.copy(
                collections = listOf(
                    SourceBookCollection(
                        nativeId = "collection-1",
                        name = "Favorites",
                        createdAt = "2026-09-20T00:00:00Z",
                        updatedAt = "2026-09-21T00:00:00Z",
                    ),
                    SourceBookCollection("collection-2", "To Read"),
                ),
            ),
        )
        classUnderTest.saveSnapshot(original)
        val refreshed = original.copy(
            metadata = original.metadata.copy(
                collections = listOf(SourceBookCollection("collection-3", "Archived")),
            ),
            status = status(at = "2026-09-24T00:01:00Z"),
        )

        // When
        classUnderTest.saveSnapshot(refreshed)

        // Then
        assertEquals(
            refreshed.metadata.collections,
            classUnderTest.getSnapshot(original.source.key)?.metadata?.collections,
        )
    }

    @Test
    fun snapshotObserverEmitsWhenCollectionMetadataChanges() = runBlocking {
        // Given
        val original = snapshot()
        classUnderTest.saveSnapshot(original)
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            classUnderTest.observeSnapshotChanges(LibraryProfileId("profile-a"))
                .collect { emissions.send(Unit) }
        }

        try {
            withTimeout(5_000) { emissions.receive() }

            // When
            val updated = original.copy(
                metadata = original.metadata.copy(
                    collections = listOf(SourceBookCollection("collection-1", "Favorites")),
                ),
                status = status(at = "2026-09-24T00:01:00Z"),
            )
            classUnderTest.saveSnapshot(updated)

            // Then
            withTimeout(5_000) { emissions.receive() }
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun sourceSnapshotsWithoutCollectionsRoundTripAnEmptyList() = runBlocking {
        // Given
        val sourceSnapshot = snapshot()

        // When
        classUnderTest.saveSnapshot(sourceSnapshot)

        // Then
        assertEquals(
            emptyList(),
            classUnderTest.getSnapshot(sourceSnapshot.source.key)?.metadata?.collections,
        )
    }

    @Test
    fun unknownSnapshotRetainsLastKnownMetadataResourcesAndEvidence() = runBlocking {
        // Given
        val original = snapshot()
        val groupId = classUnderTest.saveSnapshot(original)
        val unknown = original.copy(
            source = original.source.copy(connectionId = connection("connection-b")),
            metadata = SourceBookMetadata(title = "Transient error title"),
            resources = emptyList(),
            identityEvidence = emptyList(),
            status = status(
                at = "2026-09-24T00:01:00Z",
                presence = SourcePresence.Unknown,
                isAuthoritative = false,
            ),
        )

        // When
        val replayedGroupId = classUnderTest.saveSnapshot(unknown)
        val stored = classUnderTest.getSnapshot(original.source.key)

        // Then
        assertEquals(groupId, replayedGroupId)
        assertEquals("Original title", stored?.metadata?.title)
        assertEquals(original.resources, stored?.resources)
        assertEquals(original.identityEvidence, stored?.identityEvidence)
        assertEquals(SourcePresence.Unknown, stored?.status?.presence)
        assertEquals(connection("connection-b"), stored?.source?.connectionId)
        assertEquals(1, evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).size)
    }

    @Test
    fun authoritativeRemovalRetainsSnapshotAndMembershipButRetiresEvidence() = runBlocking {
        // Given
        val base = snapshot()
        val original = base.copy(
            identityEvidence = base.identityEvidence + SourceIdentityEvidence.BookFingerprint(
                book = base.source.key,
                algorithm = "sha-256-v1",
                hash = "cloud-verified-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
        )
        val groupId = classUnderTest.saveSnapshot(original)
        val removed = original.copy(
            metadata = SourceBookMetadata(title = "Removal event"),
            resources = emptyList(),
            identityEvidence = emptyList(),
            status = status(
                at = "2026-09-24T00:02:00Z",
                presence = SourcePresence.Removed,
                isAuthoritative = true,
            ),
        )

        // When
        classUnderTest.saveSnapshot(removed)
        val stored = classUnderTest.getSnapshot(original.source.key)

        // Then
        assertEquals("Original title", stored?.metadata?.title)
        assertEquals(
            original.resources.map { resource ->
                resource.copy(
                    availability = SourceResourceAvailability.Unavailable,
                    localStorageReference = null,
                    remoteResourceReference = null,
                )
            },
            stored?.resources,
        )
        assertEquals(SourcePresence.Removed, stored?.status?.presence)
        assertTrue(stored?.identityEvidence.isNullOrEmpty())
        assertEquals(groupId, database.libraryGroupQueries.getLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "test-adapter",
            identity_kind = "portable",
            backend_id = "backend-a",
            account_id = "account-a",
            unresolved_connection_id = "",
            native_book_id = "book-a",
        ).executeAsOne().group_id.let { groupValue -> LibraryGroupId(groupValue) })
        assertTrue(evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).isEmpty())
    }

    @Test
    fun remoteFileRemovalRetiresOnlyExactReplicaAndKeepsBookHistoryAndDeviceReplica() =
        runBlocking {
            // Given
            val base = snapshot()
            val siblingResource = SourceMediaResource(
                reference = SourceResourceRef(base.source.key, "asset-audio-sibling"),
                mediaType = "audiobook",
                format = "m4b",
                sizeBytes = 4096,
                availability = SourceResourceAvailability.AvailableRemotely,
                remoteResourceReference = RemoteResourceRef("remote-audio-id"),
            )
            val target = base.copy(resources = base.resources + siblingResource)
            classUnderTest.saveSnapshot(target)
            val otherAccountKey = base.source.key.copy(
                accountIdentity = SourceAccountIdentity.Portable("backend-a", "account-b"),
            )
            val otherAccount = base.copy(
                source = base.source.copy(key = otherAccountKey),
                resources = base.resources.map { resource ->
                    resource.copy(reference = resource.reference.copy(book = otherAccountKey))
                },
                identityEvidence = emptyList(),
            )
            classUnderTest.saveSnapshot(otherAccount)
            val resource = target.resources[1]

            // When
            val wrongReference = classUnderTest.retireRemoteReplica(
                resource = resource.reference,
                expectedRemoteRef = RemoteResourceRef("replacement-file-id"),
            )
            val retired = classUnderTest.retireRemoteReplica(
                resource = resource.reference,
                expectedRemoteRef = RemoteResourceRef("remote-audio-id"),
            )
            val replayed = classUnderTest.retireRemoteReplica(
                resource = resource.reference,
                expectedRemoteRef = RemoteResourceRef("remote-audio-id"),
            )
            val stored = classUnderTest.getSnapshot(target.source.key)
            val storedOtherAccount = classUnderTest.getSnapshot(otherAccount.source.key)

            // Then
            assertEquals(RemoteReplicaRetirementResult.ReferenceChanged, wrongReference)
            assertEquals(RemoteReplicaRetirementResult.Removed, retired)
            assertEquals(RemoteReplicaRetirementResult.AlreadyRemoved, replayed)
            assertEquals(SourcePresence.Present, stored?.status?.presence)
            assertEquals("Original title", stored?.metadata?.title)
            assertEquals(
                target.resources.first(),
                stored?.resources?.first(),
                "The existing device replica should remain attached to the Cloud book",
            )
            assertEquals(
                resource.copy(
                    availability = SourceResourceAvailability.Unavailable,
                    remoteResourceReference = null,
                ),
                stored?.resources?.get(1),
            )
            assertEquals(
                siblingResource,
                stored?.resources?.get(2),
                "A different remote resource with the same remote ID must remain available",
            )
            assertEquals(target.identityEvidence, stored?.identityEvidence)
            assertEquals(1, evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).size)
            assertEquals(SourcePresence.Present, storedOtherAccount?.status?.presence)
            assertEquals(otherAccount.resources, storedOtherAccount?.resources)
        }

    @Test
    fun snapshotObserverEmitsWhenRemoteReplicaIsRetired() = runBlocking {
        // Given
        val snapshot = snapshot()
        classUnderTest.saveSnapshot(snapshot)
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            classUnderTest.observeSnapshotChanges(LibraryProfileId("profile-a"))
                .collect { emissions.send(Unit) }
        }

        try {
            withTimeout(5_000) { emissions.receive() }

            // When
            val resource = snapshot.resources.last()
            val result = classUnderTest.retireRemoteReplica(
                resource = resource.reference,
                expectedRemoteRef = requireNotNull(resource.remoteResourceReference),
            )

            // Then
            assertEquals(RemoteReplicaRetirementResult.Removed, result)
            withTimeout(5_000) { emissions.receive() }
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun authoritativeSnapshotRetainsOmittedResourceAsUnavailableAndRetiresItsEvidence() =
        runBlocking {
            // Given
            val base = snapshot()
            val original = base.copy(
                identityEvidence = base.identityEvidence + SourceIdentityEvidence.FileFingerprint(
                    resource = base.resources.last().reference,
                    algorithm = "sha-256-v1",
                    hash = "omitted-audio-hash",
                    scope = FingerprintScope.WholeFile,
                    verification = FingerprintVerification.Verified,
                ),
            )
            classUnderTest.saveSnapshot(original)

            // When
            val authoritative = original.copy(
                resources = listOf(original.resources.first()),
                identityEvidence = emptyList(),
                status = status(
                    at = "2026-09-24T00:01:00Z",
                    isAuthoritative = true,
                ),
            )
            classUnderTest.saveSnapshot(authoritative)
            val stored = classUnderTest.getSnapshot(original.source.key)

            // Then
            assertEquals(2, stored?.resources?.size)
            assertEquals(original.resources.first(), stored?.resources?.first())
            assertEquals(
                original.resources.last().copy(
                    availability = SourceResourceAvailability.Unavailable,
                    localStorageReference = null,
                    remoteResourceReference = null,
                ),
                stored?.resources?.last(),
            )
            assertEquals(
                original.resources.map { resource -> resource.reference }.toSet(),
                evidenceDatabase.getResources(original.source.key).toSet(),
            )
            assertTrue(evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).isEmpty())
        }

    @Test
    fun bookFingerprintSnapshotPersistsWithoutResourceAndCorrectionRetiresIt() = runBlocking {
        // Given
        val sourceSnapshot = snapshot().copy(
            resources = emptyList(),
            identityEvidence = listOf(
                SourceIdentityEvidence.BookFingerprint(
                    book = snapshot().source.key,
                    algorithm = "sha-256-v1",
                    hash = "cloud-verified-hash",
                    scope = FingerprintScope.WholeFile,
                    verification = FingerprintVerification.Verified,
                ),
            ),
        )

        // When
        classUnderTest.saveSnapshot(sourceSnapshot)
        val stored = classUnderTest.getSnapshot(sourceSnapshot.source.key)

        // Then
        assertEquals(sourceSnapshot.resources, stored?.resources)
        assertEquals(sourceSnapshot.identityEvidence, stored?.identityEvidence)
        assertEquals(emptyList(), evidenceDatabase.getResources(sourceSnapshot.source.key))
        assertEquals(1, evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).size)

        classUnderTest.saveSnapshot(
            sourceSnapshot.copy(
                identityEvidence = emptyList(),
                status = status(at = "2026-09-24T00:01:00Z"),
            ),
        )
        assertTrue(evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).isEmpty())
    }

    @Test
    fun deviceReplicaRemovalKeepsGroupMetadataAndOtherMediaAndRecoversIdempotently() = runBlocking {
        // Given
        val original = snapshot()
        val groupId = classUnderTest.saveSnapshot(original)
        val sourceResource = original.resources.first().reference
        val expectedStorageRef = DeviceStorageRef("device-file-ref")

        // When
        val first = classUnderTest.retireDeviceReplica(
            original.source,
            sourceResource,
            expectedStorageRef,
        )
        val replay = classUnderTest.retireDeviceReplica(
            original.source,
            sourceResource,
            expectedStorageRef,
        )
        val stored = classUnderTest.getSnapshot(original.source.key)

        // Then
        assertEquals(DeviceReplicaRetirementResult.Removed, first)
        assertEquals(DeviceReplicaRetirementResult.AlreadyRemoved, replay)
        assertEquals(original.source, stored?.source)
        assertEquals(original.metadata, stored?.metadata)
        assertEquals(
            SourceResourceAvailability.Unavailable,
            stored?.resources?.first()?.availability,
        )
        assertNull(stored?.resources?.first()?.localStorageReference)
        assertEquals(original.resources.last(), stored?.resources?.last())
        assertEquals(groupId, database.libraryGroupQueries.getLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "test-adapter",
            identity_kind = "portable",
            backend_id = "backend-a",
            account_id = "account-a",
            unresolved_connection_id = "",
            native_book_id = "book-a",
        ).executeAsOne().group_id.let { groupValue -> LibraryGroupId(groupValue) })
    }

    @Test
    fun deviceReplicaRemovalDetachesOneSharedOwnerAndKeepsTheFinalOwnerPath() = runBlocking {
        // Given
        val first = snapshot(nativeBookId = "book-a")
        val finalOwner = snapshot(nativeBookId = "book-b")
        val firstResource = first.resources.first().reference
        val finalOwnerResource = finalOwner.resources.first().reference
        val expectedStorageRef = DeviceStorageRef("device-file-ref")
        classUnderTest.saveSnapshot(first)
        classUnderTest.saveSnapshot(finalOwner)

        // When
        val firstRemoval = classUnderTest.retireDeviceReplica(
            first.source,
            firstResource,
            expectedStorageRef,
        )
        val firstStoredAfterRemoval = classUnderTest.getSnapshot(first.source.key)
        val finalOwnerStoredBeforeRemoval = classUnderTest.getSnapshot(finalOwner.source.key)
        val finalRemoval = classUnderTest.retireDeviceReplica(
            finalOwner.source,
            finalOwnerResource,
            expectedStorageRef,
        )

        // Then
        assertEquals(
            DeviceReplicaRetirementResult.RemovedWithSharedReferences,
            firstRemoval,
        )
        assertNull(firstStoredAfterRemoval?.resources?.first()?.localStorageReference)
        assertEquals(
            expectedStorageRef,
            finalOwnerStoredBeforeRemoval?.resources?.first()?.localStorageReference,
        )
        assertEquals(DeviceReplicaRetirementResult.Removed, finalRemoval)
        assertNull(classUnderTest.getSnapshot(finalOwner.source.key)?.resources?.first()
            ?.localStorageReference)
    }

    @Test
    fun deviceReplicaRemovalDoesNotClearAReplacementAtTheSameResourceIdentity() = runBlocking {
        // Given
        val original = snapshot()
        classUnderTest.saveSnapshot(original)
        val replacement = DeviceStorageRef("replacement-file-ref")
        classUnderTest.saveSnapshot(
            original.copy(
                resources = original.resources.map { resource ->
                    if (resource.reference == original.resources.first().reference) {
                        resource.copy(localStorageReference = replacement)
                    } else {
                        resource
                    }
                },
                status = status(at = "2026-09-24T00:01:00Z"),
            ),
        )

        // When
        val result = classUnderTest.retireDeviceReplica(
            original.source,
            original.resources.first().reference,
            DeviceStorageRef("device-file-ref"),
        )

        // Then
        assertEquals(DeviceReplicaRetirementResult.ReferenceChanged, result)
        assertEquals(
            replacement,
            classUnderTest.getSnapshot(original.source.key)?.resources?.first()
                ?.localStorageReference,
        )
    }

    @Test
    fun staleSnapshotCannotReplaceNewerDataOrExecutionConnection() = runBlocking {
        // Given
        val latest = snapshot(
            title = "Latest title",
            at = "2026-09-24T00:02:00Z",
            connectionId = "connection-new",
        )
        val groupId = classUnderTest.saveSnapshot(latest)
        val stale = snapshot(
            title = "Stale title",
            at = "2026-09-24T00:01:00Z",
            connectionId = "connection-old",
        )

        // When
        val staleSaveGroupId = classUnderTest.saveSnapshot(stale)
        val stored = classUnderTest.getSnapshot(latest.source.key)

        // Then
        assertEquals(groupId, staleSaveGroupId)
        assertEquals("Latest title", stored?.metadata?.title)
        assertEquals(connection("connection-new"), stored?.source?.connectionId)
        assertEquals(latest.status, stored?.status)
    }

    @Test
    fun olderSnapshotInSameMillisecondCannotReplaceNewerObservation() = runBlocking {
        // Given
        val latest = snapshot(
            title = "Latest title",
            at = "2026-09-24T00:00:00.000900Z",
            connectionId = "connection-new",
        )
        val groupId = classUnderTest.saveSnapshot(latest)
        val earlier = snapshot(
            title = "Earlier title",
            at = "2026-09-24T00:00:00.000100Z",
            connectionId = "connection-old",
        )

        // When
        val earlierSaveGroupId = classUnderTest.saveSnapshot(earlier)
        val stored = classUnderTest.getSnapshot(latest.source.key)

        // Then
        assertEquals(groupId, earlierSaveGroupId)
        assertEquals("Latest title", stored?.metadata?.title)
        assertEquals(connection("connection-new"), stored?.source?.connectionId)
        assertEquals(latest.status, stored?.status)
        assertEquals(
            900_000L,
            database.librarySourceSnapshotQueries.getLibrarySourceSnapshot(
                "profile-a",
                LibrarySourceKeyCodec.encode(latest.source.key),
            ).executeAsOne().observed_at_submillisecond_ns,
        )
    }

    @Test
    fun backfillResumesInBatchesAndKeepsExistingGroupIds() = runBlocking {
        // Given
        val firstSnapshot = snapshot(
            nativeBookId = "book-000",
            at = "2026-09-24T00:00:00Z",
        )
        val stableGroup = classUnderTest.saveSnapshot(firstSnapshot)
        (1..100).forEach { index ->
            classUnderTest.saveSnapshot(
                snapshot(
                    nativeBookId = "book-${index.toString().padStart(3, '0')}",
                    at = "2026-09-24T00:00:00Z",
                ),
            )
        }

        // When
        val firstBatch = classUnderTest.backfillGroups(
            LibraryProfileId("profile-a"),
            migrationId = "group-backfill-v1",
            startedAt = "2026-09-24T00:03:00Z",
            completedAt = "2026-09-24T00:04:00Z",
        )
        val secondBatch = classUnderTest.backfillGroups(
            LibraryProfileId("profile-a"),
            migrationId = "group-backfill-v1",
            startedAt = "2026-09-24T00:05:00Z",
            completedAt = "2026-09-24T00:06:00Z",
        )
        val replay = classUnderTest.backfillGroups(
            LibraryProfileId("profile-a"),
            migrationId = "group-backfill-v1",
            startedAt = "2026-09-24T00:07:00Z",
            completedAt = "2026-09-24T00:08:00Z",
        )

        // Then
        assertEquals(100, firstBatch.processedSnapshots)
        assertFalse(firstBatch.isComplete)
        assertEquals(1, secondBatch.processedSnapshots)
        assertTrue(secondBatch.isComplete)
        assertEquals(0, replay.processedSnapshots)
        assertTrue(replay.isComplete)
        assertEquals(stableGroup, database.libraryGroupQueries.getLibraryGroupMembership(
            profile_id = "profile-a",
            adapter_id = "test-adapter",
            identity_kind = "portable",
            backend_id = "backend-a",
            account_id = "account-a",
            unresolved_connection_id = "",
            native_book_id = "book-000",
        ).executeAsOne().group_id.let { groupValue -> LibraryGroupId(groupValue) })
        assertEquals(101, database.libraryGroupQueries.getLibraryGroupsForProfile("profile-a")
            .executeAsList().size)
    }

    @Test
    fun snapshotsRemainScopedToTheActiveProfile() = runBlocking {
        // Given
        val otherProfile = snapshot(profileId = "profile-b")

        // When / Then
        assertFailsWith<IllegalStateException> { classUnderTest.saveSnapshot(otherProfile) }
        assertFailsWith<IllegalStateException> {
            classUnderTest.getSnapshot(otherProfile.source.key)
        }
        assertEquals(
            emptyList(),
            database.libraryGroupQueries.getLibraryGroupsForProfile("profile-b")
                .executeAsList(),
        )
    }

    private fun snapshot(
        profileId: String = "profile-a",
        nativeBookId: String = "book-a",
        title: String = "Original title",
        at: String = "2026-09-24T00:00:00Z",
        connectionId: String = "connection-a",
    ): SourceBookSnapshot {
        val source = source(profileId = profileId, nativeBookId = nativeBookId)
            .copy(connectionId = connection(connectionId))
        val resource = SourceResourceRef(source.key, "asset-epub", "revision-1")
        return SourceBookSnapshot(
            source = source,
            metadata = SourceBookMetadata(
                title = title,
                description = "A source-owned description",
                coverReference = "cover-native-id",
                authors = listOf("Author one", "Author two"),
                narrators = listOf("Narrator one"),
                series = listOf(SourceBookSeries("series-id", "Series name", 2.5f)),
                tags = listOf("tag-one", "tag-two"),
                mediaTypes = listOf("ebook", "audiobook"),
                publicationDate = "2024-03-01",
            ),
            resources = listOf(
                SourceMediaResource(
                    reference = resource,
                    mediaType = "ebook",
                    format = "epub",
                    sizeBytes = 8192,
                    availability = SourceResourceAvailability.DevicePresent,
                    localStorageReference = DeviceStorageRef("device-file-ref"),
                ),
                SourceMediaResource(
                    reference = SourceResourceRef(source.key, "asset-audio"),
                    mediaType = "audiobook",
                    format = "m4b",
                    sizeBytes = 16384,
                    availability = SourceResourceAvailability.AvailableRemotely,
                    remoteResourceReference = RemoteResourceRef("remote-audio-id"),
                ),
            ),
            identityEvidence = listOf(
                SourceIdentityEvidence.FileFingerprint(
                    resource = resource,
                    algorithm = "sha-256-v1",
                    hash = "verified-hash",
                    scope = FingerprintScope.WholeFile,
                    verification = FingerprintVerification.Verified,
                ),
            ),
            status = status(at),
        )
    }

    private fun source(
        profileId: String = "profile-a",
        nativeBookId: String = "book-a",
    ) = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId(profileId),
            adapterId = LibraryAdapterId("test-adapter"),
            accountIdentity = SourceAccountIdentity.Portable("backend-a", "account-a"),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = connection("connection-a"),
        legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:legacy-hash"),
    )

    private fun connection(value: String) = SourceConnectionId(value)

    private fun status(
        at: String = "2026-09-24T00:00:00Z",
        presence: SourcePresence = SourcePresence.Present,
        isAuthoritative: Boolean = true,
    ) = SourceSnapshotStatus(
        observedAt = Instant.parse(at),
        presence = presence,
        isAuthoritative = isAuthoritative,
    )

    private class ActiveProfileSession(
        private val activeProfileId: String,
    ) : ProfileDatabaseSession {
        override suspend fun <T> withProfile(
            localProfileId: String,
            operation: suspend () -> T,
        ): T {
            check(localProfileId == activeProfileId)
            return operation()
        }
    }
}
