package com.retro99.books.data.links

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.repeatedLinkSource
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookLinksDataRepositoryTest {

    private val library = CopyKey(CopySource.Library, "b1")
    private val storyteller = CopyKey(CopySource.Storyteller, "s1")
    private val audiobookshelf = CopyKey(CopySource.Audiobookshelf, "a1")

    private lateinit var database: FakeBookLinksDatabase
    private lateinit var classUnderTest: BookLinksRepository

    private fun setup(vararg links: BookLinkEntity) {
        database = FakeBookLinksDatabase(*links)
        classUnderTest = BookLinksDataRepository(
            bookLinksDatabase = database,
            databaseExecutor = DirectDatabaseExecutor,
        )
    }

    @Test
    fun `linking two unlinked copies creates one link and one outbox entry`() = runTest {
        // Given
        setup()

        // When
        val result = classUnderTest.link(library, storyteller)

        // Then
        val link = assertNotNull(result.get())
        assertEquals(setOf(library, storyteller), link.members)
        assertEquals(listOf(link.linkId), database.liveLinks.map { stored -> stored.linkId })
        val entry = database.outbox.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK, entry.entityType)
        assertEquals(SyncOutboxEntry.OPERATION_UPSERT, entry.operation)
        assertEquals(link.linkId, entry.entityId)
        assertNull(entry.baseRevision)
        assertEquals(
            """{"link_id":"${link.linkId}","members":["library:b1","storyteller:s1"],""" +
                """"deleted":false,"removed_members":[]}""",
            entry.payload,
        )
    }

    @Test
    fun `linking a copy into an existing link adds the member`() = runTest {
        // Given
        setup(link("link-1", "library:b1", "storyteller:s1").copy(remoteRevision = 4))

        // When
        val result = classUnderTest.link(audiobookshelf, storyteller)

        // Then
        assertEquals(setOf(library, storyteller, audiobookshelf), result.get()?.members)
        assertEquals(
            listOf("audiobookshelf:a1", "library:b1", "storyteller:s1"),
            database.liveLinks.single().members,
        )
        val entry = database.outbox.single()
        assertEquals("link-1", entry.entityId)
        assertEquals(4L, entry.baseRevision)
    }

    // With three sources, two valid links can never merge without repeating a source, so
    // this uses a one-member link (as left by a member this app version can't parse).
    @Test
    fun `linking two copies that are each in a link merges into the older link`() = runTest {
        // Given
        setup(
            link("link-new", "library:b1", "storyteller:s1", createdAt = "2026-10-01T12:00:00Z"),
            link("link-old", "audiobookshelf:a1", createdAt = "2026-09-01T12:00:00Z"),
        )

        // When
        val result = classUnderTest.link(storyteller, audiobookshelf)

        // Then
        assertEquals("link-old", result.get()?.linkId)
        assertEquals(
            listOf("link-old" to listOf("audiobookshelf:a1", "library:b1", "storyteller:s1")),
            database.liveLinks.map { stored -> stored.linkId to stored.members },
        )
        assertNotNull(database.links.value.getValue("link-new").deletedAt)
        assertEquals(
            listOf(
                "link-new" to SyncOutboxEntry.OPERATION_DELETE,
                "link-old" to SyncOutboxEntry.OPERATION_UPSERT,
            ),
            database.outbox.map { entry -> entry.entityId to entry.operation },
        )
    }

    @Test
    fun `linking two copies from the same source fails`() = runTest {
        // Given
        setup()

        // When
        val result = classUnderTest.link(storyteller, CopyKey(CopySource.Storyteller, "s2"))

        // Then
        assertEquals(CopySource.Storyteller, result.getError()?.repeatedLinkSource())
        assertTrue(database.liveLinks.isEmpty())
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `linking fails when the merged link would repeat a source`() = runTest {
        // Given
        setup(
            link("link-1", "library:b1", "storyteller:s1"),
            link("link-2", "audiobookshelf:a1", "storyteller:s2"),
        )

        // When
        val result = classUnderTest.link(library, audiobookshelf)

        // Then
        assertEquals(CopySource.Storyteller, result.getError()?.repeatedLinkSource())
        assertEquals(2, database.liveLinks.size)
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `linking copies that are already linked changes nothing`() = runTest {
        // Given
        setup(link("link-1", "library:b1", "storyteller:s1"))

        // When
        val result = classUnderTest.link(library, storyteller)

        // Then
        assertEquals("link-1", result.get()?.linkId)
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `unlinking a copy from a two-member link deletes it and records one never-decision`() =
        runTest {
            // Given
            setup(link("link-1", "library:b1", "storyteller:s1").copy(remoteRevision = 2))

            // When
            val result = classUnderTest.unlink(storyteller)

            // Then
            assertEquals(Ok(Unit), result)
            assertTrue(database.liveLinks.isEmpty())
            val decision = database.decisions.value.values.single()
            assertEquals("library:b1|storyteller:s1", decision.pairKey)
            assertEquals("never", decision.decision)
            assertEquals(
                listOf(
                    SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK to SyncOutboxEntry.OPERATION_DELETE,
                    SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION to
                        SyncOutboxEntry.OPERATION_UPSERT,
                ),
                database.outbox.map { entry -> entry.entityType to entry.operation },
            )
        }

    @Test
    fun `unlinking a copy from a larger link keeps the rest linked`() = runTest {
        // Given
        setup(link("link-1", "library:b1", "storyteller:s1", "audiobookshelf:a1"))

        // When
        classUnderTest.unlink(storyteller)

        // Then
        assertEquals(
            listOf("audiobookshelf:a1", "library:b1"),
            database.liveLinks.single().members,
        )
        assertEquals(
            setOf("library:b1|storyteller:s1", "audiobookshelf:a1|storyteller:s1"),
            database.decisions.value.keys,
        )
        assertEquals(
            listOf(SyncOutboxEntry.OPERATION_UPSERT),
            database.outbox
                .filter { entry -> entry.entityType == SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK }
                .map { entry -> entry.operation },
        )
        val entry = database.outbox.first()
        assertTrue(entry.payload.contains("\"removed_members\":[\"storyteller:s1\"]"))
    }

    @Test
    fun `unlinking a copy that is not linked changes nothing`() = runTest {
        // Given
        setup()

        // When
        val result = classUnderTest.unlink(storyteller)

        // Then
        assertEquals(Ok(Unit), result)
        assertTrue(database.outbox.isEmpty())
    }

    @Test
    fun `decide records the decision and queues it for sync`() = runTest {
        // Given
        setup()

        // When
        classUnderTest.decide(storyteller, library, LinkDecisionType.Skip)

        // Then
        val decisions = classUnderTest.observeDecisions().first()
        assertEquals(LinkDecisionType.Skip, decisions["library:b1|storyteller:s1"]?.type)
        val entry = database.outbox.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION, entry.entityType)
        assertEquals("library:b1|storyteller:s1", entry.entityId)
    }

    @Test
    fun `observeLinks maps stored links and ignores members it cannot parse`() = runTest {
        // Given
        setup(link("link-1", "library:b1", "storyteller:s1", "kindle:k1"))

        // When
        val links = classUnderTest.observeLinks().first()

        // Then
        assertEquals(setOf(library, storyteller), links.single().members)
    }

    private fun link(
        linkId: String,
        vararg members: String,
        createdAt: String = "2026-10-01T10:00:00Z",
    ) = BookLinkEntity(
        linkId = linkId,
        members = members.sorted(),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private object DirectDatabaseExecutor : DatabaseExecutor {
        override suspend fun <T> executeDatabaseOperation(
            reportException: Boolean,
            operation: suspend () -> T,
        ): AppResult<T> = Ok(operation())
    }
}
