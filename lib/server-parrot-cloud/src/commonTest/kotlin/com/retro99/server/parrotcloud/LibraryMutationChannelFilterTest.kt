package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMutationChannelFilterTest {

    @Test
    fun readingPositionEntriesStayOffTheLibraryMutationChannel() {
        val entries = listOf(
            entry("position", SyncOutboxEntry.ENTITY_TYPE_READING_POSITION),
            entry("session", SyncOutboxEntry.ENTITY_TYPE_READING_SESSION),
            entry("book", SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK),
        )

        val channelEntries = entries.filterLibraryMutationChannelEntries()

        assertEquals(listOf("session", "book"), channelEntries.map { entry -> entry.mutationId })
    }

    @Test
    fun onlyProgressEntriesAreFilteredOut() {
        val entries = listOf(
            entry("session", SyncOutboxEntry.ENTITY_TYPE_READING_SESSION),
            entry("saved_item", SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM),
        )

        assertEquals(entries, entries.filterLibraryMutationChannelEntries())
    }

    private fun entry(
        mutationId: String,
        entityType: String,
    ) = SyncOutboxEntry(
        mutationId = mutationId,
        cloudUserId = "account",
        entityType = entityType,
        entityId = "entity",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
        baseRevision = null,
        createdAt = "2026-09-25T12:00:00Z",
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
    )
}
