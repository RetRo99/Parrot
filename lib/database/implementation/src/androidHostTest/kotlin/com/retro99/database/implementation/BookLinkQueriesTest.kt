package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.links.observeBookLinkEntities
import com.retro99.database.implementation.dao.links.readBookLink
import com.retro99.database.implementation.dao.links.readBookLinkDecisions
import com.retro99.database.implementation.dao.links.writeBookLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookLinkQueriesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `inserting the same member into two links fails and changes nothing`() {
        // Given
        database.writeBookLinks(
            BookLinkWrite(links = listOf(link("link-1", "library:b1", "storyteller:s1"))),
        )

        // When
        assertFails {
            database.writeBookLinks(
                BookLinkWrite(
                    links = listOf(link("link-2", "storyteller:s1", "audiobookshelf:a1")),
                    outboxEntries = listOf(outboxEntry("link-2")),
                ),
            )
        }

        // Then
        assertEquals(listOf("link-1"), liveLinks().map { link -> link.linkId })
        assertNull(database.readBookLink("link-2"))
        assertEquals(0, database.syncOutboxQueries.getAllMutations().executeAsList().size)
    }

    @Test
    fun `deleting a link removes its members and keeps a tombstone`() {
        // Given
        val live = link("link-1", "library:b1", "storyteller:s1").copy(remoteRevision = 3)
        database.writeBookLinks(BookLinkWrite(links = listOf(live)))

        // When
        database.writeBookLinks(
            BookLinkWrite(links = listOf(live.copy(deletedAt = "2026-10-01T11:00:00Z"))),
        )

        // Then
        assertEquals(emptyList(), liveLinks())
        assertEquals(
            0L,
            database.bookLinkQueries.getBookLinkMembers("link-1").executeAsList().size.toLong(),
        )
        val tombstone = assertNotNull(database.readBookLink("link-1"))
        assertEquals("2026-10-01T11:00:00Z", tombstone.deletedAt)
        assertEquals(3L, tombstone.remoteRevision)
        assertEquals(emptyList(), tombstone.members)
    }

    @Test
    fun `members of a deleted link can join another link in the same write`() {
        // Given
        val first = link("link-1", "library:b1", "storyteller:s1")
        database.writeBookLinks(BookLinkWrite(links = listOf(first)))

        // When
        database.writeBookLinks(
            BookLinkWrite(
                links = listOf(
                    link("link-2", "library:b1", "storyteller:s1", "audiobookshelf:a1"),
                    first.copy(deletedAt = "2026-10-01T11:00:00Z"),
                ),
            ),
        )

        // Then
        assertEquals(
            listOf("audiobookshelf:a1", "library:b1", "storyteller:s1"),
            liveLinks().single().members,
        )
    }

    @Test
    fun `observeLinks emits live links with their members`() {
        // Given
        database.writeBookLinks(
            BookLinkWrite(
                links = listOf(
                    link("link-1", "storyteller:s1", "library:b1"),
                    link("link-2", "library:b2", "audiobookshelf:a2"),
                ),
            ),
        )

        // When
        val links = liveLinks()

        // Then
        assertEquals(
            listOf(
                "link-1" to listOf("library:b1", "storyteller:s1"),
                "link-2" to listOf("audiobookshelf:a2", "library:b2"),
            ),
            links.map { link -> link.linkId to link.members },
        )
    }

    @Test
    fun `updating a link replaces its members`() {
        // Given
        database.writeBookLinks(
            BookLinkWrite(
                links = listOf(link("link-1", "library:b1", "storyteller:s1", "audiobookshelf:a1")),
            ),
        )

        // When
        database.writeBookLinks(
            BookLinkWrite(links = listOf(link("link-1", "library:b1", "audiobookshelf:a1"))),
        )

        // Then
        assertEquals(listOf("audiobookshelf:a1", "library:b1"), liveLinks().single().members)
    }

    @Test
    fun `a write stores decisions and outbox entries with its links`() {
        // Given
        val decision = BookLinkDecisionEntity(
            pairKey = "library:b1|storyteller:s1",
            decision = "never",
            decidedAt = "2026-10-01T10:00:00Z",
        )

        // When
        database.writeBookLinks(
            BookLinkWrite(
                links = listOf(link("link-1", "library:b2", "storyteller:s2")),
                decisions = listOf(decision),
                outboxEntries = listOf(outboxEntry("link-1")),
            ),
        )

        // Then
        assertEquals(listOf(decision), database.readBookLinkDecisions())
        assertEquals(
            listOf("link-1"),
            database.syncOutboxQueries.getAllMutations().executeAsList()
                .map { mutation -> mutation.entity_id },
        )
    }

    private fun liveLinks(): List<BookLinkEntity> = runBlocking {
        database.observeBookLinkEntities(Dispatchers.Unconfined).first()
    }

    private fun link(linkId: String, vararg members: String) = BookLinkEntity(
        linkId = linkId,
        members = members.toList(),
        createdAt = "2026-10-01T10:00:00Z",
        updatedAt = "2026-10-01T10:00:00Z",
    )

    private fun outboxEntry(linkId: String) = SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
        entityId = linkId,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
    )
}
