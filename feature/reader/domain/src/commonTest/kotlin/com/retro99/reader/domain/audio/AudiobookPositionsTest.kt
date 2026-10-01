package com.retro99.reader.domain.audio

import com.retro99.reader.domain.model.PositionDomainModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobookPositionsTest {

    private val fortyFiles = List(40) { _ -> 15 * MINUTE }

    @Test
    fun `a position in a later file records the book-level time, length and progress`() {
        // When
        val position = build(trackIndex = 20, offsetMs = 7 * MINUTE + 30 * SECOND, fortyFiles)

        // Then
        assertEquals(7 * MINUTE + 30 * SECOND, position.audioTimestampMs)
        assertEquals(20, position.chapterIndex)
        assertEquals(40, position.totalChapters)
        assertEquals(5 * HOUR + 7 * MINUTE + 30 * SECOND, position.bookTimeMs)
        assertEquals(10 * HOUR, position.totalDurationMs)
        assertEquals(0.5125, position.totalProgression!!, absoluteTolerance = 1e-9)
        assertEquals(position.totalProgression, position.progression)
    }

    @Test
    fun `unknown lengths leave the book-level values unknown`() {
        // Given
        val cases = listOf(null, emptyList(), List(39) { _ -> 15 * MINUTE })

        cases.forEach { durations ->
            // When
            val position = build(trackIndex = 20, offsetMs = 7 * MINUTE, durations)

            // Then
            assertEquals(7 * MINUTE, position.audioTimestampMs, "for $durations")
            assertEquals(20, position.chapterIndex)
            assertNull(position.bookTimeMs)
            assertNull(position.totalDurationMs)
            assertNull(position.totalProgression)
            assertNull(position.progression)
        }
    }

    @Test
    fun `a single file's book time is its offset`() {
        // When
        val position = PositionBuilder(trackCount = 1)
            .build(trackIndex = 0, offsetMs = 30 * MINUTE, listOf(HOUR))

        // Then
        assertEquals(30 * MINUTE, position.bookTimeMs)
        assertEquals(HOUR, position.totalDurationMs)
        assertEquals(0.5, position.totalProgression)
    }

    @Test
    fun `the player's timeline is used when every length is known`() {
        // Given
        val timeline = listOf(1_000L, 2_000L)

        // When
        val durations = chooseTrackDurations(timeline, cachedDurationsMs = listOf(9L, 9L), 2)

        // Then
        assertEquals(timeline, durations)
    }

    @Test
    fun `cached lengths are used while the timeline is incomplete`() {
        // When
        val durations = chooseTrackDurations(
            timelineDurationsMs = listOf(1_000L, null),
            cachedDurationsMs = listOf(1_100L, 2_000L),
            trackCount = 2,
        )

        // Then
        assertEquals(listOf(1_100L, 2_000L), durations)
    }

    @Test
    fun `lengths that don't match the playlist are unknown`() {
        // When
        val durations = chooseTrackDurations(
            timelineDurationsMs = listOf(1_000L, null, null),
            cachedDurationsMs = listOf(1_100L, 2_000L),
            trackCount = 3,
        )

        // Then
        assertNull(durations)
    }

    @Test
    fun `resume seeks to the book time when the lengths are known`() {
        // Given
        val saved = saved(bookTimeMs = 5 * HOUR + 7 * MINUTE + 30 * SECOND)

        // When
        val target = audiobookResumeTarget(saved, fortyFiles, trackCount = 40)

        // Then
        assertEquals(20 to 7 * MINUTE + 30 * SECOND, target)
    }

    @Test
    fun `resume falls back to the file and offset without lengths`() {
        // Given
        val saved = saved(bookTimeMs = 5 * HOUR, chapterIndex = 3, audioMs = 42_000L)

        // When
        val target = audiobookResumeTarget(saved, trackDurationsMs = null, trackCount = 40)

        // Then
        assertEquals(3 to 42_000L, target)
    }

    @Test
    fun `a pulled book time without lengths resumes a single file`() {
        // Given
        val saved = saved(bookTimeMs = 30 * MINUTE)

        // When
        val target = audiobookResumeTarget(saved, trackDurationsMs = null, trackCount = 1)

        // Then
        assertEquals(0 to 30 * MINUTE, target)
    }

    @Test
    fun `nothing to resume from, or a file that isn't in the playlist, doesn't seek`() {
        // Given
        val cases = listOf(
            saved(bookTimeMs = 5 * HOUR) to null,
            saved(chapterIndex = 41, audioMs = 1_000L) to null,
            saved() to null,
        )

        cases.forEach { (position, durations) ->
            // When
            val target = audiobookResumeTarget(position, durations, trackCount = 40)

            // Then
            assertNull(target, "for $position")
        }
    }

    @Test
    fun `numbered audio files play in number order`() {
        // Given
        val names = listOf("100", "02", "10", "01", "99", "intro", "11")

        // When
        val sorted = names.sortedWith(audioTrackFileOrder)

        // Then
        assertEquals(listOf("01", "02", "10", "11", "99", "100", "intro"), sorted)
    }

    private fun build(trackIndex: Int, offsetMs: Long, durations: List<Long>?) =
        PositionBuilder(trackCount = 40).build(trackIndex, offsetMs, durations)

    private class PositionBuilder(private val trackCount: Int) {
        fun build(trackIndex: Int, offsetMs: Long, durations: List<Long>?) =
            buildAudiobookPosition(
                bookUuid = "book",
                serverId = "server",
                trackIndex = trackIndex,
                offsetMs = offsetMs,
                trackCount = trackCount,
                trackDurationsMs = durations,
                timestamp = 1L,
            )
    }

    private fun saved(
        bookTimeMs: Long? = null,
        chapterIndex: Int? = null,
        audioMs: Long? = null,
    ) = PositionDomainModel(
        bookUuid = "book",
        serverId = "server",
        timestamp = 1L,
        createdAt = null,
        updatedAt = null,
        locatorHref = null,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = audioMs,
        chapterIndex = chapterIndex,
        progression = null,
        totalChapters = null,
        totalDurationMs = null,
        totalProgression = null,
        position = null,
        bookTimeMs = bookTimeMs,
    )

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND
        const val HOUR = 60 * MINUTE
    }
}
