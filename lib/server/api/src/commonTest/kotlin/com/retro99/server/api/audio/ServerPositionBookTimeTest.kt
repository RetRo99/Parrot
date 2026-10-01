package com.retro99.server.api.audio

import com.retro99.server.api.ServerPosition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sync outbox stores positions as JSON, so the book time must survive it. */
class ServerPositionBookTimeTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `the book time survives the outbox payload`() {
        // Given
        val position = position().copy(bookTimeMs = 18_450_000L)

        // When
        val decoded = json.decodeFromString(
            ServerPosition.serializer(),
            json.encodeToString(ServerPosition.serializer(), position),
        )

        // Then
        assertEquals(18_450_000L, decoded.bookTimeMs)
    }

    @Test
    fun `a payload written before book time existed reads as unknown`() {
        // Given
        val old = """{"bookUuid":"book","serverId":"abs-1","timestamp":1,"createdAt":null,""" +
            """"updatedAt":null,"locatorHref":null,"locatorType":null,"locatorTitle":null,""" +
            """"locatorTarget":null,"audioTimestampMs":450000,"chapterIndex":20,""" +
            """"progression":0.5,"totalChapters":40,"totalDurationMs":900000,""" +
            """"totalProgression":0.5,"position":null}"""

        // When
        val decoded = json.decodeFromString(ServerPosition.serializer(), old)

        // Then
        assertEquals(450_000L, decoded.audioTimestampMs)
        assertNull(decoded.bookTimeMs)
    }

    private fun position() = ServerPosition(
        bookUuid = "book",
        serverId = "abs-1",
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = null,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = 450_000L,
        chapterIndex = 20,
        progression = 0.5125,
        totalChapters = 40,
        totalDurationMs = 36_000_000L,
        totalProgression = 0.5125,
        position = null,
    )
}
