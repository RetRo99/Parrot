package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMemberSelection
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryMediaPreferenceChange
import com.retro99.database.api.library.LibraryMediaPreferenceChangeResult
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.library.LibraryGroupsSqlDelightDao
import com.retro99.database.implementation.dao.library.LibrarySourceKeyCodec
import com.retro99.database.implementation.dao.library.deleteOrphanedLibraryBookState
import com.retro99.database.implementation.dao.sync.enqueue
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryGroupsSqlDelightDaoTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var session: ActiveProfileSession
    private lateinit var classUnderTest: LibraryGroupsDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        session = ActiveProfileSession("profile-a")
        classUnderTest = LibraryGroupsSqlDelightDao(session) { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun replayKeepsThePersistedGroupAndDoesNotCreateAnOrphan() = runBlocking {
        // Given
        val source = portableSource("profile-a", "account-a", "native-book")
        val firstId = LibraryGroupId("group-first")
        val replayId = LibraryGroupId("group-replay")

        // When
        val created = classUnderTest.ensureGroupForSource(source, firstId, CREATED_AT)
        val refreshed = source.copy(connectionId = SourceConnectionId("connection-new"))
        val replayed = classUnderTest.ensureGroupForSource(refreshed, replayId, CREATED_AT)

        // Then
        assertEquals(firstId, created)
        assertEquals(firstId, replayed)
        assertEquals(firstId, classUnderTest.getMembership(source.key)?.groupId)
        assertEquals(
            SourceConnectionId("connection-new"),
            classUnderTest.getMembership(source.key)?.source?.connectionId,
        )
        assertNull(classUnderTest.getGroup(source.key.profileId, replayId))
        assertEquals(1, classUnderTest.getMemberships(source.key.profileId, firstId).size)
    }

    @Test
    fun projectionObserverEmitsForManualMergeAndSplitWithoutSourceUpdates() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val first = portableSource("profile-a", "account-a", "native-first")
        val second = portableSource("profile-a", "account-a", "native-second")
        val firstGroupId = LibraryGroupId("group-first")
        val secondGroupId = LibraryGroupId("group-second")
        val mergedGroupId = LibraryGroupId("group-merged")
        val splitGroupId = LibraryGroupId("group-split")
        classUnderTest.ensureGroupForSource(first, firstGroupId, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroupId, CREATED_AT)
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            classUnderTest.observeProjectionChanges(profileId).collect { emissions.send(Unit) }
        }

        try {
            withTimeout(5_000) { emissions.receive() }

            // When
            classUnderTest.applyManualMerge(
                LibraryManualGroupMerge(
                    profileId = profileId,
                    newGroupId = mergedGroupId,
                    members = listOf(
                        LibraryManualGroupMemberSelection(first.key, firstGroupId),
                        LibraryManualGroupMemberSelection(second.key, secondGroupId),
                    ),
                    decisionId = "decision-merge",
                    appliedAt = CREATED_AT,
                ),
            )
            withTimeout(5_000) { emissions.receive() }

            // Then
            assertEquals(mergedGroupId, classUnderTest.getMembership(first.key)?.groupId)
            assertEquals(mergedGroupId, classUnderTest.getMembership(second.key)?.groupId)

            // When
            classUnderTest.applyManualSplit(
                LibraryManualGroupSplit(
                    profileId = profileId,
                    sourceGroupId = mergedGroupId,
                    newGroupId = splitGroupId,
                    movedSourceKeys = listOf(second.key),
                    retainedSourceKeys = listOf(first.key),
                    decisionId = "decision-split",
                    appliedAt = CREATED_AT,
                ),
            )
            withTimeout(5_000) { emissions.receive() }

            // Then
            assertEquals(mergedGroupId, classUnderTest.getMembership(first.key)?.groupId)
            assertEquals(splitGroupId, classUnderTest.getMembership(second.key)?.groupId)

            // When an incoming synchronized merge changes membership without source updates.
            val incomingGroupId = LibraryGroupId("group-incoming")
            val decisionApplied = classUnderTest.applySynchronizedDecision(
                LibrarySynchronizedGroupDecision(
                    profileId = profileId,
                    decisionId = "decision-incoming",
                    revision = 1L,
                    operation = "merge",
                    targetGroupId = incomingGroupId,
                    members = listOf(first.key, second.key),
                    payload = "{}",
                    appliedAt = CREATED_AT,
                ),
            )
            withTimeout(5_000) { emissions.receive() }

            // Then
            assertTrue(decisionApplied)
            assertEquals(incomingGroupId, classUnderTest.getMembership(first.key)?.groupId)
            assertEquals(incomingGroupId, classUnderTest.getMembership(second.key)?.groupId)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun groupIdCollisionRollsBackTheNewMembership() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "native-first")
        val second = portableSource("profile-a", "account-a", "native-second")
        val groupId = LibraryGroupId("shared-group-id")
        classUnderTest.ensureGroupForSource(first, groupId, CREATED_AT)

        // When
        assertFailsWith<IllegalStateException> {
            classUnderTest.ensureGroupForSource(second, groupId, CREATED_AT)
        }

        // Then
        assertNull(classUnderTest.getMembership(second.key))
        assertEquals(listOf(first.key), classUnderTest.getMemberships(
            first.key.profileId,
            groupId,
        ).map { member -> member.source.key })
    }

    @Test
    fun inactiveProfileCannotReadOrWriteAndAccountsRemainDistinct() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "native-book")
        val second = portableSource("profile-b", "account-a", "native-book")
        val third = portableSource("profile-a", "account-b", "native-book")
        classUnderTest.ensureGroupForSource(first, LibraryGroupId("group-a"), CREATED_AT)
        classUnderTest.ensureGroupForSource(third, LibraryGroupId("group-c"), CREATED_AT)

        // When
        assertFailsWith<IllegalStateException> {
            classUnderTest.ensureGroupForSource(second, LibraryGroupId("group-b"), CREATED_AT)
        }
        assertFailsWith<IllegalStateException> { classUnderTest.getMembership(second.key) }
        session.activeProfileId = "profile-b"
        val created = classUnderTest.ensureGroupForSource(
            second,
            LibraryGroupId("group-b"),
            CREATED_AT,
        )

        // Then
        assertEquals(LibraryGroupId("group-b"), created)
        assertEquals(LibraryGroupId("group-b"), classUnderTest.getMembership(second.key)?.groupId)
        session.activeProfileId = "profile-a"
        assertEquals(LibraryGroupId("group-a"), classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(LibraryGroupId("group-c"), classUnderTest.getMembership(third.key)?.groupId)
    }

    @Test
    fun legacyOrphanCleanupDoesNotDeleteTheGroupOrSourceMembership() = runBlocking {
        // Given
        val legacyId = "sha-256-v1:hash-a"
        val source = portableSource("profile-a", "account-a", "native-book").copy(
            legacyLibraryBookId = LegacyLibraryBookId(legacyId),
        )
        val groupId = LibraryGroupId("persistent-group")
        database.libraryBookQueries.upsertLibraryBook(
            library_book_id = legacyId,
            content_hash = "hash-a",
            content_hash_algorithm = "sha-256-v1",
            title = "Imported book",
            author = null,
            format = "ebook",
            remote_revision = null,
            deleted_at = null,
            cloud_book_id = null,
            metadata_json = null,
        )
        classUnderTest.ensureGroupForSource(source, groupId, CREATED_AT)

        // When
        database.deleteOrphanedLibraryBookState()

        // Then
        assertNull(database.libraryBookQueries.getLibraryBookById(legacyId).executeAsOneOrNull())
        assertEquals(groupId, classUnderTest.getMembership(source.key)?.groupId)
        assertEquals(groupId, classUnderTest.getGroup(source.key.profileId, groupId)?.groupId)
    }

    @Test
    fun aliasesResolveThroughChainsAndRejectCyclesOrConflictingTargets() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val first = LibraryGroupId("group-first")
        val second = LibraryGroupId("group-second")
        val third = LibraryGroupId("group-third")
        classUnderTest.ensureGroupForSource(
            portableSource("profile-a", "account-a", "book-first"),
            first,
            CREATED_AT,
        )
        classUnderTest.ensureGroupForSource(
            portableSource("profile-a", "account-a", "book-second"),
            second,
            CREATED_AT,
        )
        classUnderTest.ensureGroupForSource(
            portableSource("profile-a", "account-a", "book-third"),
            third,
            CREATED_AT,
        )

        // When
        classUnderTest.addGroupAlias(profileId, first, second, CREATED_AT)
        classUnderTest.addGroupAlias(profileId, second, third, CREATED_AT)
        classUnderTest.addGroupAlias(profileId, first, second, CREATED_AT)

        // Then
        assertEquals(third, classUnderTest.resolveGroupId(profileId, first))
        assertFailsWith<IllegalStateException> {
            classUnderTest.addGroupAlias(profileId, third, first, CREATED_AT)
        }
        assertFailsWith<IllegalStateException> {
            classUnderTest.addGroupAlias(profileId, first, third, CREATED_AT)
        }
        assertNull(classUnderTest.resolveGroupId(profileId, LibraryGroupId("missing-group")))
        assertEquals(third, classUnderTest.resolveGroupId(profileId, first))
    }

    @Test
    fun manualSeparationIsSymmetricAndRetainsScopedSources() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account:a", "book").key
        val second = portableSource("profile-a", "account", "book:a").key
        val otherProfile = portableSource("profile-b", "account:a", "book").key

        // When
        val inserted = classUnderTest.addManualSeparation(
            first,
            second,
            "decision-first",
            CREATED_AT,
        )
        val repeated = classUnderTest.addManualSeparation(
            second,
            first,
            "decision-replay",
            CREATED_AT,
        )

        // Then
        assertEquals(true, inserted)
        assertEquals(false, repeated)
        val separations = classUnderTest.getManualSeparations(first.profileId)
        assertEquals(1, separations.size)
        assertEquals(
            setOf(first, second),
            setOf(separations.single().first, separations.single().second),
        )
        assertEquals("decision-first", separations.single().decisionId)
        assertFailsWith<IllegalArgumentException> {
            classUnderTest.addManualSeparation(first, otherProfile, "cross-profile", CREATED_AT)
        }
        assertFailsWith<IllegalArgumentException> {
            classUnderTest.addManualSeparation(first, first, "self", CREATED_AT)
        }
        Unit
    }

    @Test
    fun automaticMergeMovesAllMembersAndRedirectsRetiredGroupsIdempotently() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val survivor = LibraryGroupId("group-a")
        val retired = LibraryGroupId("group-b")
        classUnderTest.ensureGroupForSource(first, survivor, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, retired, CREATED_AT)
        val merge = LibraryAutomaticGroupMerge(
            profileId = LibraryProfileId("profile-a"),
            survivorGroupId = survivor,
            retiredGroupIds = setOf(retired),
            sourceKeys = setOf(first.key, second.key),
            evidenceIds = setOf("fingerprint-first", "fingerprint-second"),
            appliedAt = CREATED_AT,
        )

        // When
        val applied = classUnderTest.applyAutomaticMerge(merge)
        val replayed = classUnderTest.applyAutomaticMerge(merge)

        // Then
        assertEquals(true, applied)
        assertEquals(false, replayed)
        assertEquals(survivor, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(survivor, classUnderTest.getMembership(second.key)?.groupId)
        assertEquals(
            LibraryMembershipOrigin.Automatic,
            classUnderTest.getMembership(second.key)?.origin,
        )
        assertEquals(
            survivor,
            classUnderTest.resolveGroupId(LibraryProfileId("profile-a"), retired),
        )
        assertEquals(setOf(first.key, second.key), classUnderTest.getMemberships(
            LibraryProfileId("profile-a"),
            survivor,
        ).map { member -> member.source.key }.toSet())
    }

    @Test
    fun automaticMergeCannotOverrideAManualSeparation() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        classUnderTest.addManualSeparation(first.key, second.key, "split-decision", CREATED_AT)

        // When / Then
        assertFailsWith<IllegalStateException> {
            classUnderTest.applyAutomaticMerge(
                LibraryAutomaticGroupMerge(
                    profileId = LibraryProfileId("profile-a"),
                    survivorGroupId = firstGroup,
                    retiredGroupIds = setOf(secondGroup),
                    sourceKeys = setOf(first.key, second.key),
                    evidenceIds = setOf("verified-match"),
                    appliedAt = CREATED_AT,
                ),
            )
        }
        assertEquals(firstGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(secondGroup, classUnderTest.getMembership(second.key)?.groupId)
        Unit
    }

    @Test
    fun manualMergeMovesOnlyNamedMembersAndOverridesOnlyTheirSeparation() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val firstRemainder = portableSource("profile-a", "account-a", "book-first-remainder")
        val second = portableSource("profile-a", "account-a", "book-second")
        val third = portableSource("profile-a", "account-a", "book-third")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val thirdGroup = LibraryGroupId("group-third")
        val mergedGroup = LibraryGroupId("group-manual-merged")
        val profileId = LibraryProfileId("profile-a")
        createGroupWithSources(firstGroup, listOf(first, firstRemainder))
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(third, thirdGroup, CREATED_AT)
        classUnderTest.addManualSeparation(
            first.key,
            second.key,
            "separate-first-second",
            CREATED_AT,
        )
        classUnderTest.addManualSeparation(
            second.key,
            third.key,
            "separate-second-third",
            CREATED_AT,
        )

        // When
        classUnderTest.applyManualMerge(
            LibraryManualGroupMerge(
                profileId = profileId,
                newGroupId = mergedGroup,
                members = listOf(
                    LibraryManualGroupMemberSelection(first.key, firstGroup),
                    LibraryManualGroupMemberSelection(second.key, secondGroup),
                ),
                decisionId = "manual-merge",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        assertEquals(mergedGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(mergedGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertEquals(
            LibraryMembershipOrigin.Manual,
            classUnderTest.getMembership(first.key)?.origin,
        )
        assertEquals("manual-merge", classUnderTest.getMembership(second.key)?.decisionId)
        assertEquals(firstGroup, classUnderTest.getMembership(firstRemainder.key)?.groupId)
        assertEquals(firstGroup, classUnderTest.resolveGroupId(profileId, firstGroup))
        assertEquals(mergedGroup, classUnderTest.resolveGroupId(profileId, secondGroup))
        assertEquals(thirdGroup, classUnderTest.resolveGroupId(profileId, thirdGroup))
        val activeSeparationPairs = classUnderTest.getManualSeparations(profileId)
            .map { separation -> setOf(separation.first, separation.second) }
            .toSet()
        assertEquals(
            setOf(
                setOf(first.key, firstRemainder.key),
                setOf(second.key, third.key),
            ),
            activeSeparationPairs,
        )
        val overridden = database.libraryGroupDecisionQueries.getLibraryManualSeparation(
            profileId.value,
            encodedPair(first.key, second.key).first,
            encodedPair(first.key, second.key).second,
        ).executeAsOne()
        assertEquals("manual-merge", overridden.overridden_by_decision_id)
    }

    @Test
    fun manualMergeRejectsStaleAndCrossProfileSelectionsWithoutWriting() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val targetGroup = LibraryGroupId("group-target")
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)

        // When / Then
        assertFailsWith<IllegalStateException> {
            classUnderTest.applyManualMerge(
                LibraryManualGroupMerge(
                    profileId = LibraryProfileId("profile-a"),
                    newGroupId = targetGroup,
                    members = listOf(
                        LibraryManualGroupMemberSelection(first.key, LibraryGroupId("stale-group")),
                        LibraryManualGroupMemberSelection(second.key, secondGroup),
                    ),
                    decisionId = "stale-merge",
                    appliedAt = CREATED_AT,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            classUnderTest.applyManualMerge(
                LibraryManualGroupMerge(
                    profileId = LibraryProfileId("profile-a"),
                    newGroupId = targetGroup,
                    members = listOf(
                        LibraryManualGroupMemberSelection(first.key, firstGroup),
                        LibraryManualGroupMemberSelection(
                            portableSource("profile-b", "account-a", "book-third").key,
                            LibraryGroupId("group-third"),
                        ),
                    ),
                    decisionId = "cross-profile-merge",
                    appliedAt = CREATED_AT,
                ),
            )
        }

        assertNull(classUnderTest.getGroup(LibraryProfileId("profile-a"), targetGroup))
        assertEquals(firstGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(secondGroup, classUnderTest.getMembership(second.key)?.groupId)
    }

    @Test
    fun manualMergeAndOutboxEnqueueRollBackTogetherOnOutboxFailure() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val targetGroup = LibraryGroupId("group-target")
        val profileId = LibraryProfileId("profile-a")
        val decisionId = "duplicate-outbox-id"
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        val existingOutboxEntry = outboxEntry(
            mutationId = decisionId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
            entityId = "other-entity",
        )
        database.syncOutboxQueries.enqueue(existingOutboxEntry)

        // When
        assertFails {
            classUnderTest.applyManualMerge(
                LibraryManualGroupMerge(
                    profileId = profileId,
                    newGroupId = targetGroup,
                    members = listOf(
                        LibraryManualGroupMemberSelection(first.key, firstGroup),
                        LibraryManualGroupMemberSelection(second.key, secondGroup),
                    ),
                    decisionId = decisionId,
                    appliedAt = CREATED_AT,
                    outboxEntry = outboxEntry(
                        mutationId = decisionId,
                        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                        entityId = decisionId,
                    ),
                ),
            )
        }

        // Then
        assertNull(classUnderTest.getGroup(profileId, targetGroup))
        assertEquals(firstGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(secondGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertEquals(
            listOf(existingOutboxEntry.mutationId),
            database.syncOutboxQueries.getPendingMutations("cloud-user")
                .executeAsList()
                .map { entry -> entry.mutation_id },
        )
        assertNull(
            database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                profileId.value,
                decisionId,
            ).executeAsOneOrNull(),
        )
    }

    @Test
    fun acceptedMergeRevisionUpdatesDecisionMembershipAndMetadataPreference() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val targetGroup = LibraryGroupId("group-target")
        val profileId = LibraryProfileId("profile-a")
        val decisionId = "accepted-merge"
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        val entry = outboxEntry(
            mutationId = decisionId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            entityId = decisionId,
        )
        classUnderTest.applyManualMerge(
            LibraryManualGroupMerge(
                profileId = profileId,
                newGroupId = targetGroup,
                members = listOf(
                    LibraryManualGroupMemberSelection(first.key, firstGroup),
                    LibraryManualGroupMemberSelection(second.key, secondGroup),
                ),
                decisionId = decisionId,
                appliedAt = CREATED_AT,
                preferredMetadataSourceKey = second.key,
                outboxEntry = entry,
            ),
        )

        // When
        classUnderTest.recordAcceptedDecision(
            profileId = profileId,
            decisionId = decisionId,
            revision = 7L,
            payload = "canonical-payload",
        )

        // Then
        val decision = database.libraryGroupDecisionQueries.getLibraryGroupDecision(
            profileId.value,
            decisionId,
        ).executeAsOne()
        assertEquals(7L, decision.server_revision)
        assertEquals("canonical-payload", decision.payload)
        assertEquals(7L, classUnderTest.getMembership(first.key)?.revision)
        val metadataPreference = database.libraryGroupDecisionQueries
            .getLibraryGroupMetadataPreference(profileId.value, targetGroup.value)
            .executeAsOne()
        assertEquals(
            second.key,
            LibrarySourceKeyCodec.decode(profileId, metadataPreference.source_key),
        )
        assertEquals(7L, metadataPreference.server_revision)
    }

    @Test
    fun synchronizedMergeIsAppliedOnceAndRetainsPortableUnconnectedMembers() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first").copy(
            connectionId = null,
        )
        val second = portableSource("profile-a", "account-a", "book-second").copy(
            connectionId = null,
        )
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val targetGroup = LibraryGroupId("group-synchronized")
        val profileId = LibraryProfileId("profile-a")
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        val decision = LibrarySynchronizedGroupDecision(
            profileId = profileId,
            decisionId = "remote-merge",
            revision = 3L,
            operation = "merge",
            targetGroupId = targetGroup,
            members = listOf(first.key, second.key),
            preferredMediaSourceKeys = mapOf(
                "ebook" to first.key,
                "audiobook" to second.key,
            ),
            payload = "remote-payload",
            appliedAt = CREATED_AT,
        )

        // When
        val firstApplication = classUnderTest.applySynchronizedDecision(decision)
        val replayApplication = classUnderTest.applySynchronizedDecision(decision)

        // Then
        assertEquals(true, firstApplication)
        assertEquals(false, replayApplication)
        assertEquals(targetGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(targetGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertNull(classUnderTest.getMembership(first.key)?.source?.connectionId)
        assertEquals(
            mapOf("ebook" to first.key, "audiobook" to second.key),
            classUnderTest.getGroup(profileId, targetGroup)?.preferredMediaSourceKeys,
        )
        val mediaPreferences = database.libraryGroupDecisionQueries
            .getLibraryGroupMediaPreferences(profileId.value, targetGroup.value)
            .executeAsList()
        assertEquals(setOf("ebook", "audiobook"), mediaPreferences.map { row ->
            row.media_type
        }.toSet())
        assertTrue(mediaPreferences.all { preference -> preference.server_revision == 3L })
        assertEquals(
            1,
            database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                profileId.value,
                decision.decisionId,
            ).executeAsList().size,
        )
    }

    @Test
    fun mediaPreferenceChangeStoresPreferenceDecisionAndOutboxTogether() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val groupId = LibraryGroupId("group-media-preference")
        val source = portableSource("profile-a", "account-a", "book-preferred")
        val decisionId = "decision-media-preference"
        classUnderTest.ensureGroupForSource(source, groupId, CREATED_AT)
        val entry = outboxEntry(
            mutationId = decisionId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            entityId = decisionId,
        )

        // When
        val result = classUnderTest.applyMediaPreference(
            LibraryMediaPreferenceChange(
                profileId = profileId,
                activeGroupId = groupId,
                mediaType = "audiobook",
                memberSourceKey = source.key,
                decisionId = decisionId,
                appliedAt = CREATED_AT,
                outboxEntry = entry,
            ),
        )

        // Then
        assertEquals(LibraryMediaPreferenceChangeResult.Applied, result)
        assertEquals(
            mapOf("audiobook" to source.key),
            classUnderTest.getGroup(profileId, groupId)?.preferredMediaSourceKeys,
        )
        val decision = database.libraryGroupDecisionQueries.getLibraryGroupDecision(
            profileId.value,
            decisionId,
        ).executeAsOne()
        assertEquals(0L, decision.server_revision)
        assertEquals(entry.payload, decision.payload)
        assertEquals(
            listOf(entry.mutationId),
            database.syncOutboxQueries.getPendingMutations("cloud-user")
                .executeAsList()
                .map { mutation -> mutation.mutation_id },
        )
    }

    @Test
    fun mediaPreferenceChangeReturnsStaleWhenMemberMovedFromActiveGroup() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val activeGroupId = LibraryGroupId("group-media-active")
        val currentGroupId = LibraryGroupId("group-media-current")
        val source = portableSource("profile-a", "account-a", "book-moved")
        val remainingMember = portableSource("profile-a", "account-a", "book-still-active")
        val decisionId = "decision-stale-media-preference"
        classUnderTest.ensureGroupForSource(remainingMember, activeGroupId, CREATED_AT)
        classUnderTest.ensureGroupForSource(source, currentGroupId, CREATED_AT)
        val entry = outboxEntry(
            mutationId = decisionId,
            entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
            entityId = decisionId,
        )

        // When
        val result = classUnderTest.applyMediaPreference(
            LibraryMediaPreferenceChange(
                profileId = profileId,
                activeGroupId = activeGroupId,
                mediaType = "ebook",
                memberSourceKey = source.key,
                decisionId = decisionId,
                appliedAt = CREATED_AT,
                outboxEntry = entry,
            ),
        )

        // Then
        assertEquals(LibraryMediaPreferenceChangeResult.StaleMembership, result)
        assertEquals(activeGroupId, classUnderTest.getGroup(profileId, activeGroupId)?.groupId)
        assertEquals(
            emptyMap(),
            classUnderTest.getGroup(profileId, activeGroupId)?.preferredMediaSourceKeys,
        )
        assertNull(
            database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                profileId.value,
                decisionId,
            ).executeAsOneOrNull(),
        )
        assertEquals(
            emptyList(),
            database.syncOutboxQueries.getPendingMutations("cloud-user").executeAsList(),
        )
    }

    @Test
    fun settingTheCurrentMediaPreferenceAgainDoesNotCreateAnotherDecisionOrOutboxEntry() =
        runBlocking {
            // Given
            val profileId = LibraryProfileId("profile-a")
            val groupId = LibraryGroupId("group-media-idempotent")
            val source = portableSource("profile-a", "account-a", "book-same")
            classUnderTest.ensureGroupForSource(source, groupId, CREATED_AT)
            val firstDecisionId = "decision-media-first"
            val firstEntry = outboxEntry(
                mutationId = firstDecisionId,
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                entityId = firstDecisionId,
            )
            val firstChange = LibraryMediaPreferenceChange(
                profileId = profileId,
                activeGroupId = groupId,
                mediaType = "readaloud",
                memberSourceKey = source.key,
                decisionId = firstDecisionId,
                appliedAt = CREATED_AT,
                outboxEntry = firstEntry,
            )
            classUnderTest.applyMediaPreference(firstChange)
            val retryDecisionId = "decision-media-retry"
            val retryEntry = outboxEntry(
                mutationId = retryDecisionId,
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                entityId = retryDecisionId,
            )

            // When
            val result = classUnderTest.applyMediaPreference(
                firstChange.copy(
                    decisionId = retryDecisionId,
                    outboxEntry = retryEntry,
                ),
            )

            // Then
            assertEquals(LibraryMediaPreferenceChangeResult.AlreadyPreferred, result)
            assertNull(
                database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                    profileId.value,
                    retryDecisionId,
                ).executeAsOneOrNull(),
            )
            assertEquals(
                listOf(firstEntry.mutationId),
                database.syncOutboxQueries.getPendingMutations("cloud-user")
                    .executeAsList()
                    .map { mutation -> mutation.mutation_id },
            )
            assertEquals(
                mapOf("readaloud" to source.key),
                classUnderTest.getGroup(profileId, groupId)?.preferredMediaSourceKeys,
            )
        }

    @Test
    fun synchronizedDecisionRejectsMediaPreferenceOutsideSelectedMembers() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val outside = portableSource("profile-a", "account-a", "book-outside")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val profileId = LibraryProfileId("profile-a")
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        val decision = LibrarySynchronizedGroupDecision(
            profileId = profileId,
            decisionId = "remote-invalid-media-preference",
            revision = 4L,
            operation = "merge",
            targetGroupId = LibraryGroupId("group-invalid-preference"),
            members = listOf(first.key, second.key),
            preferredMediaSourceKeys = mapOf("ebook" to outside.key),
            payload = "remote-payload",
            appliedAt = CREATED_AT,
        )

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            classUnderTest.applySynchronizedDecision(decision)
        }
        assertNull(classUnderTest.getGroup(profileId, decision.targetGroupId))
    }

    @Test
    fun olderSynchronizedRevisionCannotOverwriteANewerDecision() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val mergedGroup = LibraryGroupId("group-stale-merge")
        val profileId = LibraryProfileId("profile-a")
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)

        // When
        val newestApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = "newer-preference",
                revision = 2L,
                operation = "preferences",
                targetGroupId = firstGroup,
                members = listOf(first.key),
                preferredMetadataSourceKey = first.key,
                payload = "newer-payload",
                appliedAt = CREATED_AT,
            ),
        )
        val staleApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = "older-merge",
                revision = 1L,
                operation = "merge",
                targetGroupId = mergedGroup,
                members = listOf(first.key, second.key),
                payload = "older-payload",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        assertEquals(true, newestApplied)
        assertEquals(false, staleApplied)
        assertEquals(firstGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(secondGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertNull(classUnderTest.getGroup(profileId, mergedGroup))
    }

    @Test
    fun acceptedLocalMergeEchoRebasesOverEarlierRemoteSplitExactlyOnce() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val firstGroup = LibraryGroupId("group-first")
        val secondGroup = LibraryGroupId("group-second")
        val localMergedGroup = LibraryGroupId("group-local-merge")
        val remoteSplitGroup = LibraryGroupId("group-remote-split")
        val profileId = LibraryProfileId("profile-a")
        val decisionId = "local-merge-accepted-second"
        classUnderTest.ensureGroupForSource(first, firstGroup, CREATED_AT)
        classUnderTest.ensureGroupForSource(second, secondGroup, CREATED_AT)
        classUnderTest.applyManualMerge(
            LibraryManualGroupMerge(
                profileId = profileId,
                newGroupId = localMergedGroup,
                members = listOf(
                    LibraryManualGroupMemberSelection(first.key, firstGroup),
                    LibraryManualGroupMemberSelection(second.key, secondGroup),
                ),
                decisionId = decisionId,
                appliedAt = CREATED_AT,
                outboxEntry = outboxEntry(
                    mutationId = decisionId,
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                    entityId = decisionId,
                ),
            ),
        )
        classUnderTest.recordAcceptedDecision(
            profileId = profileId,
            decisionId = decisionId,
            revision = 2L,
            payload = "accepted-local-payload",
        )

        // When
        val earlierRemoteSplitApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = "remote-split-first",
                revision = 1L,
                operation = "split",
                targetGroupId = remoteSplitGroup,
                members = listOf(first.key),
                retainedGroupId = localMergedGroup,
                retainedMembers = listOf(second.key),
                payload = "remote-split-payload",
                appliedAt = CREATED_AT,
            ),
        )
        val acceptedLocalMergeApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = decisionId,
                revision = 2L,
                operation = "merge",
                targetGroupId = localMergedGroup,
                members = listOf(first.key, second.key),
                payload = "accepted-local-payload",
                appliedAt = CREATED_AT,
            ),
        )
        val repeatedLocalMergeApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = decisionId,
                revision = 2L,
                operation = "merge",
                targetGroupId = localMergedGroup,
                members = listOf(first.key, second.key),
                payload = "accepted-local-payload",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        assertEquals(true, earlierRemoteSplitApplied)
        assertEquals(true, acceptedLocalMergeApplied)
        assertEquals(false, repeatedLocalMergeApplied)
        assertEquals(localMergedGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(localMergedGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertEquals(2L, classUnderTest.getMembership(first.key)?.revision)
        assertEquals(emptyList(), classUnderTest.getManualSeparations(profileId))
        assertEquals(
            1L,
            database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                profileId.value,
                decisionId,
            ).executeAsOne().feed_applied,
        )
    }

    @Test
    fun acceptedLocalSplitRebasesOverEarlierRemoteMoveOfItsMember() = runBlocking {
        // Given
        val moved = portableSource("profile-a", "account-a", "book-moved")
        val retained = portableSource("profile-a", "account-a", "book-retained")
        val remotePeer = portableSource("profile-a", "account-a", "book-remote-peer")
        val sourceGroup = LibraryGroupId("group-source")
        val remotePeerGroup = LibraryGroupId("group-remote-peer")
        val localSplitGroup = LibraryGroupId("group-local-split")
        val remoteMergeGroup = LibraryGroupId("group-remote-merge")
        val profileId = LibraryProfileId("profile-a")
        val localDecisionId = "local-split-accepted-second"
        createGroupWithSources(sourceGroup, listOf(moved, retained))
        classUnderTest.ensureGroupForSource(remotePeer, remotePeerGroup, CREATED_AT)
        classUnderTest.applyManualSplit(
            LibraryManualGroupSplit(
                profileId = profileId,
                sourceGroupId = sourceGroup,
                newGroupId = localSplitGroup,
                movedSourceKeys = listOf(moved.key),
                decisionId = localDecisionId,
                appliedAt = CREATED_AT,
                retainedSourceKeys = listOf(retained.key),
                outboxEntry = outboxEntry(
                    mutationId = localDecisionId,
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                    entityId = localDecisionId,
                ),
            ),
        )
        classUnderTest.recordAcceptedDecision(
            profileId = profileId,
            decisionId = localDecisionId,
            revision = 2L,
            payload = "accepted-local-split-payload",
        )

        // When
        val earlierRemoteMoveApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = "remote-merge-first",
                revision = 1L,
                operation = "merge",
                targetGroupId = remoteMergeGroup,
                members = listOf(moved.key, remotePeer.key),
                payload = "remote-merge-payload",
                appliedAt = CREATED_AT,
            ),
        )
        val acceptedLocalSplitApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = localDecisionId,
                revision = 2L,
                operation = "split",
                targetGroupId = localSplitGroup,
                retainedGroupId = sourceGroup,
                members = listOf(moved.key),
                retainedMembers = listOf(retained.key),
                payload = "accepted-local-split-payload",
                appliedAt = CREATED_AT,
            ),
        )
        val repeatedLocalSplitApplied = classUnderTest.applySynchronizedDecision(
            LibrarySynchronizedGroupDecision(
                profileId = profileId,
                decisionId = localDecisionId,
                revision = 2L,
                operation = "split",
                targetGroupId = localSplitGroup,
                retainedGroupId = sourceGroup,
                members = listOf(moved.key),
                retainedMembers = listOf(retained.key),
                payload = "accepted-local-split-payload",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        assertEquals(true, earlierRemoteMoveApplied)
        assertEquals(true, acceptedLocalSplitApplied)
        assertEquals(false, repeatedLocalSplitApplied)
        assertEquals(localSplitGroup, classUnderTest.getMembership(moved.key)?.groupId)
        assertEquals(sourceGroup, classUnderTest.getMembership(retained.key)?.groupId)
        assertEquals(remoteMergeGroup, classUnderTest.getMembership(remotePeer.key)?.groupId)
        assertEquals(
            setOf(setOf(moved.key, retained.key)),
            classUnderTest.getManualSeparations(profileId).map { separation ->
                setOf(separation.first, separation.second)
            }.toSet(),
        )
    }

    @Test
    fun manualSplitSeparatesEachMovedMemberFromEachRemainingMember() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val remaining = portableSource("profile-a", "account-a", "book-remaining")
        val sourceGroup = LibraryGroupId("group-source")
        val splitGroup = LibraryGroupId("group-split")
        val profileId = LibraryProfileId("profile-a")
        createGroupWithSources(sourceGroup, listOf(first, second, remaining))

        // When
        classUnderTest.applyManualSplit(
            LibraryManualGroupSplit(
                profileId = profileId,
                sourceGroupId = sourceGroup,
                newGroupId = splitGroup,
                movedSourceKeys = listOf(first.key, second.key),
                decisionId = "manual-split",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        assertEquals(splitGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(splitGroup, classUnderTest.getMembership(second.key)?.groupId)
        assertEquals(
            LibraryMembershipOrigin.Manual,
            classUnderTest.getMembership(first.key)?.origin,
        )
        assertEquals("manual-split", classUnderTest.getMembership(second.key)?.decisionId)
        assertEquals(sourceGroup, classUnderTest.getMembership(remaining.key)?.groupId)
        assertEquals(sourceGroup, classUnderTest.resolveGroupId(profileId, sourceGroup))
        val separations = classUnderTest.getManualSeparations(profileId)
        assertEquals(2, separations.size)
        assertEquals(
            setOf(
                setOf(first.key, remaining.key),
                setOf(second.key, remaining.key),
            ),
            separations.map { separation -> setOf(separation.first, separation.second) }.toSet(),
        )
        assertTrue(separations.all { separation ->
            separation.decisionId.startsWith("manual-split:separation:")
        })
    }

    @Test
    fun explicitMergeOverrideCanBeReactivatedByLaterSplit() = runBlocking {
        // Given
        val first = portableSource("profile-a", "account-a", "book-first")
        val second = portableSource("profile-a", "account-a", "book-second")
        val sourceGroup = LibraryGroupId("group-source")
        val mergedGroup = LibraryGroupId("group-merged")
        val splitGroup = LibraryGroupId("group-split")
        val profileId = LibraryProfileId("profile-a")
        createGroupWithSources(sourceGroup, listOf(first, second))
        classUnderTest.addManualSeparation(first.key, second.key, "initial-split", CREATED_AT)

        // When
        classUnderTest.applyManualMerge(
            LibraryManualGroupMerge(
                profileId = profileId,
                newGroupId = mergedGroup,
                members = listOf(
                    LibraryManualGroupMemberSelection(first.key, sourceGroup),
                    LibraryManualGroupMemberSelection(second.key, sourceGroup),
                ),
                decisionId = "explicit-merge",
                appliedAt = CREATED_AT,
            ),
        )
        assertEquals(emptyList(), classUnderTest.getManualSeparations(profileId))
        classUnderTest.applyManualSplit(
            LibraryManualGroupSplit(
                profileId = profileId,
                sourceGroupId = mergedGroup,
                newGroupId = splitGroup,
                movedSourceKeys = listOf(first.key),
                decisionId = "later-split",
                appliedAt = CREATED_AT,
            ),
        )

        // Then
        val separations = classUnderTest.getManualSeparations(profileId)
        assertEquals(1, separations.size)
        assertEquals("later-split:separation:0", separations.single().decisionId)
        assertEquals(splitGroup, classUnderTest.getMembership(first.key)?.groupId)
        assertEquals(mergedGroup, classUnderTest.getMembership(second.key)?.groupId)
        val stored = database.libraryGroupDecisionQueries.getLibraryManualSeparation(
            profileId.value,
            encodedPair(first.key, second.key).first,
            encodedPair(first.key, second.key).second,
        ).executeAsOne()
        assertNull(stored.overridden_by_decision_id)
    }

    private fun encodedPair(
        first: SourceBookKey,
        second: SourceBookKey,
    ): Pair<String, String> {
        val firstEncoded = LibrarySourceKeyCodec.encode(first)
        val secondEncoded = LibrarySourceKeyCodec.encode(second)
        return if (firstEncoded < secondEncoded) {
            firstEncoded to secondEncoded
        } else {
            secondEncoded to firstEncoded
        }
    }

    private fun outboxEntry(
        mutationId: String,
        entityType: String,
        entityId: String,
    ) = SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = "cloud-user",
        entityType = entityType,
        entityId = entityId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "decision-payload",
        baseRevision = null,
        createdAt = CREATED_AT,
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private suspend fun createGroupWithSources(
        groupId: LibraryGroupId,
        sources: List<SourceBookRef>,
    ) {
        require(sources.isNotEmpty())
        val initialGroupIds = sources.mapIndexed { index, source ->
            val initialGroupId = if (index == 0) {
                groupId
            } else {
                LibraryGroupId("${groupId.value}-seed-$index")
            }
            classUnderTest.ensureGroupForSource(source, initialGroupId, CREATED_AT)
            initialGroupId
        }
        if (sources.size > 1) {
            classUnderTest.applyAutomaticMerge(
                LibraryAutomaticGroupMerge(
                    profileId = sources.first().key.profileId,
                    survivorGroupId = groupId,
                    retiredGroupIds = initialGroupIds.toSet() - groupId,
                    sourceKeys = sources.map { source -> source.key }.toSet(),
                    evidenceIds = setOf("seed:${groupId.value}"),
                    appliedAt = CREATED_AT,
                ),
            )
        }
    }

    @Test
    fun projectionObserverEmitsWhenAliasChangesWithoutMembershipChanges() = runBlocking {
        // Given
        val profileId = LibraryProfileId("profile-a")
        val aliasGroup = LibraryGroupId("group-alias")
        val targetGroup = LibraryGroupId("group-target")
        classUnderTest.ensureGroupForSource(
            portableSource("profile-a", "account-a", "book-alias"),
            aliasGroup,
            CREATED_AT,
        )
        classUnderTest.ensureGroupForSource(
            portableSource("profile-a", "account-a", "book-target"),
            targetGroup,
            CREATED_AT,
        )
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            classUnderTest.observeProjectionChanges(profileId).collect { emissions.send(Unit) }
        }

        try {
            withTimeout(5_000) { emissions.receive() }

            // When
            classUnderTest.addGroupAlias(profileId, aliasGroup, targetGroup, CREATED_AT)

            // Then
            withTimeout(5_000) { emissions.receive() }
            assertEquals(targetGroup, classUnderTest.resolveGroupId(profileId, aliasGroup))
        } finally {
            observer.cancelAndJoin()
        }
    }

    private fun portableSource(
        profileId: String,
        accountId: String,
        nativeBookId: String,
    ): SourceBookRef = SourceBookRef(
        key = SourceBookKey(
            profileId = LibraryProfileId(profileId),
            adapterId = LibraryAdapterId("future-source"),
            accountIdentity = SourceAccountIdentity.Portable("backend-a", accountId),
            nativeBookId = NativeBookId(nativeBookId),
        ),
        connectionId = SourceConnectionId("connection-a"),
    )

    private class ActiveProfileSession(
        var activeProfileId: String,
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
        const val CREATED_AT = "2026-09-24T00:00:00Z"
    }
}
