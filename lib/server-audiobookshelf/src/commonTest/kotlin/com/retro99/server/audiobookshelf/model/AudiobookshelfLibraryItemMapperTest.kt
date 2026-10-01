package com.retro99.server.audiobookshelf.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `the audiobook's length comes from its duration, or its audio files`() {
        // Given
        val withDuration = AudiobookshelfMediaApiModel(duration = 3_600.5)
        val withFiles = AudiobookshelfMediaApiModel(
            audioFiles = listOf(
                AudiobookshelfAudioFileApiModel(duration = 1_800.0),
                AudiobookshelfAudioFileApiModel(duration = 1_200.0),
            ),
        )

        // When
        val lengths = listOf(withDuration, withFiles, AudiobookshelfMediaApiModel()).map { media ->
            AudiobookshelfLibraryItemApiModel(id = "item-1", media = media)
                .toDomain(serverId = "abs-1", baseUrl = null)
                .audioDurationMs
        }

        // Then
        assertEquals(listOf(3_600_500L, 3_000_000L, null), lengths)
    }

    @Test
    fun `each audio file's length is cached in milliseconds, in playlist order`() {
        // Given
        val media = AudiobookshelfMediaApiModel(
            audioFiles = listOf(
                AudiobookshelfAudioFileApiModel(ino = "1", duration = 600.5),
                AudiobookshelfAudioFileApiModel(ino = "2", duration = 900.0),
            ),
        )

        // When
        val book = AudiobookshelfLibraryItemApiModel(id = "item-1", media = media)
            .toDomain(serverId = "abs-1", baseUrl = null)

        // Then
        assertEquals(listOf(600_500L, 900_000L), book.audioTrackDurationsMs)
    }

    @Test
    fun `missing file lengths leave the per-file lengths unknown`() {
        // Given
        val cases = listOf(
            AudiobookshelfMediaApiModel(),
            AudiobookshelfMediaApiModel(
                audioFiles = listOf(
                    AudiobookshelfAudioFileApiModel(ino = "1", duration = 600.5),
                    AudiobookshelfAudioFileApiModel(ino = "2", duration = null),
                ),
            ),
        )

        cases.forEach { media ->
            // When
            val book = AudiobookshelfLibraryItemApiModel(id = "item-1", media = media)
                .toDomain(serverId = "abs-1", baseUrl = null)

            // Then
            assertNull(book.audioTrackDurationsMs, "for $media")
        }
    }

    @Test
    fun `files the app can't download aren't counted`() {
        // Given
        val media = AudiobookshelfMediaApiModel(
            audioFiles = listOf(
                AudiobookshelfAudioFileApiModel(ino = "1", duration = 600.0),
                AudiobookshelfAudioFileApiModel(ino = null, duration = 100.0),
                AudiobookshelfAudioFileApiModel(ino = "3", duration = 300.0),
            ),
        )

        // When
        val book = AudiobookshelfLibraryItemApiModel(id = "item-1", media = media)
            .toDomain(serverId = "abs-1", baseUrl = null)

        // Then
        assertEquals(listOf(600_000L, 300_000L), book.audioTrackDurationsMs)
    }

    private fun metadata(isbn: String?, asin: String?, language: String?) =
        AudiobookshelfBookMetadataApiModel(
            title = "The Hobbit",
            isbn = isbn,
            asin = asin,
            language = language,
        )
}
