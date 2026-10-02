package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecapEligibilityTest {

    private val text = (1..120).joinToString(" ") { "word$it" } + "."

    private fun stats(
        activeReadingMs: Long = 0,
        pageAdvances: Int = 0,
        ttsSentences: Int = 0,
        excerpt: String? = text,
        start: Double? = 0.10,
        end: Double? = 0.12,
        furthest: Double? = 0.12,
        previousExcerpt: String? = null,
        previousHash: String? = null,
    ) = RecapSessionStats(
        activeReadingMs = activeReadingMs,
        pageAdvances = pageAdvances,
        ttsSentences = ttsSentences,
        excerpt = excerpt,
        startTotalProgression = start,
        endTotalProgression = end,
        furthestTotalProgression = furthest,
        previousExcerpt = previousExcerpt,
        previousExcerptHash = previousHash,
    )

    private val tooLittle = RecapEligibilityDecision.Ineligible(RecapErrorCode.TOO_LITTLE_READING)

    @Test
    fun anyOneActivityThresholdIsEnough() {
        val eligible = RecapEligibilityDecision.Eligible
        assertEquals(eligible, RecapEligibility.evaluate(stats(activeReadingMs = 180_000)))
        assertEquals(eligible, RecapEligibility.evaluate(stats(pageAdvances = 2)))
        assertEquals(eligible, RecapEligibility.evaluate(stats(ttsSentences = 20)))
    }

    @Test
    fun belowEveryActivityThresholdIsSkipped() {
        val decision = RecapEligibility.evaluate(
            stats(activeReadingMs = 179_999, pageAdvances = 1, ttsSentences = 19),
        )
        assertEquals(tooLittle, decision)
    }

    @Test
    fun shortExcerptIsSkippedEvenAfterLongReading() {
        assertEquals(
            tooLittle,
            RecapEligibility.evaluate(stats(activeReadingMs = 600_000, excerpt = "  short  ")),
        )
        assertEquals(tooLittle, RecapEligibility.evaluate(stats(pageAdvances = 9, excerpt = null)))
    }

    @Test
    fun movingOnlyBackwardsIsARereadOnly() {
        val decision = RecapEligibility.evaluate(
            stats(pageAdvances = 5, start = 0.50, end = 0.40, furthest = 0.50),
        )
        assertEquals(RecapEligibilityDecision.Ineligible(RecapErrorCode.REREAD_ONLY), decision)
    }

    @Test
    fun goingBackAfterReadingForwardStillCounts() {
        val decision = RecapEligibility.evaluate(
            stats(pageAdvances = 5, start = 0.50, end = 0.40, furthest = 0.55),
        )
        assertEquals(RecapEligibilityDecision.Eligible, decision)
    }

    @Test
    fun unknownProgressionNeverMarksAReread() {
        val decision = RecapEligibility.evaluate(stats(pageAdvances = 5, start = null))
        assertEquals(RecapEligibilityDecision.Eligible, decision)
    }

    @Test
    fun mostlyIdenticalToPreviousSessionIsADuplicate() {
        val previous = text.replace("word120.", "") + " and a tail"
        val decision = RecapEligibility.evaluate(stats(pageAdvances = 3, previousExcerpt = previous))
        assertEquals(
            RecapEligibilityDecision.Ineligible(RecapErrorCode.DUPLICATE_OF_PREVIOUS),
            decision,
        )
    }

    @Test
    fun matchingFingerprintIsADuplicateWhenTextIsGone() {
        val hash = RecapEligibility.fingerprint("  " + text.uppercase() + "\n")
        val decision = RecapEligibility.evaluate(stats(pageAdvances = 3, previousHash = hash))
        assertEquals(
            RecapEligibilityDecision.Ineligible(RecapErrorCode.DUPLICATE_OF_PREVIOUS),
            decision,
        )
    }

    @Test
    fun differentTextIsNotADuplicate() {
        val other = (1..120).joinToString(" ") { "other$it" }
        val decision = RecapEligibility.evaluate(
            stats(pageAdvances = 3, previousExcerpt = other, previousHash = "0"),
        )
        assertEquals(RecapEligibilityDecision.Eligible, decision)
        assertNotEquals(RecapEligibility.fingerprint(text), RecapEligibility.fingerprint(other))
    }
}
