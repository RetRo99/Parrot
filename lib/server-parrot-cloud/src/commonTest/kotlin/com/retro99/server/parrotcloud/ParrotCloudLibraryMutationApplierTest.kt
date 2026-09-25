package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryGroupMembershipRecord
import com.retro99.database.api.library.LibraryGroupRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryManualSeparationRecord
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.library.LocalBookFileEntity
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.data.LocalBookUuidResolver
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParrotCloudLibraryMutationApplierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun acceptedMutationDecodesPayloadAndAppliesRemoteIdentity() = runTest {
        val database = RecordingLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier(database),
            libraryGroupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase(),
        )

        applier.onAccepted(
            entry = entry(json.encodeToString(bookPayload())),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                cloudBookId = "cloud-book-accepted",
                revision = 12L,
                payload = null,
                reason = null,
            ),
        )

        assertEquals("cloud-book-accepted", database.upserted.single().cloudBookId)
        assertEquals("book title", database.upserted.single().title)
        assertEquals(12L, database.upserted.single().remoteRevision)
    }

    @Test
    fun conflictPayloadIsAppliedAsRemoteLibraryState() = runTest {
        val database = RecordingLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier(database),
            libraryGroupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase(),
        )

        applier.onConflict(
            entry = entry("{}"),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "conflict",
                cloudBookId = null,
                revision = 13L,
                payload = json.encodeToString(bookPayload().copy(title = "remote title")),
                reason = "stale revision",
            ),
        )

        assertEquals("remote title", database.upserted.single().title)
        assertEquals(13L, database.upserted.single().remoteRevision)
    }

    @Test
    fun deletedBookConflictPayloadPreservesTheRemoteTombstone() = runTest {
        val database = RecordingLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier(database),
            libraryGroupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase(),
        )
        val deletedAt = "2026-09-25T08:30:00Z"

        applier.onConflict(
            entry = entry("{}"),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "conflict",
                cloudBookId = "cloud-book-1",
                revision = 14L,
                payload = json.encodeToString(bookPayload().copy(deletedAt = deletedAt)),
                reason = "book_deleted",
            ),
        )

        assertEquals(14L, database.upserted.single().remoteRevision)
        assertEquals(deletedAt, database.upserted.single().deletedAt)
    }

    @Test
    fun acceptedBookRemovalAppliesTheServerTombstoneAndRevision() = runTest {
        // Given
        val database = RecordingLibraryBooksDatabase()
        val localBookUuid = "local-book-1"
        val positions = RecordingPositionDatabase().apply {
            localPositions += TestPosition(localBookUuid, progression = 0.42)
            remotePositions += TestPosition(localBookUuid, progression = 0.41)
        }
        val removalApplier = removalApplier(
            database = database,
            positionDatabase = positions,
            localBookUuidResolver = LocalBookUuidResolver { libraryBookId, cloudBookId, fallback ->
                assertEquals("library-book-1", libraryBookId)
                assertEquals("cloud-book-1", cloudBookId)
                assertEquals("library-book-1", fallback)
                localBookUuid
            },
        )
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier,
            libraryGroupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase(),
        )
        val deletedAt = "2026-09-25T08:30:00Z"
        val tombstone = bookPayload().copy(deletedAt = deletedAt)

        // When
        applier.onAccepted(
            entry = entry(json.encodeToString(tombstone)).copy(
                operation = SyncOutboxEntry.OPERATION_DELETE,
            ),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                cloudBookId = tombstone.cloudBookId,
                revision = 15L,
                payload = json.encodeToString(tombstone),
                reason = null,
            ),
        )

        // Then
        val applied = database.upserted.single()
        assertEquals("cloud-book-1", applied.cloudBookId)
        assertEquals(deletedAt, applied.deletedAt)
        assertEquals(15L, applied.remoteRevision)
        assertTrue(positions.remotePositions.isEmpty())
        assertTrue(positions.deletedRemoteBookIds.contains(localBookUuid))
        assertTrue(positions.deletedRemoteBookIds.contains("library-book-1"))
        assertTrue(positions.deletedRemoteBookIds.contains("cloud-book-1"))
        assertEquals(0.42, positions.localPositions.single().progression)
        assertTrue(positions.deletedLocalBookIds.isEmpty())
    }

    @Test
    fun staleAcceptedBookRemovalDoesNotClearTheRemoteProgressBaseline() = runTest {
        // Given
        val database = RecordingLibraryBooksDatabase(
            existing = ExistingCloudBookEntity(remoteRevision = 16L),
        )
        val positions = RecordingPositionDatabase().apply {
            remotePositions += TestPosition("local-book-1", progression = 0.41)
        }
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier(
                database = database,
                positionDatabase = positions,
            ),
            libraryGroupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase(),
        )
        val tombstone = bookPayload().copy(deletedAt = "2026-09-25T08:30:00Z")

        // When
        applier.onAccepted(
            entry = entry(json.encodeToString(tombstone)).copy(
                operation = SyncOutboxEntry.OPERATION_DELETE,
            ),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                cloudBookId = tombstone.cloudBookId,
                revision = 15L,
                payload = json.encodeToString(tombstone),
                reason = null,
            ),
        )

        // Then
        assertTrue(database.upserted.isEmpty())
        assertTrue(positions.deletedRemoteBookIds.isEmpty())
        assertEquals("local-book-1", positions.remotePositions.single().bookUuid)
    }

    @Test
    fun acceptedGroupDecisionPersistsTheServerRevision() = runTest {
        // Given
        val decisionId = "decision-1"
        val payload = LibraryGroupDecisionPayload(
            decisionId = decisionId,
            decisionType = LibraryGroupDecisionPayload.MERGE,
            targetGroupId = "group-1",
            members = listOf(
                LibraryGroupMemberRef(
                    profileId = "local-profile",
                    adapterId = "cloud",
                    identityKind = LibraryGroupMemberRef.PORTABLE,
                    backendId = "parrot-cloud",
                    accountId = "cloud-account",
                    nativeBookId = "book-1",
                ),
                LibraryGroupMemberRef(
                    profileId = "local-profile",
                    adapterId = "storyteller",
                    identityKind = LibraryGroupMemberRef.PORTABLE,
                    backendId = "story-backend",
                    accountId = "story-account",
                    nativeBookId = "book-2",
                ),
            ),
            createdAt = CREATED_AT,
            localProfileId = "local-profile",
        )
        val encodedPayload = LibraryGroupDecisionCodec.encodeLocal(payload)
        val database = RecordingLibraryBooksDatabase()
        val groupsDatabase = AcceptedDecisionRecordingLibraryGroupsDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            libraryBookSyncApplier = LibraryBookSyncApplier(database),
            libraryBookRemovalApplier = removalApplier(database),
            libraryGroupsDatabase = groupsDatabase,
        )

        // When
        applier.onAccepted(
            entry = entry(encodedPayload).copy(
                mutationId = decisionId,
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION,
                entityId = decisionId,
            ),
            response = SyncMutationResponse(
                mutationId = decisionId,
                status = "accepted",
                cloudBookId = null,
                revision = 18L,
                payload = "canonical-payload",
                reason = null,
            ),
        )

        // Then
        assertEquals(
            AcceptedDecision("local-profile", decisionId, 18L, "canonical-payload"),
            groupsDatabase.acceptedDecisions.single(),
        )
    }

    private fun entry(payload: String) = SyncOutboxEntry(
        mutationId = "mutation-1",
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
        entityId = "library-book-1",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = payload,
        baseRevision = 4L,
        createdAt = "2026-09-22T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private fun bookPayload() = ParrotCloudBookPayload(
        libraryBookId = "library-book-1",
        cloudBookId = "cloud-book-1",
        contentHash = "hash",
        contentHashAlgorithm = "sha256",
        title = "book title",
        author = "author",
        format = "ebook",
        metadataJson = "metadata",
        remoteRevision = 9L,
    )
}

private data class AcceptedDecision(
    val profileId: String,
    val decisionId: String,
    val revision: Long,
    val payload: String,
)

private class AcceptedDecisionRecordingLibraryGroupsDatabase : LibraryGroupsDatabase {
    val acceptedDecisions = mutableListOf<AcceptedDecision>()

    override fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit> = emptyFlow()

    override suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    ) {
        acceptedDecisions += AcceptedDecision(profileId.value, decisionId, revision, payload)
    }

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
    ): List<LibraryGroupMembershipRecord> = unused()

    override suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean = unused()

    override suspend fun applyManualMerge(merge: LibraryManualGroupMerge): Unit = unused()

    override suspend fun applyManualSplit(split: LibraryManualGroupSplit): Unit = unused()

    override suspend fun applySynchronizedDecision(
        decision: LibrarySynchronizedGroupDecision,
    ): Boolean = unused()

    override suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    ): Unit = unused()

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

private const val CREATED_AT = "2026-09-24T12:00:00Z"

private class RecordingLibraryBooksDatabase(
    private val existing: LibraryBookEntity? = null,
) : LibraryBooksDatabase {
    val upserted = mutableListOf<LibraryBookEntity>()

    override suspend fun upsertLibraryBook(book: LibraryBookEntity) {
        upserted += book
    }

    override suspend fun upsertLocalLibraryBook(book: LibraryBookEntity) = Unit

    override fun getAllLibraryBooks(): Flow<List<LibraryBookEntity>> = emptyFlow()

    override suspend fun getLibraryBookById(libraryBookId: String): LibraryBookEntity? = null

    override suspend fun getLibraryBookByContentHash(contentHash: String): LibraryBookEntity? = null

    override suspend fun getLibraryBookByContentHash(
        contentHashAlgorithm: String,
        contentHash: String,
    ): LibraryBookEntity? = null

    override suspend fun getLibraryBookByCloudBookId(cloudBookId: String): LibraryBookEntity? =
        existing?.takeIf { book -> book.cloudBookId == cloudBookId }

    override suspend fun getLibraryBookByCloudBookIdIncludingDeleted(
        cloudBookId: String,
    ): LibraryBookEntity? = existing?.takeIf { book -> book.cloudBookId == cloudBookId }

    override suspend fun attachCloudBookId(libraryBookId: String, cloudBookId: String) = Unit

    override suspend fun upsertLocalBookFile(file: LocalBookFileEntity) = Unit

    override suspend fun getLocalBookFiles(
        libraryBookId: String,
    ): List<LocalBookFileEntity> = emptyList()

    override suspend fun getLocalBookFileByImportedBookUuid(
        importedBookUuid: String,
    ): LocalBookFileEntity? = null

    override suspend fun deleteLocalBookFileByImportedBookUuid(importedBookUuid: String) = Unit
}

private data class ExistingCloudBookEntity(
    override val remoteRevision: Long,
) : LibraryBookEntity {
    override val libraryBookId = "library-book-1"
    override val cloudBookId = "cloud-book-1"
    override val contentHash = "hash"
    override val contentHashAlgorithm = "sha-256-v1"
    override val title = "Book"
    override val author: String? = null
    override val format = "ebook"
    override val deletedAt: String? = null
    override val metadataJson: String? = null
}

private fun removalApplier(
    database: LibraryBooksDatabase,
    positionDatabase: RecordingPositionDatabase = RecordingPositionDatabase(),
    localBookUuidResolver: LocalBookUuidResolver = LocalBookUuidResolver {
        libraryBookId, cloudBookId, fallback ->
        fallback
    },
) = ParrotCloudLibraryBookRemovalApplier(
    libraryBookSyncApplier = LibraryBookSyncApplier(database),
    localBookUuidResolver = localBookUuidResolver,
    positionDatabase = positionDatabase,
)

private class RecordingPositionDatabase : PositionDatabase {
    val localPositions = mutableListOf<PositionEntity>()
    val remotePositions = mutableListOf<PositionEntity>()
    val deletedRemoteBookIds = mutableListOf<String>()
    val deletedLocalBookIds = mutableListOf<String>()

    override suspend fun upsertPosition(position: PositionEntity) {
        localPositions.removeAll { existing -> existing.bookUuid == position.bookUuid }
        localPositions += position
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) = Unit

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) = Unit

    override suspend fun upsertRemotePosition(position: PositionEntity) {
        remotePositions.removeAll { existing -> existing.bookUuid == position.bookUuid }
        remotePositions += position
    }

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? =
        remotePositions.firstOrNull { position -> position.bookUuid == bookUuid }

    override suspend fun deleteRemotePosition(bookUuid: String) {
        deletedRemoteBookIds += bookUuid
        remotePositions.removeAll { position -> position.bookUuid == bookUuid }
    }

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? =
        localPositions.firstOrNull { position -> position.bookUuid == bookUuid }

    override suspend fun getAllPositions(): List<PositionEntity> = localPositions.toList()

    override suspend fun deletePosition(bookUuid: String) {
        deletedLocalBookIds += bookUuid
        localPositions.removeAll { position -> position.bookUuid == bookUuid }
    }

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> = emptyFlow()

    override fun observeAllPositions(): Flow<List<PositionEntity>> = emptyFlow()

    override suspend fun clearAllData() {
        localPositions.clear()
        remotePositions.clear()
    }
}

private data class TestPosition(
    override val bookUuid: String,
    override val progression: Double?,
) : PositionEntity {
    override val timestamp: Long? = 1L
    override val createdAt: String? = CREATED_AT
    override val updatedAt: String? = CREATED_AT
    override val locatorHref: String? = "chapter-1.xhtml"
    override val locatorType: String? = "application/xhtml+xml"
    override val locatorTitle: String? = "Chapter 1"
    override val locatorTarget: Int? = null
    override val audioTimestampMs: Long? = null
    override val chapterIndex: Int? = 0
    override val totalChapters: Int? = 10
    override val totalDurationMs: Long? = null
    override val totalProgression: Double? = progression
    override val position: Int? = 1
}
