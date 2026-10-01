package com.retro99.books.data

import com.retro99.books.data.model.LibraryBookJsonCodec
import com.retro99.database.api.library.LibraryBookEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LibraryBookJsonCodecTest {

    @Test
    fun `the payload carries the book id and the source hash as separate fields`() {
        // Given
        val book = LibraryBookEntity(
            libraryBookId = BOOK_ID,
            title = "A book",
            author = "An author",
            sourceContentHash = "hash-123",
            sourceContentHashAlgorithm = "sha-256-v1",
            addedAt = "2026-10-01T00:00:00Z",
        )

        // When
        val payload = Json.parseToJsonElement(LibraryBookJsonCodec.encode(book, "ebook")).jsonObject

        // Then
        assertEquals(BOOK_ID, payload["library_book_id"]?.jsonPrimitive?.content)
        assertFalse(payload.getValue("library_book_id").jsonPrimitive.content.contains(':'))
        assertEquals("hash-123", payload["source_content_hash"]?.jsonPrimitive?.content)
        assertEquals("sha-256-v1", payload["source_content_hash_algorithm"]?.jsonPrimitive?.content)
        assertEquals("A book", payload["title"]?.jsonPrimitive?.content)
        assertEquals("An author", payload["author"]?.jsonPrimitive?.content)
        assertEquals("ebook", payload["format"]?.jsonPrimitive?.content)
        assertFalse("cloud_book_id" in payload)
        assertFalse("content_hash" in payload)
    }

    private companion object {
        const val BOOK_ID = "44444444-4444-4444-8444-444444444444"
    }
}
