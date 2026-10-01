package com.retro99.books.data

import com.retro99.books.data.model.LibraryBookMetadataJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpubIsbnTest {

    @Test
    fun `isbnFromIdentifier reads identifiers that look like an ISBN`() {
        // Given
        val cases = listOf(
            "urn:isbn:9780261102217" to "9780261102217",
            "URN:ISBN:978-0-261-10221-7" to "9780261102217",
            "isbn:0-261-10221-4" to "0261102214",
            "9780261102217" to "9780261102217",
            "0 261 10221 4" to "0261102214",
            "080442957x" to "080442957X",
            "urn:uuid:6f1c0000-0000-4000-8000-000000000001" to null,
            "978026110221" to null,
            "12345678901234" to null,
            "calibre:42" to null,
            "" to null,
            null to null,
        )

        cases.forEach { (identifier, expected) ->
            // When
            val isbn = isbnFromIdentifier(identifier)

            // Then
            assertEquals(expected, isbn, "for $identifier")
        }
    }

    @Test
    fun `library metadata json round-trips the isbn`() {
        // Given
        val encoded = LibraryBookMetadataJson.encode(isbn = "9780261102217")

        // When
        val isbn = LibraryBookMetadataJson.isbn(encoded)

        // Then
        assertEquals("""{"isbn":"9780261102217"}""", encoded)
        assertEquals("9780261102217", isbn)
        assertNull(LibraryBookMetadataJson.encode(isbn = null))
        assertNull(LibraryBookMetadataJson.isbn(null))
        assertNull(LibraryBookMetadataJson.isbn("not json"))
        assertNull(LibraryBookMetadataJson.isbn("""{"series":"x"}"""))
    }
}
