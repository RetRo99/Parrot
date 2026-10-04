package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.links.BookLinkDecisionEntity
import com.retro99.database.api.links.BookLinkEntity
import com.retro99.database.api.links.BookLinkWrite
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.library.deleteBookFromDeviceRows
import com.retro99.database.implementation.dao.library.mergeLibraryBookRows
import com.retro99.database.implementation.dao.library.upsertDeviceFileRow
import com.retro99.database.implementation.dao.library.upsertLibraryBookRow
import com.retro99.database.implementation.dao.sync.enqueue
import com.retro99.database.implementation.dao.links.readBookLink
import com.retro99.database.implementation.dao.links.readBookLinkDecisions
import com.retro99.database.implementation.dao.links.writeBookLinks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryBookMergeTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        database.upsertLibraryBookRow(book(FROM))
        database.upsertLibraryBookRow(book(INTO))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `merge keeps the most recently updated position`() {
        // Given
        insertPosition(bookUuid = FROM, updatedAt = "2026-09-30T10:00:00Z", progression = 0.6)
        insertPosition(bookUuid = INTO, updatedAt = "2026-09-29T10:00:00Z", progression = 0.2)

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val position = database.positionQueries.getPositionByBookUuid(INTO).executeAsOne()
        assertEquals(0.6, position.progression)
        assertEquals(INTO, position.library_book_id)
        assertNull(database.positionQueries.getPositionByBookUuid(FROM).executeAsOneOrNull())
    }

    @Test
    fun `merge keeps the surviving position when it is newer`() {
        // Given
        insertPosition(bookUuid = FROM, updatedAt = "2026-09-28T10:00:00Z", progression = 0.6)
        insertPosition(bookUuid = INTO, updatedAt = "2026-09-29T10:00:00Z", progression = 0.2)

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val position = database.positionQueries.getPositionByBookUuid(INTO).executeAsOne()
        assertEquals(0.2, position.progression)
        assertNull(database.positionQueries.getPositionByBookUuid(FROM).executeAsOneOrNull())
    }

    @Test
    fun `merge moves a position that only the merged book has`() {
        // Given
        insertPosition(bookUuid = FROM, updatedAt = "2026-09-30T10:00:00Z", progression = 0.4)

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val position = database.positionQueries.getPositionByBookUuid(INTO).executeAsOne()
        assertEquals(0.4, position.progression)
        assertEquals(INTO, position.library_book_id)
    }

    @Test
    fun `merge deletes the merged remote position`() {
        // Given
        database.positionQueries.upsertRemotePosition(
            FROM, FROM, 1, null, null, null, null, null, null, null, null, null, null, 0.5, null,
            null, null, null, null, null, null, null,
        )

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertNull(database.positionQueries.getRemotePositionByBookUuid(FROM).executeAsOneOrNull())
    }

    @Test
    fun `merge moves favorites without duplicating them`() {
        // Given
        database.favoriteQueries.insertFavorite(FROM, "2026-09-01")
        database.favoriteQueries.insertFavorite(INTO, "2026-09-02")

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertEquals(listOf(INTO), database.favoriteQueries.getAllFavorites().executeAsList())
    }

    @Test
    fun `merge moves saved items and reading sessions`() {
        // Given
        database.savedItemQueries.upsertSavedItem(
            "b1", "library:$FROM", FROM, null, null, "bookmark", "c1", null, null, null, null, null,
            null, null, null, null, null, null, null, 0, "2026-09-01", "2026-09-01", null, null,
        )
        database.readingSessionQueries.insertSession(
            FROM, "Book", "ebook", 1, 2, 1, null, null, null, null, null,
        )

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val moved = database.savedItemQueries.getSavedItemById("b1").executeAsOne()
        assertEquals(INTO, moved.book_uuid)
        assertEquals("library:$INTO", moved.book_key)
        assertEquals(
            listOf(INTO),
            database.readingSessionQueries.getAllSessions().executeAsList().map { row -> row.book_uuid },
        )
    }

    @Test
    fun `merge moves device files and returns the redundant ones`() {
        // Given
        database.upsertDeviceFileRow(deviceFile(FROM, "ebook", "/from_ebook.epub"))
        database.upsertDeviceFileRow(deviceFile(FROM, "readaloud", "/from_readaloud.epub"))
        database.upsertDeviceFileRow(deviceFile(INTO, "ebook", "/into_ebook.epub"))

        // When
        val redundant = database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertEquals(listOf("/from_ebook.epub"), redundant)
        val files = database.deviceFileQueries.getDeviceFilesForBook(INTO).executeAsList()
            .associate { file -> file.media_type to file.file_path }
        assertEquals(
            mapOf("ebook" to "/into_ebook.epub", "readaloud" to "/from_readaloud.epub"),
            files,
        )
        assertTrue(database.deviceFileQueries.getDeviceFilesForBook(FROM).executeAsList().isEmpty())
    }

    @Test
    fun `merge drops the merged Parrot file mirror`() {
        // Given
        database.cloudBookFileStateQueries.upsertCloudBookFileState(
            FROM, "file-1", "ebook", "", "f.epub", "available", 1, "h", "sha-256-v1", 1, "a",
        )

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertTrue(database.cloudBookFileStateQueries.getCloudBookFileStates(FROM).executeAsList().isEmpty())
    }

    @Test
    fun `merge moves running transfers and deletes finished ones`() {
        // Given
        insertTransfer("t-running", state = "transferring")
        insertTransfer("t-done", state = "completed")

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertEquals(INTO, database.cloudFileTransferQueries.getCloudFileTransfer("t-running").executeAsOne().library_book_id)
        assertNull(database.cloudFileTransferQueries.getCloudFileTransfer("t-done").executeAsOneOrNull())
    }

    @Test
    fun `merge rewrites pending outbox entries and drops the duplicate book upsert`() {
        // Given
        database.syncOutboxQueries.enqueue(
            SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                entityId = FROM,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"library_book_id":"$FROM"}""",
            ),
        )
        database.syncOutboxQueries.enqueue(
            SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                entityId = FROM,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"bookUuid":"$FROM","position":{"libraryBookId":"$FROM"}}""",
            ),
        )

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val entries = database.syncOutboxQueries.getAllMutations().executeAsList()
        assertEquals(1, entries.size)
        val position = entries.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_READING_POSITION, position.entity_type)
        assertEquals(INTO, position.entity_id)
        assertEquals("""{"bookUuid":"$INTO","position":{"libraryBookId":"$INTO"}}""", position.payload)
    }

    @Test
    fun `merge keeps the merged book's cover and description when the survivor has none`() {
        // Given
        database.upsertLibraryBookRow(
            book(FROM).copy(
                coverPath = "/covers/from.png",
                description = "From description",
                addedAt = "2026-01-01T00:00:00Z",
            ),
        )

        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        val survivor = database.libraryBookQueries.getLibraryBookById(INTO).executeAsOne()
        assertEquals("/covers/from.png", survivor.cover_path)
        assertEquals("From description", survivor.description)
        assertEquals("2026-01-01T00:00:00Z", survivor.added_at)
    }

    @Test
    fun `merge moves linked copies and replaces queued snapshots with the surviving id`() {
        // Given
        database.writeBookLinks(BookLinkWrite(
            links = listOf(link("link-1", "library:$FROM", "storyteller:s1")),
            outboxEntries = listOf(SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
                entityId = "link-1",
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"link_id":"link-1","members":["library:$FROM","storyteller:s1"]}""",
            )),
        ))

        // When
        database.mergeLibraryBookRows(FROM, INTO)

        // Then
        assertEquals(
            listOf("library:$INTO", "storyteller:s1"),
            database.readBookLink("link-1")!!.members,
        )
        val mutation = database.syncOutboxQueries.getAllMutations().executeAsList().single()
        val payload = Json.parseToJsonElement(mutation.payload).jsonObject
        assertEquals(
            listOf("library:$INTO", "storyteller:s1"),
            payload.getValue("members").jsonArray.map { member -> member.jsonPrimitive.content },
        )
        assertEquals(
            listOf("library:$FROM"),
            payload.getValue("removed_members").jsonArray.map { member ->
                member.jsonPrimitive.content
            },
        )
    }

    @Test
    fun `merge joins compatible existing links and queues deletion before the survivor`() {
        // Given
        database.writeBookLinks(BookLinkWrite(links = listOf(
            link("link-1", "library:$FROM", "storyteller:s1"),
            link("link-2", "library:$INTO", "audiobookshelf:a1"),
        )))

        // When
        database.mergeLibraryBookRows(FROM, INTO)

        // Then
        assertEquals(
            listOf("audiobookshelf:a1", "library:$INTO", "storyteller:s1"),
            database.readBookLink("link-1")!!.members,
        )
        assertNotNull(database.readBookLink("link-2")!!.deletedAt)
        assertEquals(
            listOf("delete", "upsert"),
            database.syncOutboxQueries.getAllMutations().executeAsList()
                .map { mutation -> mutation.operation },
        )
    }

    @Test
    fun `merge preserves unsent unlink intent in the replacement snapshot`() {
        // Given
        database.writeBookLinks(BookLinkWrite(
            links = listOf(link("link-1", "library:$FROM", "storyteller:s1")),
            outboxEntries = listOf(SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
                entityId = "link-1",
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"link_id":"link-1","removed_members":["audiobookshelf:a1"]}""",
            )),
        ))

        // When
        database.mergeLibraryBookRows(FROM, INTO)

        // Then
        val mutation = database.syncOutboxQueries.getAllMutations().executeAsList().single()
        val removed = Json.parseToJsonElement(mutation.payload).jsonObject
            .getValue("removed_members").jsonArray
            .map { member -> member.jsonPrimitive.content }.toSet()
        assertEquals(setOf("audiobookshelf:a1", "library:$FROM"), removed)
    }

    @Test
    fun `merge retains the surviving book link when server copies conflict`() {
        // Given
        database.writeBookLinks(BookLinkWrite(links = listOf(
            link("link-1", "library:$FROM", "storyteller:s1"),
            link("link-2", "library:$INTO", "storyteller:s2"),
        )))

        // When
        database.mergeLibraryBookRows(FROM, INTO)

        // Then
        assertNotNull(database.readBookLink("link-1")!!.deletedAt)
        assertEquals(
            listOf("library:$INTO", "storyteller:s2"),
            database.readBookLink("link-2")!!.members,
        )
    }

    @Test
    fun `merge migrates decisions and keeps the latest decision for the surviving pair`() {
        // Given
        val fromPair = "library:$FROM|storyteller:s1"
        val intoPair = "library:$INTO|storyteller:s1"
        database.writeBookLinks(BookLinkWrite(
            decisions = listOf(
                BookLinkDecisionEntity(fromPair, "skip", "2026-09-30T10:00:00Z"),
                BookLinkDecisionEntity(intoPair, "never", "2026-10-01T10:00:00Z", 3),
            ),
            outboxEntries = listOf(SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
                entityId = fromPair,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"pair_key":"$fromPair","decision":"skip"}""",
            )),
        ))

        // When
        database.mergeLibraryBookRows(FROM, INTO)

        // Then
        val decision = database.readBookLinkDecisions().single()
        assertEquals(intoPair, decision.pairKey)
        assertEquals("never", decision.decision)
        assertEquals(3L, decision.remoteRevision)
        val mutation = database.syncOutboxQueries.getAllMutations().executeAsList().single()
        assertEquals(intoPair, mutation.entity_id)
        assertTrue(mutation.payload.contains(intoPair))
        assertTrue(!mutation.payload.contains(fromPair))
    }

    @Test
    fun `merge deletes the merged library book`() {
        // When
        database.mergeLibraryBookRows(fromId = FROM, intoId = INTO)

        // Then
        assertNull(database.libraryBookQueries.getLibraryBookById(FROM).executeAsOneOrNull())
        assertNotNull(database.libraryBookQueries.getLibraryBookById(INTO).executeAsOneOrNull())
    }

    @Test
    fun `a failure midway leaves every table unchanged`() {
        // Given
        insertPosition(bookUuid = FROM, updatedAt = "2026-09-30T10:00:00Z", progression = 0.6)
        database.favoriteQueries.insertFavorite(FROM, "2026-09-01")
        database.upsertDeviceFileRow(deviceFile(FROM, "ebook", "/from_ebook.epub"))
        // Dropping a table the merge writes to after the position and favorite steps
        // makes the transaction fail partway through.
        driver.execute(null, "DROP TABLE cloud_book_file_state", 0)

        // When
        assertFailsWith<Exception> { database.mergeLibraryBookRows(fromId = FROM, intoId = INTO) }

        // Then
        assertNotNull(database.positionQueries.getPositionByBookUuid(FROM).executeAsOneOrNull())
        assertNull(database.positionQueries.getPositionByBookUuid(INTO).executeAsOneOrNull())
        assertEquals(listOf(FROM), database.favoriteQueries.getAllFavorites().executeAsList())
        assertEquals(1, database.deviceFileQueries.getDeviceFilesForBook(FROM).executeAsList().size)
        assertNotNull(database.libraryBookQueries.getLibraryBookById(FROM).executeAsOneOrNull())
    }

    @Test
    fun `deleting a book from the device drops its unsent upsert for any account`() {
        // Given
        database.syncOutboxQueries.enqueue(
            SyncOutboxEntry.new(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                entityId = FROM,
                operation = SyncOutboxEntry.OPERATION_UPSERT,
                payload = """{"library_book_id":"$FROM"}""",
            ),
        )
        database.syncOutboxQueries.bindUnassignedMutations("user-1")
        database.upsertDeviceFileRow(deviceFile(FROM, "ebook", "/from_ebook.epub"))

        // When
        database.deleteBookFromDeviceRows(FROM)

        // Then
        assertTrue(database.syncOutboxQueries.getAllMutations().executeAsList().isEmpty())
        assertTrue(database.deviceFileQueries.getDeviceFilesForBook(FROM).executeAsList().isEmpty())
        assertNull(database.libraryBookQueries.getLibraryBookById(FROM).executeAsOneOrNull())
    }

    private fun link(id: String, vararg members: String) = BookLinkEntity(
        linkId = id,
        members = members.sorted(),
        createdAt = "2026-09-30T10:00:00Z",
        updatedAt = "2026-09-30T10:00:00Z",
        remoteRevision = 1,
    )

    private fun book(id: String) = LibraryBookEntity(
        libraryBookId = id,
        title = "Book $id",
        addedAt = "2026-09-30T00:00:00Z",
    )

    private fun deviceFile(bookId: String, mediaType: String, path: String) = DeviceFileEntity(
        libraryBookId = bookId,
        mediaType = mediaType,
        filePath = path,
        fileSize = 1,
        contentHash = "h-$bookId-$mediaType",
        contentHashAlgorithm = "sha-256-v1",
        origin = DeviceFileEntity.ORIGIN_IMPORT,
        addedAt = "2026-09-30T00:00:00Z",
    )

    private fun insertPosition(bookUuid: String, updatedAt: String, progression: Double) {
        database.positionQueries.upsertPosition(
            bookUuid, bookUuid, 0, null, null, updatedAt, updatedAt, "c1", null, null, null,
            null, null, null, progression, null, null, progression, null, "user", null, null,
            null, null, null, null,
        )
    }

    private fun insertTransfer(transferId: String, state: String) {
        database.cloudFileTransferQueries.insertCloudFileTransfer(
            transferId, "parrot-cloud", "upload", FROM, null, "ebook", null, 1, 0, null, null,
            null, null, null, null, null, state, 0, null, null, "a", "a",
        )
    }

    private companion object {
        const val FROM = "11111111-1111-4111-8111-111111111111"
        const val INTO = "22222222-2222-4222-8222-222222222222"
    }
}
