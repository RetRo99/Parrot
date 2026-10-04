package com.retro99.server.parrotcloud

import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParrotCloudBookLinkSyncTest {

    private lateinit var database: ParrotTestBookLinksDatabase
    private lateinit var classUnderTest: ParrotCloudBookLinkSync

    private fun setup(vararg links: BookLinkEntity) {
        database = ParrotTestBookLinksDatabase(*links)
        classUnderTest = ParrotCloudBookLinkSync(database)
    }

    @Test
    fun `a pulled link is stored`() = runTest {
        // Given
        setup()

        // When
        classUnderTest.applyRemoteLink(
            remoteLink(REMOTE, "library:b1", "storyteller:s1", revision = 3),
        )

        // Then
        val stored = database.liveLinks.single()
        assertEquals(REMOTE, stored.linkId)
        assertEquals(listOf("library:b1", "storyteller:s1"), stored.members)
        assertEquals(3L, stored.remoteRevision)
        assertEquals("2026-09-01T10:00:00+00:00", stored.createdAt)
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `a pulled deletion removes the link`() = runTest {
        // Given
        setup(link(REMOTE, "library:b1", "storyteller:s1", revision = 3))

        // When
        classUnderTest.applyRemoteLink(remoteLink(REMOTE, revision = 4, deleted = true))

        // Then
        assertTrue(database.liveLinks.isEmpty())
        assertNotNull(database.links.value.getValue(REMOTE).deletedAt)
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `a pulled link this device already has at that revision is ignored`() = runTest {
        // Given: the echo of this device's own push arrives after a newer local change
        setup(link(REMOTE, "audiobookshelf:a1", "library:b1", "storyteller:s1", revision = 3))

        // When
        classUnderTest.applyRemoteLink(
            remoteLink(REMOTE, "library:b1", "storyteller:s1", revision = 3),
        )

        // Then
        assertEquals(3, database.liveLinks.single().members.size)
    }

    @Test
    fun `a pulled link that shares a member with a local link merges into the older link`() =
        runTest {
            // Given
            setup(link(LOCAL, "library:b1", "storyteller:s1", createdAt = "2026-10-01T10:00:00Z"))

            // When
            classUnderTest.applyRemoteLink(
                remoteLink(REMOTE, "audiobookshelf:a1", "storyteller:s1", revision = 5),
            )

            // Then
            val survivor = database.liveLinks.single()
            assertEquals(REMOTE, survivor.linkId)
            assertEquals(
                listOf("audiobookshelf:a1", "library:b1", "storyteller:s1"),
                survivor.members,
            )
            assertEquals(
                listOf(
                    Triple(LOCAL, SyncOutboxEntry.OPERATION_DELETE, null),
                    Triple(REMOTE, SyncOutboxEntry.OPERATION_UPSERT, 5L),
                ),
                database.outbox.map { entry ->
                    Triple(entry.entityId, entry.operation, entry.baseRevision)
                },
            )
        }

    @Test
    fun `a conflict merge keeps the older id`() = runTest {
        // Given: the local link is older than the one the server reports
        setup(
            link(
                LOCAL,
                "library:b1",
                "storyteller:s1",
                revision = 2,
                createdAt = "2026-08-01T10:00:00Z",
            ),
        )
        val applier = applier()

        // When
        applier.onConflict(
            entry = linkEntry(LOCAL),
            response = conflict(
                remoteLink(REMOTE, "audiobookshelf:a1", "storyteller:s1", revision = 5),
            ),
        )

        // Then
        val survivor = database.liveLinks.single()
        assertEquals(LOCAL, survivor.linkId)
        assertEquals(
            listOf("audiobookshelf:a1", "library:b1", "storyteller:s1"),
            survivor.members,
        )
        assertNotNull(database.links.value.getValue(REMOTE).deletedAt)
        assertEquals(
            listOf(
                Triple(REMOTE, SyncOutboxEntry.OPERATION_DELETE, 5L),
                Triple(LOCAL, SyncOutboxEntry.OPERATION_UPSERT, 2L),
            ),
            database.outbox.map { entry ->
                Triple(entry.entityId, entry.operation, entry.baseRevision)
            },
        )
        assertTrue(applier.discardsConflict(linkEntry(LOCAL)))
    }

    @Test
    fun `a conflict that cannot merge keeps the server link and drops the local one`() =
        runTest {
            // Given: both links have a library copy, so they can't become one link
            setup(link(LOCAL, "library:b1", "storyteller:s1"))

            // When
            applier().onConflict(
                entry = linkEntry(LOCAL),
                response = conflict(
                    remoteLink(REMOTE, "library:b2", "storyteller:s1", revision = 5),
                ),
            )

            // Then
            val survivor = database.liveLinks.single()
            assertEquals(REMOTE, survivor.linkId)
            assertEquals(listOf("library:b2", "storyteller:s1"), survivor.members)
            assertEquals(
                listOf(LOCAL to SyncOutboxEntry.OPERATION_DELETE),
                database.outbox.map { entry -> entry.entityId to entry.operation },
            )
        }

    @Test
    fun `a stale conflict on the same link joins both sides and pushes again`() = runTest {
        // Given
        setup(link(LOCAL, "library:b1", "storyteller:s1", revision = 1))

        // When
        applier().onConflict(
            entry = linkEntry(LOCAL),
            response = conflict(
                remoteLink(LOCAL, "audiobookshelf:a1", "library:b1", revision = 2),
            ),
        )

        // Then
        val stored = database.liveLinks.single()
        assertEquals(
            listOf("audiobookshelf:a1", "library:b1", "storyteller:s1"),
            stored.members,
        )
        assertEquals(2L, stored.remoteRevision)
        val entry = database.outbox.single()
        assertEquals(LOCAL, entry.entityId)
        assertEquals(2L, entry.baseRevision)
    }

    @Test
    fun `an accepted link records the server revision`() = runTest {
        // Given
        setup(link(LOCAL, "library:b1", "storyteller:s1"))

        // When
        applier().onAccepted(
            entry = linkEntry(LOCAL),
            response = SyncMutationResponse(
                mutationId = "mutation-1",
                status = "accepted",
                revision = 7L,
                payload = null,
                reason = null,
            ),
        )

        // Then
        assertEquals(7L, database.liveLinks.single().remoteRevision)
    }

    @Test
    fun `a stale unlink does not restore the removed copy`() = runTest {
        // Given: an earlier queued addition was accepted before this removal.
        setup(link(LOCAL, "library:b1", "storyteller:s1", revision = 2))
        val entry = linkEntry(LOCAL).copy(
            payload = """{"link_id":"$LOCAL","members":["library:b1","storyteller:s1"],"removed_members":["audiobookshelf:a1"]}""",
        )

        // When
        applier().onConflict(
            entry,
            conflict(remoteLink(
                LOCAL, "library:b1", "storyteller:s1", "audiobookshelf:a1", revision = 2,
            )),
        )

        // Then
        assertEquals(listOf("library:b1", "storyteller:s1"), database.liveLinks.single().members)
        val retry = database.outbox.single()
        assertEquals(2L, retry.baseRevision)
        assertTrue(retry.payload.contains("\"removed_members\":[\"audiobookshelf:a1\"]"))

        // Another concurrent edit must not lose the removal intent on the retry.
        applier().onConflict(
            retry,
            conflict(remoteLink(
                LOCAL, "library:b1", "storyteller:s1", "audiobookshelf:a1", revision = 3,
            )),
        )
        assertEquals(listOf("library:b1", "storyteller:s1"), database.liveLinks.single().members)
        assertEquals(3L, database.outbox.last().baseRevision)
    }

    @Test
    fun `a pulled decision is stored unless this device has a newer one`() = runTest {
        // Given
        setup()
        database.write(
            BookLinkWrite(
                decisions = listOf(
                    BookLinkDecisionEntity(
                        pairKey = "library:b2|storyteller:s2",
                        decision = "skip",
                        decidedAt = "2026-10-01T12:00:00Z",
                    ),
                ),
            ),
        )

        // When
        classUnderTest.applyRemoteDecision(
            remoteDecision("library:b1|storyteller:s1", "never", "2026-10-01T10:00:00+00:00"),
        )
        classUnderTest.applyRemoteDecision(
            remoteDecision("library:b2|storyteller:s2", "never", "2026-10-01T11:00:00+00:00"),
        )

        // Then
        assertEquals("never", database.decisions.value["library:b1|storyteller:s1"]?.decision)
        assertEquals(4L, database.decisions.value["library:b1|storyteller:s1"]?.remoteRevision)
        assertEquals("skip", database.decisions.value["library:b2|storyteller:s2"]?.decision)
    }

    @Test
    fun `link mutations never touch library books`() = runTest {
        // Given
        setup()
        val libraryBooks = ParrotTestLibraryBooksDatabase()
        val applier = ParrotCloudLibraryMutationApplier(
            LibraryBookSyncApplier(libraryBooks),
            classUnderTest,
            testSavedItemSync(),
        )
        val decisionEntry = linkEntry("library:b1|storyteller:s1").copy(
            entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
        )

        // When
        applier.onAccepted(decisionEntry, accepted())
        applier.onAccepted(linkEntry(LOCAL), accepted())
        applier.onConflict(decisionEntry, accepted())

        // Then
        assertEquals(emptyList(), libraryBooks.upserted)
        assertNull(database.links.value[LOCAL])
    }

    private fun applier() = ParrotCloudLibraryMutationApplier(
        LibraryBookSyncApplier(ParrotTestLibraryBooksDatabase()),
        classUnderTest,
        testSavedItemSync(),
    )

    private fun accepted() = SyncMutationResponse(
        mutationId = "mutation-1",
        status = "accepted",
        revision = 1L,
        payload = null,
        reason = null,
    )

    private fun conflict(remote: String) = SyncMutationResponse(
        mutationId = "mutation-1",
        status = "conflict",
        revision = 5L,
        payload = remote,
        reason = null,
    )

    private fun remoteLink(
        linkId: String,
        vararg members: String,
        revision: Long,
        deleted: Boolean = false,
    ): String {
        val memberList = members.joinToString(",") { member -> "\"$member\"" }
        return """{"link_id":"$linkId","members":[$memberList],"deleted":$deleted,""" +
            """"remote_revision":$revision,"created_at":"2026-09-01T10:00:00+00:00"}"""
    }

    private fun remoteDecision(pairKey: String, decision: String, decidedAt: String) =
        """{"pair_key":"$pairKey","decision":"$decision","decided_at":"$decidedAt",""" +
            """"remote_revision":4}"""

    private suspend fun ParrotCloudBookLinkSync.applyRemoteLink(payload: String) =
        applyRemoteLink(Json.parseToJsonElement(payload))

    private suspend fun ParrotCloudBookLinkSync.applyRemoteDecision(payload: String) =
        applyRemoteDecision(Json.parseToJsonElement(payload))

    private fun link(
        linkId: String,
        vararg members: String,
        revision: Long? = null,
        createdAt: String = "2026-10-01T10:00:00Z",
    ) = BookLinkEntity(
        linkId = linkId,
        members = members.sorted(),
        createdAt = createdAt,
        updatedAt = createdAt,
        remoteRevision = revision,
    )

    private fun linkEntry(entityId: String) = SyncOutboxEntry(
        mutationId = "mutation-1",
        cloudUserId = "account",
        entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
        entityId = entityId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = """{"link_id":"$entityId"}""",
        baseRevision = null,
        createdAt = "2026-10-01T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )

    private companion object {
        const val LOCAL = "11111111-1111-4111-8111-111111111111"
        const val REMOTE = "22222222-2222-4222-8222-222222222222"
    }
}
