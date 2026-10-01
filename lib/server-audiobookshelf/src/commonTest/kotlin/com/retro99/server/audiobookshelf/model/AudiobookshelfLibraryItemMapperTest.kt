package com.retro99.server.audiobookshelf.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookshelfLibraryItemMapperTest {

    @Test
    fun `toDomain maps the identifiers and language used to suggest links`() {
        // Given
        val cases = listOf(
            metadata(isbn = "9780261102217", asin = "B007978NPG", language = "English") to
                Triple("9780261102217", "B007978NPG", "English"),
            metadata(isbn = " ", asin = "", language = null) to Triple(null, null, null),
            metadata(isbn = null, asin = null, language = "de") to Triple(null, null, "de"),
        )

        cases.forEach { (metadata, expected) ->
            // When
            val book = AudiobookshelfLibraryItemApiModel(
                id = "item-1",
                media = AudiobookshelfMediaApiModel(metadata = metadata),
            ).toDomain(serverId = "abs-1", baseUrl = "http://example.com")

            // Then
            assertEquals(expected, Triple(book.isbn, book.asin, book.language), "for $metadata")
        }
    }

    private fun metadata(isbn: String?, asin: String?, language: String?) =
        AudiobookshelfBookMetadataApiModel(
            title = "The Hobbit",
            isbn = isbn,
            asin = asin,
            language = language,
        )
}
