package com.retro99.server.api.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioBookTimeTest {

    private val tracks = listOf(60_000L, 120_000L, 30_000L)

    @Test
    fun `book time adds the earlier tracks to the offset`() {
        // Given
        val cases = listOf(
            Triple(0, 10_000L, 10_000L),
            Triple(1, 5_000L, 65_000L),
            Triple(2, 30_000L, 210_000L),
        )

        cases.forEach { (track, offset, expected) ->
            // When
            val bookTime = bookTimeMs(tracks, track, offset)

            // Then
            assertEquals(expected, bookTime, "track $track at $offset")
        }
    }

    @Test
    fun `book time is unknown without lengths or outside the tracks`() {
        // Given
        val cases = listOf(
            Triple(emptyList<Long>(), 0, 0L),
            Triple(tracks, 3, 0L),
            Triple(tracks, -1, 0L),
        )

        cases.forEach { (durations, track, offset) ->
            // When
            val bookTime = bookTimeMs(durations, track, offset)

            // Then
            assertNull(bookTime, "track $track of $durations")
        }
    }

    @Test
    fun `an offset past its track's end is held at the end of that track`() {
        // When
        val bookTime = bookTimeMs(tracks, 0, 90_000L)

        // Then
        assertEquals(60_000L, bookTime)
    }

    @Test
    fun `a single track's book time is its offset`() {
        // When
        val bookTime = bookTimeMs(listOf(3_600_000L), 0, 1_234_567L)

        // Then
        assertEquals(1_234_567L, bookTime)
    }

    @Test
    fun `track position finds the track and the offset in it`() {
        // Given
        val cases = listOf(
            10_000L to (0 to 10_000L),
            65_000L to (1 to 5_000L),
            210_000L to (2 to 30_000L),
            // Exactly on a boundary belongs to the next track.
            60_000L to (1 to 0L),
            180_000L to (2 to 0L),
            // Past the end: the end of the last track.
            999_000L to (2 to 30_000L),
            -5L to (0 to 0L),
        )

        cases.forEach { (bookTime, expected) ->
            // When
            val position = trackPosition(tracks, bookTime)

            // Then
            assertEquals(expected, position, "book time $bookTime")
        }
    }

    @Test
    fun `track position is unknown without lengths`() {
        // When
        val position = trackPosition(emptyList(), 10_000L)

        // Then
        assertNull(position)
    }

    @Test
    fun `a single track's position is the book time`() {
        // When
        val position = trackPosition(listOf(3_600_000L), 1_234_567L)

        // Then
        assertEquals(0 to 1_234_567L, position)
    }

    @Test
    fun `the forty-file book resolves both ways`() {
        // Given
        val fortyFiles = List(40) { _ -> 15 * MINUTE }
        val bookTime = 5 * HOUR + 7 * MINUTE + 30 * SECOND

        // When
        val forward = bookTimeMs(fortyFiles, 20, 7 * MINUTE + 30 * SECOND)
        val back = trackPosition(fortyFiles, bookTime)

        // Then
        assertEquals(bookTime, forward)
        assertEquals(20 to 7 * MINUTE + 30 * SECOND, back)
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
    }
}
