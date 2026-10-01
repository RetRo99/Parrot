package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.links.CopySource
import com.retro99.epub.api.globalBeginMs
import com.retro99.epub.api.globalEndMs
import com.retro99.server.api.TextAnchor
import com.retro99.sync.domain.ProgressKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CopyPositionTranslatorTest {

    private val matcher = TextAnchorMatcher()
    private val translator = CopyPositionTranslator(matcher = matcher)

    // The same text, split differently and with headings: a different edition's markup.
    private val sourceChapters = book("a", listOf(4, 4, 4))
    private val targetChapters = book("b", listOf(3, 3, 3, 3), headings = true)

    @Test
    fun `the same file content is exact and keeps the locator`() {
        // Given
        val source = copy(CopySource.Library, "lib", chapters = sourceChapters, contentHash = "h1")
        val target = copy(CopySource.Storyteller, "st", contentHash = "h1")
        val position = textPosition(sourceChapters, "a-s5")

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.Exact, result.confidence)
        assertEquals(TranslationStrategy.SameFile, result.strategy)
        assertEquals(position.locatorHref, result.position.locatorHref)
        assertEquals(position.progression, result.position.progression)
        assertEquals(position.cssSelector, result.position.cssSelector)
        assertEquals("st", result.position.bookUuid)
    }

    @Test
    fun `an anchor found once is high, at the right chapter and progression`() {
        // Given
        val source = copy(CopySource.Library, "lib", chapters = sourceChapters)
        val target = copy(CopySource.Storyteller, "st", chapters = targetChapters)
        val position = textPosition(sourceChapters, "a-s5")

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(TranslationStrategy.TextAnchor, result.strategy)
        val expected = targetChapters.pointOfSentence(5)
        assertEquals(targetChapters[expected.chapterIndex].href, result.position.locatorHref)
        val expectedProgression = targetChapters.progressionAt(expected)
        assertTrue(abs(expectedProgression - result.position.progression!!) <= 0.005)
        assertEquals("#b-s5", result.position.cssSelector)
        assertEquals(
            targetChapters.totalProgressionAt(expected),
            result.position.totalProgression!!,
            0.005,
        )
    }

    @Test
    fun `the stored anchor is used when the source file is not on this device`() {
        // Given
        val source = copy(CopySource.Audiobookshelf, "abs")
        val target = copy(CopySource.Library, "lib", chapters = targetChapters)
        val position = position("abs").copy(
            locatorHref = "somewhere.xhtml",
            totalProgression = 0.6,
            textAnchor = TextAnchor(before = SENTENCES[6], after = SENTENCES[7]),
        )

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals("#b-s7", result.position.cssSelector)
    }

    @Test
    fun `duplicate text is told apart by the text before it`() {
        // Given: sentence 1 appears again after sentence 9.
        val texts = SENTENCES.toMutableList().apply { add(10, SENTENCES[1]) }
        val target = copy(CopySource.Storyteller, "st", chapters = book("b", listOf(13), texts))
        val source = copy(CopySource.Library, "lib")
        val position = position("lib").copy(
            locatorHref = "x.xhtml",
            totalProgression = 0.8,
            textAnchor = TextAnchor(before = SENTENCES[9], after = SENTENCES[1]),
        )

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals("#b-s10", result.position.cssSelector)
    }

    @Test
    fun `duplicate text that can't be told apart falls through to approximate`() {
        // Given: the anchor has no text before it.
        val texts = SENTENCES.toMutableList().apply { add(10, SENTENCES[1]) }
        val target = copy(CopySource.Storyteller, "st", chapters = book("b", listOf(13), texts))
        val source = copy(CopySource.Library, "lib")
        val position = position("lib").copy(
            locatorHref = "x.xhtml",
            totalProgression = 0.8,
            textAnchor = TextAnchor(before = "", after = SENTENCES[1]),
        )

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals(TranslationStrategy.Proportional, result.strategy)
    }

    @Test
    fun `one changed word still matches high`() {
        // Given
        val changed = SENTENCES.toMutableList().apply {
            set(4, SENTENCES[4].replace("returned she", "replied she"))
        }
        val target = copy(CopySource.Storyteller, "st", chapters = book("b", listOf(6, 6), changed))
        val source = copy(CopySource.Library, "lib", chapters = sourceChapters)
        val position = textPosition(sourceChapters, "a-s4")

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals("#b-s4", result.position.cssSelector)
    }

    @Test
    fun `an anchor missing from the target is approximate`() {
        // Given
        val other = listOf("Completely different words fill this other book from start to end.")
        val target = copy(
            CopySource.Storyteller,
            "st",
            chapters = book("b", listOf(1), other),
        )
        val source = copy(CopySource.Library, "lib", chapters = sourceChapters)
        val position = textPosition(sourceChapters, "a-s6")

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals(position.totalProgression, result.position.totalProgression)
    }

    @Test
    fun `text to audio through smil is high and inside the covering clip`() {
        // Given
        val readaloudChapters = book("r", listOf(2, 4, 4, 2))
        val timing = timingFor(readaloudChapters)
        val bridge = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val source = copy(CopySource.Library, "lib", chapters = sourceChapters)
        val target = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = timing.totalDurationMs,
        )
        val position = textPosition(sourceChapters, "a-s6")

        // When
        val result = assertNotNull(translator.translate(source, position, target, listOf(bridge)))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(TranslationStrategy.SmilBridge, result.strategy)
        val clip = timing.clips.first { candidate -> candidate.fragmentId == "r-s6" }
        val ms = assertNotNull(result.position.audioTimestampMs)
        assertTrue(ms >= timing.globalBeginMs(clip) && ms < timing.globalEndMs(clip))
    }

    @Test
    fun `audio to text through smil is high`() {
        // Given
        val readaloudChapters = book("r", listOf(2, 4, 4, 2))
        val timing = timingFor(readaloudChapters)
        val bridge = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val source = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = timing.totalDurationMs,
        )
        val target = copy(CopySource.Library, "lib", chapters = targetChapters)
        val clip = timing.clips.first { candidate -> candidate.fragmentId == "r-s8" }
        val position = audioPosition(timing.globalBeginMs(clip) + 2_000, timing.totalDurationMs)

        // When
        val result = assertNotNull(translator.translate(source, position, target, listOf(bridge)))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(TranslationStrategy.SmilBridge, result.strategy)
        assertEquals("#b-s8", result.position.cssSelector)
    }

    @Test
    fun `audiobook durations within 1 percent map directly onto a read-aloud`() {
        // Given
        val readaloudChapters = book("r", listOf(6, 6))
        val timing = timingFor(readaloudChapters)
        val target = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val source = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = (timing.totalDurationMs * 1.005).toLong(),
        )
        val position = audioPosition(55_000, source.audioDurationMs!!)

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals("#r-s5", result.position.cssSelector)
        assertEquals(55_000L, result.position.audioTimestampMs)
    }

    @Test
    fun `audiobook durations 3 percent apart are approximate`() {
        // Given
        val readaloudChapters = book("r", listOf(6, 6))
        val timing = timingFor(readaloudChapters)
        val target = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val source = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = (timing.totalDurationMs * 1.03).toLong(),
        )
        val position = audioPosition(55_000, source.audioDurationMs!!)

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals(TranslationStrategy.Proportional, result.strategy)
    }

    @Test
    fun `audio to audio maps time directly and never calls the text matcher`() {
        // Given
        val readaloudChapters = book("r", listOf(6, 6))
        val timing = timingFor(readaloudChapters)
        val source = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val target = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = timing.totalDurationMs,
        )
        val position = textPosition(readaloudChapters, "r-s3", bookUuid = "st")
            .copy(audioTimestampMs = 31_000, totalDurationMs = timing.totalDurationMs)

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then: the target's file lengths aren't known, so only the book time is set.
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(31_000L, result.position.bookTimeMs)
        assertNull(result.position.audioTimestampMs)
        assertEquals(0, matcher.matchCount)
    }

    @Test
    fun `audio onto a multi-file audiobook finds the file when its lengths are known`() {
        // Given
        val readaloudChapters = book("r", listOf(6, 6))
        val timing = timingFor(readaloudChapters)
        val source = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val firstFile = timing.totalDurationMs / 2
        val target = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = timing.totalDurationMs,
            trackDurationsMs = listOf(firstFile, timing.totalDurationMs - firstFile),
        )
        val position = textPosition(readaloudChapters, "r-s3", bookUuid = "st")
            .copy(audioTimestampMs = firstFile + 1_000, totalDurationMs = timing.totalDurationMs)

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(firstFile + 1_000, result.position.bookTimeMs)
        assertEquals(1, result.position.chapterIndex)
        assertEquals(1_000L, result.position.audioTimestampMs)
        assertEquals(2, result.position.totalChapters)
    }

    @Test
    fun `an audiobook position with its book time maps directly onto a read-aloud`() {
        // Given: the player's position in file 2 of 2, with its time from the start of the book.
        val readaloudChapters = book("r", listOf(2, 4, 4, 2))
        val timing = timingFor(readaloudChapters)
        val target = copy(
            CopySource.Storyteller,
            "st",
            chapters = readaloudChapters,
            timing = timing,
        )
        val source = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = timing.totalDurationMs,
        )
        val clip = timing.clips.first { candidate -> candidate.fragmentId == "r-s8" }
        val bookTime = timing.globalBeginMs(clip) + 500
        val position = audioPosition(5_000, timing.totalDurationMs).copy(
            chapterIndex = 1,
            totalChapters = 2,
            bookTimeMs = bookTime,
        )

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(TranslationStrategy.SmilBridge, result.strategy)
        assertEquals("#r-s8", result.position.cssSelector)
    }

    @Test
    fun `a mapped time past the end is clamped to the duration`() {
        // Given
        val target = copy(
            CopySource.Audiobookshelf,
            "abs",
            kind = ProgressKind.AUDIO,
            audioDurationMs = 100_000,
        )
        val source = copy(
            CopySource.Storyteller,
            "st",
            chapters = book("r", listOf(6, 6)),
            timing = timingFor(book("r", listOf(6, 6))).copy(totalDurationMs = 100_500),
        )
        val position = position("st").copy(audioTimestampMs = 100_400, totalDurationMs = 100_500)

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.High, result.confidence)
        assertEquals(100_000L, result.position.bookTimeMs)
    }

    @Test
    fun `equal chapter counts map the chapter and the progression inside it`() {
        // Given
        val target = copy(CopySource.Storyteller, "st", chapters = book("b", listOf(2, 8, 2)))
        val source = copy(CopySource.Library, "lib")
        val position = position("lib").copy(
            locatorHref = "a2.xhtml",
            chapterIndex = 1,
            totalChapters = 3,
            progression = 0.5,
            totalProgression = 0.5,
        )

        // When
        val result = assertNotNull(translator.translate(source, position, target))

        // Then
        assertEquals(TranslationConfidence.Approximate, result.confidence)
        assertEquals("OEBPS/b2.xhtml", result.position.locatorHref)
        assertEquals(0.5, result.position.progression)
    }
}
