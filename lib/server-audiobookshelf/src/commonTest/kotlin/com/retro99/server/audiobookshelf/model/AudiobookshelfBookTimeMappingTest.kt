package com.retro99.server.audiobookshelf.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudiobookshelfBookTimeMappingTest {

    private val fortyFiles = List(40) { _ -> 15L * 60 * 1000 }

    @Test
    fun `a pulled position keeps the book time and finds the file when lengths are known`() =
        runTest {
            // Given
            val progress = AudiobookshelfMediaProgressApiModel(
                currentTime = 18_450.0,
                duration = 36_000.0,
                progress = 0.5125,
            )

            // When
            val known = progress.toServerPosition("item-1", "abs-1", trackDurationsMs = fortyFiles)
            val unknown = progress.toServerPosition("item-1", "abs-1")

            // Then
            assertEquals(18_450_000L, known.bookTimeMs)
            assertEquals(20, known.chapterIndex)
            assertEquals(450_000L, known.audioTimestampMs)
            assertEquals(36_000_000L, known.totalDurationMs)
            assertEquals(0.5125, known.totalProgression)
            assertEquals(18_450_000L, unknown.bookTimeMs)
            assertNull(unknown.chapterIndex)
            assertNull(unknown.audioTimestampMs)
        }

    @Test
    fun `a pulled web reader cfi becomes its chapter and keeps the raw value`() = runTest {
        // Given
        val progress = AudiobookshelfMediaProgressApiModel(
            currentTime = 0.0,
            ebookLocation = "epubcfi(/6/4!/4/2/8:0)",
            ebookProgress = 0.4,
        )

        // When
        val position = progress.toServerPosition("item-1", "abs-1") {
            listOf("cover.xhtml", "ch01.xhtml")
        }

        // Then
        assertEquals("ch01.xhtml", position.locatorHref)
        assertEquals(1, position.chapterIndex)
        assertEquals(0.4, position.totalProgression)
        assertEquals("epubcfi(/6/4!/4/2/8:0)", position.ebookLocationRaw)
    }
}
