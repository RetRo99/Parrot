package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopySource
import com.retro99.epub.api.globalBeginMs
import com.retro99.reader.domain.fakes.FakeCopyFiles
import com.retro99.reader.domain.fakes.FakePositionDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Slice 5 (P6b): an Audiobookshelf audiobook and a linked Storyteller read-aloud. */
class AudiobookReadaloudTimingTest {

    private val files = FakeCopyFiles()
    private val positions = FakePositionDatabase()
    private val audiobook = linkedCopy(
        CopySource.Audiobookshelf,
        "abs",
        hasEbook = false,
        hasAudiobook = true,
        serverId = "abs-1",
    )
    private val readaloud = linkedCopy(
        CopySource.Storyteller,
        "st",
        hasReadaloud = true,
        serverId = "st-1",
    )
    private val chapters = book("r", listOf(6, 6))
    private val timing = timingFor(chapters)

    init {
        files.chapters["st"] = chapters
        files.timings["st"] = timing
    }

    /** A position pulled from Audiobookshelf: a global time, no track. */
    private fun pulled(ms: Long) = position("abs").copy(
        serverId = "abs-1",
        audioTimestampMs = ms,
        observedAt = "2026-10-01T10:00:00Z",
    )

    @Test
    fun `cached lengths 0_5 percent apart map the time directly`() = runTest {
        // Given: the item's cached length, not the position, says how long the book is.
        files.durations["abs"] = (timing.totalDurationMs * 1.005).toLong()

        // When
        val result = assertNotNull(
            files.translateUseCase(positions)(audiobook, pulled(75_000), readaloud),
        )

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(TranslationStrategy.SmilBridge, result.strategy)
        assertEquals(75_000L, result.position.audioTimestampMs)
        assertEquals("#r-s7", result.position.cssSelector)
    }

    @Test
    fun `cached lengths 3 percent apart are proportional`() = runTest {
        // Given
        files.durations["abs"] = (timing.totalDurationMs * 1.03).toLong()

        // When
        val result = assertNotNull(
            files.translateUseCase(positions)(audiobook, pulled(75_000), readaloud),
        )

        // Then
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals(TranslationStrategy.Proportional, result.strategy)
    }

    @Test
    fun `equal track counts map the track and the way through it`() = runTest {
        // Given: the player's position, kept within track 2 of 2, half way.
        files.durations["abs"] = (timing.totalDurationMs * 1.2).toLong()
        val position = position("abs").copy(
            serverId = "abs-1",
            audioTimestampMs = 36_000,
            totalDurationMs = 72_000,
            chapterIndex = 1,
            totalChapters = 2,
            progression = 0.5,
            totalProgression = 0.5,
            observedAt = "2026-10-01T10:00:00Z",
        )

        // When
        val translate = files.translateUseCase(positions)
        val result = assertNotNull(translate(audiobook, position, readaloud))

        // Then: half way through the second audio file is sentence 9.
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals("#r-s9", result.position.cssSelector)
        val clip = timing.clips.first { candidate -> candidate.fragmentId == "r-s9" }
        assertEquals(timing.globalBeginMs(clip), result.position.audioTimestampMs)
    }

    @Test
    fun `a track-relative time never maps directly, even with matching lengths`() = runTest {
        // Given
        files.durations["abs"] = timing.totalDurationMs
        val position = position("abs").copy(
            serverId = "abs-1",
            audioTimestampMs = 5_000,
            totalDurationMs = 60_000,
            chapterIndex = 1,
            totalChapters = 2,
            progression = 5_000.0 / 60_000,
            observedAt = "2026-10-01T10:00:00Z",
        )

        // When
        val translate = files.translateUseCase(positions)
        val result = assertNotNull(translate(audiobook, position, readaloud))

        // Then: track 2 starts at sentence 6, not at the book's 5th second.
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals("#r-s6", result.position.cssSelector)
    }
}
