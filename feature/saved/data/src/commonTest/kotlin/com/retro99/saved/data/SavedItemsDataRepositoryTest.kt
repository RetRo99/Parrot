package com.retro99.saved.data

import com.retro99.database.api.saved.SavedItemEntity
import com.retro99.database.api.saved.SavedItemMutation
import com.retro99.database.api.saved.SavedItemsDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class SavedItemsDataRepositoryTest {
    private val database = FakeSavedItemsDatabase()
    private val repository = SavedItemsDataRepository(database)

    @Test
    fun `saving queues an upsert that names the item`() = runTest {
        // When
        repository.save(listOf(highlight()))

        // Then
        val entry = database.outbox.single()
        assertEquals(SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM, entry.entityType)
        assertEquals("item-1", entry.entityId)
        assertEquals(SyncOutboxEntry.OPERATION_UPSERT, entry.operation)
        assertEquals("""{"item_id":"item-1"}""", entry.payload)
        assertEquals("amber", database.rows.getValue("item-1").color)
    }

    @Test
    fun `an edit keeps the revision Parrot Cloud acknowledged`() = runTest {
        // Given
        repository.save(listOf(highlight()))
        database.setRemoteRevision("item-1", 7)

        // When
        repository.save(listOf(highlight().copy(note = "  A thought  ")))

        // Then
        val row = database.rows.getValue("item-1")
        assertEquals(7L, row.remoteRevision)
        assertEquals("A thought", row.note, "notes are trimmed")
        assertEquals(7L, database.outbox.last().baseRevision)
    }

    @Test
    fun `delete leaves a tombstone, queues a delete and returns the item for undo`() = runTest {
        // Given
        repository.save(listOf(highlight()))

        // When
        val deleted = repository.delete("item-1")

        // Then
        assertNotNull(deleted)
        assertEquals("item-1", deleted.id)
        assertNotNull(database.rows.getValue("item-1").deletedAt)
        assertEquals(SyncOutboxEntry.OPERATION_DELETE, database.outbox.last().operation)
        assertNull(repository.delete("item-1"), "deleting twice does nothing")
    }

    @Test
    fun `restoring a deleted item clears its tombstone`() = runTest {
        // Given
        repository.save(listOf(highlight()))
        val deleted = repository.delete("item-1")!!

        // When
        repository.save(listOf(deleted))

        // Then
        assertNull(database.rows.getValue("item-1").deletedAt)
        assertEquals(SyncOutboxEntry.OPERATION_UPSERT, database.outbox.last().operation)
    }

    @Test
    fun `bookmarks never store a colour`() = runTest {
        // When
        repository.save(listOf(highlight().copy(type = SavedItemType.Bookmark, color = HighlightColor.Rose)))

        // Then
        assertNull(database.rows.getValue("item-1").color)
    }

    private fun highlight() = SavedItem(
        id = "item-1",
        book = SavedBookRef(key = "library:book", uuid = "book", title = "Book", author = null),
        type = SavedItemType.Highlight,
        location = SavedLocation("c8.xhtml", null, 0.5, 0.62, null, "8. The Crossing"),
        anchor = TextAnchor(before = "x ", quote = "Out on the water", after = " y"),
        color = null,
        note = null,
        audio = null,
        snippetPending = false,
        createdAt = Instant.parse("2026-10-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-10-01T10:00:00Z"),
        remoteRevision = null,
    )
}

private class FakeSavedItemsDatabase : SavedItemsDatabase {
    val rows = linkedMapOf<String, SavedItemEntity>()
    val outbox = mutableListOf<SyncOutboxEntry>()
    private val version = MutableStateFlow(0)

    override suspend fun upsertWithMutations(mutations: List<SavedItemMutation>) {
        mutations.forEach { mutation ->
            rows[mutation.item.id] = mutation.item
            outbox += mutation.outboxEntry
        }
        version.value++
    }

    override suspend fun upsert(items: List<SavedItemEntity>) {
        items.forEach { item -> rows[item.id] = item }
        version.value++
    }

    override suspend fun applyRemote(
        remote: SavedItemEntity,
        shouldReplace: (local: SavedItemEntity?) -> Boolean,
    ): Boolean {
        if (!shouldReplace(rows[remote.id])) return false
        rows[remote.id] = remote
        return true
    }

    override fun observeForBooks(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItemEntity>> =
        version.map {
            rows.values.filter { row ->
                row.deletedAt == null && (row.bookKey in bookKeys || row.bookUuid in bookUuids)
            }
        }

    override suspend fun getForBooks(bookKeys: Set<String>, bookUuids: Set<String>): List<SavedItemEntity> =
        rows.values.filter { row -> row.deletedAt == null && (row.bookKey in bookKeys || row.bookUuid in bookUuids) }

    override fun observeAll(): Flow<List<SavedItemEntity>> =
        version.map { rows.values.filter { row -> row.deletedAt == null } }

    override suspend fun get(id: String): SavedItemEntity? = rows[id]

    override suspend fun getSnippetPending(bookUuid: String): List<SavedItemEntity> =
        rows.values.filter { row -> row.bookUuid == bookUuid && row.snippetPending }

    override suspend fun getUnsynced(): List<SavedItemEntity> =
        rows.values.filter { row -> row.remoteRevision == null }

    override suspend fun setRemoteRevision(id: String, revision: Long) {
        rows[id] = SavedItemRecord.from(rows.getValue(id)).copy(remoteRevision = revision)
    }

    override fun observePendingSyncCount(): Flow<Long> = version.map { outbox.size.toLong() }

    override suspend fun clearAll() {
        rows.clear()
    }
}
