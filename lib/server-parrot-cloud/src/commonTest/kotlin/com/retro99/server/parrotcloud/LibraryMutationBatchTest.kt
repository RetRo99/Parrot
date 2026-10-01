package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMutationBatchTest {
    @Test
    fun `book identities are pushed before links even when queued after them`() {
        // Given
        val link = entry(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK)
        val book = entry(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK)
        val decision = entry(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION)

        // When
        val batch = listOf(link, book, decision).libraryMutationBatch()

        // Then: links must be reselected after the duplicate response is applied.
        assertEquals(listOf(book), batch)
    }

    @Test
    fun `a batch without library books preserves link mutation ordering`() {
        // Given
        val deletion = entry(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK)
            .copy(operation = SyncOutboxEntry.OPERATION_DELETE)
        val survivor = entry(SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK)

        // When
        val batch = listOf(deletion, survivor).libraryMutationBatch()

        // Then
        assertEquals(listOf(deletion, survivor), batch)
    }

    private fun entry(type: String) = SyncOutboxEntry.new(
        entityType = type,
        entityId = "book-1",
        operation = SyncOutboxEntry.OPERATION_UPSERT,
        payload = "{}",
    )
}
