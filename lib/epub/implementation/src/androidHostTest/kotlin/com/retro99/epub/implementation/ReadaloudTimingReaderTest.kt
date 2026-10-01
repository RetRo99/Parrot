package com.retro99.epub.implementation

import com.github.michaelbull.result.get
import com.retro99.epub.api.globalBeginMs
import com.retro99.epub.implementation.di.EpubModule
import com.retro99.epub.implementation.smil.SmilClockParser
import com.retro99.epub.implementation.smil.SmilParser
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadaloudTimingReaderTest {

    private val directory: File = Files.createTempDirectory("epub-timing").toFile()
    private val reader = ReadaloudTimingReaderImpl(
        smilParser = SmilParser(
            xml = EpubModule().provideXml(),
            clockParser = SmilClockParser(SilentAnalytics),
            analytics = SilentAnalytics,
        ),
        analytics = SilentAnalytics,
    )

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `clips come in reading order with resolved paths and fragments`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory)

        // When
        val timing = assertNotNull(reader.readTiming(epub.path).get())

        // Then
        assertEquals(8, timing.clips.size)
        val first = timing.clips.first()
        assertEquals("OEBPS/text/r1.xhtml", first.textHref)
        assertEquals("r1-s1", first.fragmentId)
        assertEquals("OEBPS/audio/part1.mp3", first.audioSrc)
        assertEquals(0L to 6_000L, first.clipBeginMs to first.clipEndMs)
        assertEquals(
            listOf("r1-s1", "r2-s1", "r2-s2", "r2-s3", "r3-s1", "r3-s2", "r3-s3", "r4-s1"),
            timing.clips.map { clip -> clip.fragmentId },
        )
    }

    @Test
    fun `audio files are placed on one global timeline`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory)

        // When
        val timing = assertNotNull(reader.readTiming(epub.path).get())

        // Then
        assertEquals(
            mapOf("OEBPS/audio/part1.mp3" to 0L, "OEBPS/audio/part2.mp3" to 22_000L),
            timing.audioFileOffsetsMs,
        )
        assertEquals(28_000L, timing.totalDurationMs)
        val clip = timing.clips.first { candidate -> candidate.fragmentId == "r3-s3" }
        assertEquals(25_500L, timing.globalBeginMs(clip))
    }

    @Test
    fun `never reads the audio entries`() = runTest {
        // Given
        val epub = FixtureEpubs.readaloud(directory, audioBytes = EpubTextReaderTest.AUDIO_BYTES)
        val audioRange = dataRangeOf(epub, FixtureEpubs.AUDIO_ENTRY)
        val source = RecordingSource(epub.path)
        reader.openSource = { _ -> source }

        // When
        val timing = reader.readTiming(epub.path).get()

        // Then
        assertEquals(8, timing?.clips?.size)
        assertTrue(
            source.reads.none { range ->
                range.first <= audioRange.last && audioRange.first <= range.last
            },
        )
    }
}
