package com.retro99.books.data

import com.retro99.books.data.model.LibraryBookJsonCodec
import com.retro99.books.data.model.LibraryBookLocalModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryBookJsonCodecTest {
    @Test
    fun encodeIncludesPortableLibraryMetadata() {
        val book = LibraryBookLocalModel(
            libraryBookId = "hash-123",
            contentHash = "hash-123",
            contentHashAlgorithm = "sha256",
            title = "A book",
            author = "An author",
            format = "ebook",
        )

        val payload = Json.parseToJsonElement(LibraryBookJsonCodec.encode(book)).jsonObject

        assertEquals("hash-123", payload["library_book_id"]?.jsonPrimitive?.content)
        assertEquals("hash-123", payload["content_hash"]?.jsonPrimitive?.content)
        assertEquals("sha256", payload["content_hash_algorithm"]?.jsonPrimitive?.content)
        assertEquals("A book", payload["title"]?.jsonPrimitive?.content)
        assertEquals("An author", payload["author"]?.jsonPrimitive?.content)
        assertEquals("ebook", payload["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodeRoundTripsServerOwnedFields() {
        val book = LibraryBookLocalModel(
            libraryBookId = "hash-123",
            contentHash = "hash-123",
            contentHashAlgorithm = "sha256",
            title = "A book",
            author = null,
            format = "ebook",
            remoteRevision = 4,
            deletedAt = "2026-09-18T10:00:00Z",
        )

        val decoded = LibraryBookJsonCodec.decode(LibraryBookJsonCodec.encode(book))

        assertEquals(book.libraryBookId, decoded.libraryBookId)
        assertEquals(book.contentHash, decoded.contentHash)
        assertEquals(book.contentHashAlgorithm, decoded.contentHashAlgorithm)
        assertEquals(book.title, decoded.title)
        assertEquals(book.format, decoded.format)
        assertEquals(book.remoteRevision, decoded.remoteRevision)
        assertEquals(book.deletedAt, decoded.deletedAt)
    }
}
