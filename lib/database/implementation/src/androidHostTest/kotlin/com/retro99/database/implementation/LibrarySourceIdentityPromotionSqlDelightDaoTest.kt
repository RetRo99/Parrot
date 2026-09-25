package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryEvidenceDatabase
import com.retro99.database.api.library.LibraryManualGroupMemberSelection
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.library.LibraryEvidenceSqlDelightDao
import com.retro99.database.implementation.dao.library.LibraryGroupsSqlDelightDao
import com.retro99.database.implementation.dao.library.LibrarySourceIdentityPromotionSqlDelightDao
import com.retro99.database.implementation.dao.library.LibrarySourceKeyCodec
import com.retro99.database.implementation.dao.library.LibrarySourceSnapshotsSqlDelightDao
import com.retro99.database.implementation.dao.sync.enqueue
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.NativeBookId
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
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LibrarySourceIdentityPromotionSqlDelightDaoTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var session: ActiveProfileSession
    private lateinit var groupsDatabase: LibraryGroupsDatabase
    private lateinit var snapshotsDatabase: LibrarySourceSnapshotsDatabase
    private lateinit var evidenceDatabase: LibraryEvidenceDatabase
    private lateinit var classUnderTest: LibrarySourceIdentityPromotionSqlDelightDao

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        session = ActiveProfileSession("profile-a")
        groupsDatabase = LibraryGroupsSqlDelightDao(session) { database }
        snapshotsDatabase = LibrarySourceSnapshotsSqlDelightDao(session) { database }
        evidenceDatabase = LibraryEvidenceSqlDelightDao(session) { database }
        classUnderTest = LibrarySourceIdentityPromotionSqlDelightDao(session) { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun promotionRekeysMembershipSnapshotEvidenceAndConstraintsButPreservesDecisionBytes() =
        runBlocking {
            // Given
            val unresolved = unresolvedCloudSource()
            val portable = portableCloudSource("cloud-user-a", unresolved.key.nativeBookId.value)
            val otherSource = portableAudiobookshelfSource()
            val originalSnapshot = snapshot(unresolved)
            val groupId = snapshotsDatabase.saveSnapshot(originalSnapshot)
            groupsDatabase.ensureGroupForSource(
                otherSource,
                LibraryGroupId("group-other"),
                CREATED_AT,
            )
            groupsDatabase.addManualSeparation(
                unresolved.key,
                otherSource.key,
                decisionId = "separation-decision",
                createdAt = CREATED_AT,
            )
            database.libraryGroupQueries.moveLibraryGroupMembership(
                group_id = groupId.value,
                membership_origin = LibraryMembershipOrigin.Manual.name,
                revision = 7L,
                decision_id = "decision-pending",
                profile_id = "profile-a",
                adapter_id = "parrot-cloud",
                identity_kind = "unresolved",
                backend_id = "",
                account_id = "",
                unresolved_connection_id = CONNECTION_ID.value,
                native_book_id = unresolved.key.nativeBookId.value,
            )
            val oldStorageKey = LibrarySourceKeyCodec.encode(unresolved.key)
            database.libraryGroupDecisionQueries.upsertLibraryGroupMetadataPreference(
                profile_id = "profile-a",
                group_id = groupId.value,
                source_key = oldStorageKey,
                decision_id = "metadata-decision",
                server_revision = 0L,
            )
            val payload = "pending decision keeps its original unresolved member"
            database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                profile_id = "profile-a",
                decision_id = "decision-pending",
                server_revision = 0L,
                payload = payload,
                created_at = CREATED_AT,
            )
            database.syncOutboxQueries.enqueue(outboxEntry(payload))

            // When
            val status = classUnderTest.promoteUnresolvedSourceIdentity(
                unresolvedKey = unresolved.key,
                portableSource = portable,
            )

            // Then
            assertEquals(LibrarySourceIdentityPromotionStatus.Promoted, status)
            val membership = groupsDatabase.getMembership(portable.key)
            assertEquals(groupId, membership?.groupId)
            assertEquals(LibraryMembershipOrigin.Manual, membership?.origin)
            assertEquals(7L, membership?.revision)
            assertEquals("decision-pending", membership?.decisionId)
            assertNull(groupsDatabase.getMembership(unresolved.key))

            val promotedSnapshot = snapshotsDatabase.getSnapshot(portable.key)
            assertEquals(originalSnapshot.metadata, promotedSnapshot?.metadata)
            assertEquals(SourcePresence.Present, promotedSnapshot?.status?.presence)
            assertEquals(portable.key, promotedSnapshot?.resources?.single()?.reference?.book)
            assertEquals(
                DeviceStorageRef("device-file-reference"),
                promotedSnapshot?.resources?.single()?.localStorageReference,
            )
            assertNull(snapshotsDatabase.getSnapshot(unresolved.key))
            assertTrue(
                evidenceDatabase.getActiveEvidence(LibraryProfileId("profile-a")).any { record ->
                    (record.evidence as? SourceIdentityEvidence.BookFingerprint)
                        ?.book == portable.key
                },
            )
            assertEquals(
                portable.key,
                groupsDatabase.getGroup(LibraryProfileId("profile-a"), groupId)
                    ?.preferredMetadataSourceKey,
            )
            val separation = groupsDatabase.getManualSeparations(LibraryProfileId("profile-a"))
                .single()
            assertEquals(
                setOf(portable.key, otherSource.key),
                setOf(separation.first, separation.second),
            )

            assertEquals(portable.key, classUnderTest.resolveSourceIdentityAlias(unresolved.key))
            assertEquals(
                portable.key,
                classUnderTest.resolvePromotedParrotCloudSource(
                    unresolved.key,
                    CONNECTION_ID,
                    "cloud-user-a",
                ),
            )
            assertNull(
                classUnderTest.resolvePromotedParrotCloudSource(
                    unresolved.key,
                    CONNECTION_ID,
                    "cloud-user-b",
                ),
            )
            assertNull(
                classUnderTest.resolvePromotedParrotCloudSource(
                    unresolved.key,
                    SourceConnectionId("another-cloud-connection"),
                    "cloud-user-a",
                ),
            )
            assertEquals(
                payload,
                database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                    "profile-a",
                    "decision-pending",
                ).executeAsOne().payload,
            )
            assertEquals(
                payload,
                database.syncOutboxQueries.getEligibleMutations(
                    "cloud-user-a",
                    "2026-09-25T00:01:00Z",
                ).executeAsList().single().payload,
            )

            // Replaying a promotion is idempotent and returns the same portable identity.
            assertEquals(
                LibrarySourceIdentityPromotionStatus.AlreadyPromoted,
                classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable),
            )
        }

    @Test
    fun portableIdentitySyncAfterPairingBeforeFirstListingCreatesPortableMembership() =
        runBlocking {
            // Given: pairing is complete before this installation receives its first snapshot.
            val unresolved = unresolvedCloudSource()
            val portable = portableCloudSource("cloud-user-a", unresolved.key.nativeBookId.value)

            // When: the portable identity arrives before any unresolved membership exists.
            assertEquals(
                LibrarySourceIdentityPromotionStatus.NotFound,
                classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable),
            )
            val groupId = snapshotsDatabase.saveSnapshot(snapshot(portable))

            // Then: the first synced membership is portable and no unresolved alias is needed.
            assertEquals(groupId, groupsDatabase.getMembership(portable.key)?.groupId)
            assertNull(groupsDatabase.getMembership(unresolved.key))
            assertNull(snapshotsDatabase.getSnapshot(unresolved.key))
            assertNull(classUnderTest.resolveSourceIdentityAlias(unresolved.key))
        }

    @Test
    fun portableTargetCollisionLeavesUnresolvedStateUntouched() = runBlocking {
        // Given
        val unresolved = unresolvedCloudSource()
        val portable = portableCloudSource("cloud-user-a", unresolved.key.nativeBookId.value)
        val originalGroupId = snapshotsDatabase.saveSnapshot(snapshot(unresolved))
        val targetGroupId = groupsDatabase.ensureGroupForSource(
            portable,
            LibraryGroupId("group-existing-portable"),
            CREATED_AT,
        )
        val unresolvedDecisionPayload =
            "{\"decision_id\":\"unresolved-decision\",\"member\":\"unresolved\"}"
        val portableDecisionPayload =
            "{\"decision_id\":\"portable-decision\",\"member\":\"portable\"}"
        insertDecision("unresolved-decision", unresolvedDecisionPayload)
        insertDecision("portable-decision", portableDecisionPayload)
        database.syncOutboxQueries.enqueue(
            outboxEntry(unresolvedDecisionPayload, "unresolved-decision"),
        )
        database.syncOutboxQueries.enqueue(
            outboxEntry(portableDecisionPayload, "portable-decision"),
        )

        // When
        val status = classUnderTest.promoteUnresolvedSourceIdentity(
            unresolvedKey = unresolved.key,
            portableSource = portable,
        )

        // Then
        assertEquals(LibrarySourceIdentityPromotionStatus.Conflict, status)
        assertEquals(originalGroupId, groupsDatabase.getMembership(unresolved.key)?.groupId)
        assertEquals(targetGroupId, groupsDatabase.getMembership(portable.key)?.groupId)
        assertEquals(originalGroupId, snapshotsDatabase.getSnapshot(unresolved.key)?.let {
            groupsDatabase.getMembership(unresolved.key)?.groupId
        })
        assertNull(snapshotsDatabase.getSnapshot(portable.key))
        assertNull(classUnderTest.resolveSourceIdentityAlias(unresolved.key))
        assertEquals(
            1,
            database.libraryEvidenceQueries.getLibrarySourceResourcesForBook(
                "profile-a",
                LibrarySourceKeyCodec.encode(unresolved.key),
            ).executeAsList().size,
        )
        assertTrue(
            database.libraryEvidenceQueries.getLibrarySourceResourcesForBook(
                "profile-a",
                LibrarySourceKeyCodec.encode(portable.key),
            ).executeAsList().isEmpty(),
        )
        assertEquals(
            unresolvedDecisionPayload,
            getDecisionPayload("unresolved-decision"),
        )
        assertEquals(portableDecisionPayload, getDecisionPayload("portable-decision"))
        assertEquals(
            mapOf(
                "unresolved-decision" to unresolvedDecisionPayload,
                "portable-decision" to portableDecisionPayload,
            ),
            database.syncOutboxQueries.getEligibleMutations(
                "cloud-user-a",
                "2026-09-25T00:01:00Z",
            ).executeAsList().associate { entry -> entry.mutation_id to entry.payload },
        )
    }

    @Test
    fun explicitManualMergeAllowsSafePromotionAfterConflictWithoutChangingStoredBytes() =
        runBlocking {
            val unresolved = unresolvedCloudSource()
            val portable = portableCloudSource("cloud-user-a", unresolved.key.nativeBookId.value)
            val originalUnresolvedSnapshot = snapshot(unresolved)
            val originalPortableSnapshot = snapshot(portable)
            val unresolvedGroupId = snapshotsDatabase.saveSnapshot(originalUnresolvedSnapshot)
            val portableGroupId = snapshotsDatabase.saveSnapshot(originalPortableSnapshot)
            val unresolvedPayload = "unresolved decision payload bytes"
            val portablePayload = "portable decision payload bytes"
            val mergedPayload = "explicit manual merge payload bytes"
            val mergeDecisionId = "manual-merge-a"
            insertDecision("unresolved-decision", unresolvedPayload)
            insertDecision("portable-decision", portablePayload)
            database.syncOutboxQueries.enqueue(
                outboxEntry(unresolvedPayload, "unresolved-decision"),
            )
            database.syncOutboxQueries.enqueue(
                outboxEntry(portablePayload, "portable-decision"),
            )

            assertEquals(
                LibrarySourceIdentityPromotionStatus.Conflict,
                classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable),
            )
            assertSnapshotPreserved(
                originalUnresolvedSnapshot,
                snapshotsDatabase.getSnapshot(unresolved.key),
            )
            assertSnapshotPreserved(
                originalPortableSnapshot,
                snapshotsDatabase.getSnapshot(portable.key),
            )
            assertNull(classUnderTest.resolveSourceIdentityAlias(unresolved.key))

            groupsDatabase.applyManualMerge(
                LibraryManualGroupMerge(
                    profileId = PROFILE_ID,
                    newGroupId = LibraryGroupId("group-manual-merged"),
                    members = listOf(
                        LibraryManualGroupMemberSelection(unresolved.key, unresolvedGroupId),
                        LibraryManualGroupMemberSelection(portable.key, portableGroupId),
                    ),
                    decisionId = mergeDecisionId,
                    appliedAt = CREATED_AT,
                    outboxEntry = outboxEntry(mergedPayload, mergeDecisionId),
                ),
            )

            val status = classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable)

            assertEquals(LibrarySourceIdentityPromotionStatus.Promoted, status)
            assertNull(groupsDatabase.getMembership(unresolved.key))
            val promotedMembership = groupsDatabase.getMembership(portable.key)
            assertEquals(LibraryGroupId("group-manual-merged"), promotedMembership?.groupId)
            assertEquals(LibraryMembershipOrigin.Manual, promotedMembership?.origin)
            assertEquals(mergeDecisionId, promotedMembership?.decisionId)
            assertEquals(portable.key, classUnderTest.resolveSourceIdentityAlias(unresolved.key))
            assertSnapshotPreserved(
                originalUnresolvedSnapshot,
                snapshotsDatabase.getSnapshot(unresolved.key),
            )
            assertSnapshotPreserved(
                originalPortableSnapshot,
                snapshotsDatabase.getSnapshot(portable.key),
            )
            assertEquals(unresolvedPayload, getDecisionPayload("unresolved-decision"))
            assertEquals(portablePayload, getDecisionPayload("portable-decision"))
            assertEquals(mergedPayload, getDecisionPayload(mergeDecisionId))
            assertEquals(
                mapOf(
                    "unresolved-decision" to unresolvedPayload,
                    "portable-decision" to portablePayload,
                    mergeDecisionId to mergedPayload,
                ),
                database.syncOutboxQueries.getEligibleMutations(
                    "cloud-user-a",
                    "2026-09-25T00:01:00Z",
                ).executeAsList().associate { entry -> entry.mutation_id to entry.payload },
            )
        }

    @Test
    fun localPromotionRekeysToVerifiedHashAndRetainsTheImportedResourceUuid() = runBlocking {
        // Given
        val connectionId = SourceConnectionId("local-installation")
        val unresolved = SourceBookRef(
            key = SourceBookKey(
                profileId = PROFILE_ID,
                adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("imported-book-uuid"),
            ),
            connectionId = connectionId,
        )
        val contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val portable = SourceBookRef(
            key = SourceBookKey(
                profileId = PROFILE_ID,
                adapterId = LibraryAdapterId(LocalContentIdentity.ADAPTER_ID),
                accountIdentity = SourceAccountIdentity.Portable(
                    LocalContentIdentity.BACKEND_ID,
                    LocalContentIdentity.ACCOUNT_ID,
                ),
                nativeBookId = LocalContentIdentity.nativeBookId(contentHash),
            ),
            connectionId = connectionId,
        )
        val groupId = snapshotsDatabase.saveSnapshot(
            snapshot(unresolved).copy(
                resources = listOf(
                    SourceMediaResource(
                        reference = SourceResourceRef(unresolved.key, "imported-book-uuid"),
                        mediaType = "ebook",
                        availability = SourceResourceAvailability.DevicePresent,
                        localStorageReference = DeviceStorageRef("/imports/book.epub"),
                    ),
                ),
            ),
        )

        // When
        val status = classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable)

        // Then
        assertEquals(LibrarySourceIdentityPromotionStatus.Promoted, status)
        assertEquals(groupId, groupsDatabase.getMembership(portable.key)?.groupId)
        assertNull(groupsDatabase.getMembership(unresolved.key))
        assertEquals(portable.key, classUnderTest.resolveSourceIdentityAlias(unresolved.key))
        val promotedSnapshot = snapshotsDatabase.getSnapshot(portable.key)
        assertEquals(
            "imported-book-uuid",
            promotedSnapshot?.resources?.single()?.reference?.nativeResourceId,
        )
        assertEquals(portable.key, promotedSnapshot?.resources?.single()?.reference?.book)
    }

    @Test
    fun authenticatedAdapterCanPromoteSameNativeIdWithoutCloudSpecificResolver() = runBlocking {
        // Given
        val connectionId = SourceConnectionId("abs-connection")
        val unresolved = SourceBookRef(
            key = SourceBookKey(
                profileId = PROFILE_ID,
                adapterId = LibraryAdapterId("audiobookshelf"),
                accountIdentity = SourceAccountIdentity.Unresolved(connectionId),
                nativeBookId = NativeBookId("abs-book"),
            ),
            connectionId = connectionId,
        )
        val portable = SourceBookRef(
            key = SourceBookKey(
                profileId = PROFILE_ID,
                adapterId = LibraryAdapterId("audiobookshelf"),
                accountIdentity = SourceAccountIdentity.Portable(
                    "audiobookshelf",
                    "authenticated-abs-account",
                ),
                nativeBookId = NativeBookId("abs-book"),
            ),
            connectionId = connectionId,
        )
        snapshotsDatabase.saveSnapshot(snapshot(unresolved))

        // When
        val status = classUnderTest.promoteUnresolvedSourceIdentity(unresolved.key, portable)

        // Then
        assertEquals(LibrarySourceIdentityPromotionStatus.Promoted, status)
        assertEquals(portable.key, classUnderTest.resolveSourceIdentityAlias(unresolved.key))
        assertNull(groupsDatabase.getMembership(unresolved.key))
        assertEquals(portable.key, groupsDatabase.getMembership(portable.key)?.source?.key)
        assertNull(
            classUnderTest.resolvePromotedParrotCloudSource(
                unresolved.key,
                connectionId,
                "authenticated-abs-account",
            ),
        )
    }

    @Test
    fun bulkPromotionOnlyMovesParrotCloudSourcesFromTheLinkedConnection() = runBlocking {
        // Given
        val cloud = unresolvedCloudSource()
        val other = SourceBookRef(
            key = SourceBookKey(
                profileId = PROFILE_ID,
                adapterId = LibraryAdapterId("audiobookshelf"),
                accountIdentity = SourceAccountIdentity.Unresolved(
                    SourceConnectionId("abs-connection"),
                ),
                nativeBookId = NativeBookId("abs-book"),
            ),
            connectionId = SourceConnectionId("abs-connection"),
        )
        groupsDatabase.ensureGroupForSource(cloud, LibraryGroupId("group-cloud"), CREATED_AT)
        groupsDatabase.ensureGroupForSource(other, LibraryGroupId("group-abs"), CREATED_AT)

        // When
        val results = classUnderTest.promoteUnresolvedParrotCloudSources(
            profileId = PROFILE_ID,
            connectionId = CONNECTION_ID,
            cloudUserId = "cloud-user-a",
        )

        // Then
        assertEquals(
            listOf("cloud-book" to LibrarySourceIdentityPromotionStatus.Promoted),
            results.map { result -> result.nativeBookId to result.status },
        )
        assertEquals(SourceAccountIdentity.Unresolved(SourceConnectionId("abs-connection")),
            groupsDatabase.getMembership(other.key)?.source?.key?.accountIdentity)
    }

    private fun unresolvedCloudSource() = SourceBookRef(
        key = SourceBookKey(
            profileId = PROFILE_ID,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Unresolved(CONNECTION_ID),
            nativeBookId = NativeBookId("cloud-book"),
        ),
        connectionId = CONNECTION_ID,
        legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:legacy-hash"),
    )

    private fun portableCloudSource(cloudUserId: String, nativeBookId: String) = SourceBookRef(
        key = SourceBookKey(
            profileId = PROFILE_ID,
            adapterId = LibraryAdapterId("parrot-cloud"),
            accountIdentity = SourceAccountIdentity.Portable("parrot-cloud", cloudUserId),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = CONNECTION_ID,
        legacyLibraryBookId = LegacyLibraryBookId("sha-256-v1:legacy-hash"),
    )

    private fun portableAudiobookshelfSource() = SourceBookRef(
        key = SourceBookKey(
            profileId = PROFILE_ID,
            adapterId = LibraryAdapterId("audiobookshelf"),
            accountIdentity = SourceAccountIdentity.Portable("audiobookshelf", "abs-account"),
            nativeBookId = NativeBookId("abs-book"),
        ),
        connectionId = SourceConnectionId("abs-connection"),
    )

    private fun snapshot(source: SourceBookRef) = SourceBookSnapshot(
        source = source,
        metadata = SourceBookMetadata(
            title = "Promoted book",
            description = "Keep source metadata",
            authors = listOf("Author"),
        ),
        resources = listOf(
            SourceMediaResource(
                reference = SourceResourceRef(source.key, "cloud-file-id", "revision-1"),
                mediaType = "ebook",
                format = "epub",
                sizeBytes = 4096,
                availability = SourceResourceAvailability.DevicePresent,
                localStorageReference = DeviceStorageRef("device-file-reference"),
            ),
        ),
        identityEvidence = listOf(
            SourceIdentityEvidence.BookFingerprint(
                book = source.key,
                algorithm = "sha-256-v1",
                hash = "verified-content-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
            SourceIdentityEvidence.FileFingerprint(
                resource = SourceResourceRef(source.key, "cloud-file-id", "revision-1"),
                algorithm = "sha-256-v1",
                hash = "verified-content-hash",
                scope = FingerprintScope.WholeFile,
                verification = FingerprintVerification.Verified,
            ),
        ),
        status = SourceSnapshotStatus(
            observedAt = Instant.parse(CREATED_AT),
            presence = SourcePresence.Present,
            isAuthoritative = true,
        ),
    )

    private fun assertSnapshotPreserved(
        expected: SourceBookSnapshot,
        actual: SourceBookSnapshot?,
    ) {
        assertEquals(expected.source, actual?.source)
        assertEquals(expected.metadata, actual?.metadata)
        assertEquals(expected.resources.toSet(), actual?.resources?.toSet())
        assertEquals(expected.identityEvidence.toSet(), actual?.identityEvidence?.toSet())
        assertEquals(expected.status, actual?.status)
    }

    private fun insertDecision(decisionId: String, payload: String) {
        database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
            profile_id = "profile-a",
            decision_id = decisionId,
            server_revision = 0L,
            payload = payload,
            created_at = CREATED_AT,
        )
    }

    private fun getDecisionPayload(decisionId: String) =
        database.libraryGroupDecisionQueries.getLibraryGroupDecision(
            "profile-a",
            decisionId,
        ).executeAsOne().payload

    private fun outboxEntry(
        payload: String,
        mutationId: String = "decision-pending",
    ) = SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = "cloud-user-a",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
        entityId = mutationId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = payload,
        baseRevision = null,
        createdAt = CREATED_AT,
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
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

    private companion object {
        val PROFILE_ID = LibraryProfileId("profile-a")
        val CONNECTION_ID = SourceConnectionId("parrot-cloud")
        const val CREATED_AT = "2026-09-25T00:00:00Z"
    }
}
