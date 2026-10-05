package com.retro99.server.parrotcloud

import com.retro99.database.api.saved.SavedItemEntity
import com.retro99.database.api.saved.SavedItemMutation
import com.retro99.database.api.saved.SavedItemsDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParrotCloudSavedItemSyncTest {
    private val items = InMemorySavedItems()
    private val outbox = DeletingOutbox()
    private val sync = ParrotCloudSavedItemSync(items, outbox)

    @Test
    fun `a word snapshot survives push and pull`() = runTest {
        val word = ParrotCloudSavedItemPayload(itemId = "word-1", bookKey = "library:book-a", type = "word", href = "chapter.xhtml",
            updatedAt = "2026-10-05T10:00:00Z", wordSelected = "mice", wordHeadword = "mouse", wordLanguage = "en",
            wordGloss = "a small rodent", wordPartOfSpeech = "noun").toEntity("book-a")
        items.put(word)
        val prepared = sync.preparePush(listOf(queued(word.id))).single()
        val payload = Json.parseToJsonElement(prepared.payload)
        assertEquals("mouse", payload.jsonObject.getValue("word_headword").jsonPrimitive.content)
        val otherItems = InMemorySavedItems()
        ParrotCloudSavedItemSync(otherItems, DeletingOutbox()).applyRemote(payload)
        assertEquals("mice", otherItems.rows.getValue(word.id).wordSelected)
        assertEquals("a small rodent", otherItems.rows.getValue(word.id).wordGloss)
    }

    @Test
    fun `a pushed entry carries the item's current row`() = runTest {
        // Given
        items.put(row(id = "11111111-1111-4111-8111-111111111111", note = "Latest note"))
        val entry = queued("11111111-1111-4111-8111-111111111111")

        // When
        val prepared = sync.preparePush(listOf(entry)).single()

        // Then
        val payload = Json.parseToJsonElement(prepared.payload).jsonObject
        assertEquals("Latest note", payload.getValue("note").jsonPrimitive.content)
        assertEquals("library:book-a", payload.getValue("book_key").jsonPrimitive.content)
        assertEquals(SyncOutboxEntry.OPERATION_UPSERT, prepared.operation)
    }

    @Test
    fun `a tombstoned row is pushed as a delete and a missing row leaves the outbox`() = runTest {
        // Given
        items.put(row(id = "a", deletedAt = "2026-10-03T10:00:00Z"))
        val other = SyncOutboxEntry.new("library_book", "book", "upsert", "{}")

        // When
        val prepared = sync.preparePush(listOf(queued("a"), queued("gone"), other))

        // Then
        assertEquals(listOf("a", "book"), prepared.map { entry -> entry.entityId })
        assertEquals(SyncOutboxEntry.OPERATION_DELETE, prepared.first().operation)
        assertEquals(1, outbox.deleted.size, "the entry for the missing item is dropped")
    }

    @Test
    fun `a newer remote edit replaces the local item and an older one does not`() = runTest {
        // Given
        items.put(row(id = "a", note = "Local", updatedAt = "2026-10-02T10:00:00Z"))

        // When
        sync.applyRemote(remotePayload(id = "a", note = "Older", updatedAt = "2026-10-01T10:00:00+00:00"))
        val afterOlder = items.rows.getValue("a").note
        sync.applyRemote(remotePayload(id = "a", note = "Newer", updatedAt = "2026-10-03T10:00:00+00:00"))

        // Then
        assertEquals("Local", afterOlder)
        val stored = items.rows.getValue("a")
        assertEquals("Newer", stored.note)
        assertEquals("2026-10-03T10:00:00Z", stored.updatedAt, "server times are stored in the app's form")
        assertEquals(4L, stored.remoteRevision)
    }

    @Test
    fun `a remote delete arrives as a tombstone`() = runTest {
        // Given
        items.put(row(id = "a", updatedAt = "2026-10-02T10:00:00Z"))

        // When
        sync.applyRemote(
            remotePayload(id = "a", updatedAt = "2026-10-03T10:00:00Z", deletedAt = "2026-10-03T10:00:00Z"),
        )

        // Then
        assertEquals("2026-10-03T10:00:00Z", items.rows.getValue("a").deletedAt)
    }

    @Test
    fun `an item from another device is stored under its copy key`() = runTest {
        // When
        sync.applyRemote(remotePayload(id = "new", bookKey = "storyteller:st-9", updatedAt = "2026-10-03T10:00:00Z"))

        // Then
        val stored = items.rows.getValue("new")
        assertEquals("storyteller:st-9", stored.bookKey)
        assertEquals("st-9", stored.bookUuid)
    }

    @Test
    fun `a conflict takes the server version and acceptance records the revision`() = runTest {
        // Given
        items.put(row(id = "a", note = "Mine", updatedAt = "2026-10-02T10:00:00Z"))

        // When
        sync.onConflict(
            SyncMutationResponse(
                mutationId = "m",
                status = "conflict",
                revision = 9,
                payload = remotePayload(id = "a", note = "Theirs", updatedAt = "2026-10-02T10:00:00Z").toString(),
                reason = null,
            ),
        )
        sync.onAccepted(queued("a"), SyncMutationResponse("m2", "accepted", 10, null, null))

        // Then
        assertEquals("Theirs", items.rows.getValue("a").note)
        assertEquals(10L, items.rows.getValue("a").remoteRevision)
    }

    @Test
    fun `items Parrot Cloud has never had are queued once`() = runTest {
        // Given
        items.put(row(id = "migrated", remoteRevision = null))
        items.put(row(id = "synced", remoteRevision = 3))

        // When
        sync.enqueueUnsynced("user-1")

        // Then
        val entry = outbox.enqueued.single()
        assertEquals("migrated", entry.entityId)
        assertEquals("user-1", entry.cloudUserId)
        assertTrue(entry.payload.contains("migrated"))
    }

    @Test
    fun `undecodable payloads are ignored`() = runTest {
        // When
        sync.applyRemote(Json.parseToJsonElement("""{"item_id": 3}"""))

        // Then
        assertNull(items.rows["3"])
    }

    private fun queued(id: String) = SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM,
        entityId = id,
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = """{"item_id":"$id"}""",
    )

    private fun remotePayload(
        id: String,
        note: String? = null,
        updatedAt: String,
        deletedAt: String? = null,
        bookKey: String = "library:book-a",
    ) = Json.parseToJsonElement(
        """
        {"item_id":"$id","book_key":"$bookKey","type":"highlight","href":"c8.xhtml",
         "text_quote":"Out on the water","color":"rose","note":${note?.let { "\"$it\"" } ?: "null"},
         "created_at":"2026-10-01T10:00:00+00:00","updated_at":"$updatedAt",
         "deleted_at":${deletedAt?.let { "\"$it\"" } ?: "null"},"remote_revision":4}
        """.trimIndent(),
    )

    private fun row(
        id: String,
        note: String? = null,
        updatedAt: String = "2026-10-01T10:00:00Z",
        deletedAt: String? = null,
        remoteRevision: Long? = 2,
    ): SavedItemEntity = remotePayload(id = id, note = note, updatedAt = updatedAt, deletedAt = deletedAt)
        .let { json -> Json { ignoreUnknownKeys = true }.decodeFromJsonElement(ParrotCloudSavedItemPayload.serializer(), json) }
        .copy(remoteRevision = remoteRevision)
        .toEntity(localBookUuid = "book-a")
}

internal class InMemorySavedItems : SavedItemsDatabase {
    val rows = linkedMapOf<String, SavedItemEntity>()

    fun put(item: SavedItemEntity) {
        rows[item.id] = item
    }

    override suspend fun upsertWithMutations(mutations: List<SavedItemMutation>) {
        mutations.forEach { mutation -> put(mutation.item) }
    }

    override suspend fun upsert(items: List<SavedItemEntity>) = items.forEach(::put)

    override suspend fun applyRemote(
        remote: SavedItemEntity,
        shouldReplace: (local: SavedItemEntity?) -> Boolean,
    ): Boolean {
        if (!shouldReplace(rows[remote.id])) return false
        put(remote)
        return true
    }

    override fun observeForBooks(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItemEntity>> =
        flowOf(rows.values.toList())

    override suspend fun getForBooks(bookKeys: Set<String>, bookUuids: Set<String>) = rows.values.toList()

    override fun observeAll(): Flow<List<SavedItemEntity>> = flowOf(rows.values.toList())

    override suspend fun get(id: String): SavedItemEntity? = rows[id]

    override suspend fun getSnippetPending(bookUuid: String) = emptyList<SavedItemEntity>()

    override suspend fun getUnsynced() = rows.values.filter { row -> row.remoteRevision == null }

    override suspend fun setRemoteRevision(id: String, revision: Long) {
        val row = rows.getValue(id)
        rows[id] = ParrotCloudSavedItemPayload(
            itemId = row.id,
            bookKey = row.bookKey,
            type = row.type,
            href = row.href,
            textQuote = row.textQuote,
            color = row.color,
            note = row.note,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            deletedAt = row.deletedAt,
            remoteRevision = revision,
        ).toEntity(localBookUuid = row.bookUuid)
    }

    override fun observePendingSyncCount(): Flow<Long> = flowOf(0L)

    override suspend fun clearAll() = rows.clear()
}

internal class DeletingOutbox : SyncOutboxDatabase {
    val enqueued = mutableListOf<SyncOutboxEntry>()
    val deleted = mutableListOf<String>()

    override suspend fun enqueue(entry: SyncOutboxEntry) {
        enqueued += entry
    }

    override suspend fun bindUnassignedMutations(cloudUserId: String) = Unit

    override suspend fun getPending(cloudUserId: String): List<SyncOutboxEntry> = enqueued

    override suspend fun updateBaseRevision(mutationId: String, baseRevision: Long) = Unit

    override suspend fun markDispatched(mutationId: String) = Unit

    override suspend fun markConflict(mutationId: String, error: String) = Unit

    override suspend fun delete(mutationId: String) {
        deleted += mutationId
    }

    override suspend fun deleteByEntityType(entityType: String) = Unit

    override suspend fun recordFailure(mutationId: String, nextAttemptAt: String, error: String) = Unit

    override suspend fun coalesce(entityType: String, entityId: String, entry: SyncOutboxEntry) = Unit

    override suspend fun clearAllData() = Unit
}

internal fun testSavedItemSync() = ParrotCloudSavedItemSync(InMemorySavedItems(), DeletingOutbox())
