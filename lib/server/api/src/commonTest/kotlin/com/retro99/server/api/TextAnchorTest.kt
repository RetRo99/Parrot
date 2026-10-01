package com.retro99.server.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextAnchorTest {

    @Test
    fun `keeps the last 20 words before and the first 30 words after`() {
        // Given
        val before = (1..25).joinToString(" ") { index -> "b$index" }
        val after = (1..40).joinToString("  \n") { index -> "a$index" }

        // When
        val anchor = TextAnchor.of(before, after)

        // Then
        assertEquals((6..25).joinToString(" ") { index -> "b$index" }, anchor?.before)
        assertEquals((1..30).joinToString(" ") { index -> "a$index" }, anchor?.after)
    }

    @Test
    fun `no text after the position gives no anchor`() {
        // When
        val anchor = TextAnchor.of("Some words", "   ")

        // Then
        assertNull(anchor)
    }

    @Test
    fun `round trips through json`() {
        // Given
        val anchor = TextAnchor(before = "It was a \"dark\" night", after = "and stormy")

        // When
        val decoded = TextAnchor.fromJson(anchor.toJson())

        // Then
        assertEquals(anchor, decoded)
    }

    @Test
    fun `broken json gives no anchor`() {
        // When
        val decoded = TextAnchor.fromJson("{not json")

        // Then
        assertNull(decoded)
    }

    @Test
    fun `server positions never encode the local fields`() {
        // Given
        val position = ServerPosition(
            bookUuid = "book-1",
            serverId = "server-1",
            timestamp = 1L,
            createdAt = null,
            updatedAt = null,
            locatorHref = "c1.xhtml",
            locatorType = null,
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = null,
            chapterIndex = null,
            progression = 0.5,
            totalChapters = null,
            totalDurationMs = null,
            totalProgression = 0.1,
            position = null,
            origin = PositionOrigin.Manual,
            observedAt = "2026-10-01T10:00:00Z",
            textAnchor = TextAnchor(before = "before", after = "after"),
        )
        val json = Json { encodeDefaults = true }

        // When
        val encoded = json.encodeToString(ServerPosition.serializer(), position)

        // Then
        assertEquals(
            json.encodeToString(
                ServerPosition.serializer(),
                position.copy(
                    origin = PositionOrigin.User,
                    observedAt = null,
                    textAnchor = null,
                ),
            ),
            encoded,
        )
        assertEquals(false, encoded.contains("origin"))
        assertEquals(false, encoded.contains("textAnchor"))
        assertEquals(false, encoded.contains("observedAt"))
    }

    @Test
    fun `unknown origins read as user`() {
        assertEquals(PositionOrigin.User, PositionOrigin.fromValue("something-new"))
        assertEquals(PositionOrigin.LinkedCopy, PositionOrigin.fromValue("linked_copy"))
    }
}
