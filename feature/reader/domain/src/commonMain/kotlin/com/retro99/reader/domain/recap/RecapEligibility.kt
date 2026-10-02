package com.retro99.reader.domain.recap

/** What a finished session looked like, for [RecapEligibility]. */
data class RecapSessionStats(
    val activeReadingMs: Long,
    val pageAdvances: Int,
    val ttsSentences: Int,
    val excerpt: String?,
    val startTotalProgression: Double?,
    val endTotalProgression: Double?,
    val furthestTotalProgression: Double?,
    /** The previous session's excerpt, when it is still stored. */
    val previousExcerpt: String? = null,
    /** The previous session's [RecapEligibility.fingerprint]. */
    val previousExcerptHash: String? = null,
) {
    override fun toString(): String =
        "RecapSessionStats(ms=$activeReadingMs, pages=$pageAdvances, tts=$ttsSentences, " +
            "chars=${excerpt?.length})"
}

sealed interface RecapEligibilityDecision {
    data object Eligible : RecapEligibilityDecision

    data class Ineligible(val reason: RecapErrorCode) : RecapEligibilityDecision
}

/**
 * Whether a session is worth a recap. Pure, so it is unit-tested directly.
 * A session needs enough reading (any one activity threshold) AND enough
 * text, must have moved forward, and must not repeat the last session.
 */
object RecapEligibility {
    const val MIN_ACTIVE_READING_MS = 3 * 60 * 1000L
    const val MIN_PAGE_ADVANCES = 2
    const val MIN_TTS_SENTENCES = 20
    const val MIN_EXCERPT_CHARS = 400

    /** Share of this excerpt's word trigrams already in the previous one. */
    const val DUPLICATE_OVERLAP = 0.8

    /** Progression noise below this is "no movement". */
    const val PROGRESSION_EPSILON = 0.0005

    fun evaluate(stats: RecapSessionStats): RecapEligibilityDecision {
        val excerpt = stats.excerpt?.trim().orEmpty()
        val enoughActivity = stats.activeReadingMs >= MIN_ACTIVE_READING_MS ||
            stats.pageAdvances >= MIN_PAGE_ADVANCES ||
            stats.ttsSentences >= MIN_TTS_SENTENCES
        if (!enoughActivity || excerpt.length < MIN_EXCERPT_CHARS) {
            return RecapEligibilityDecision.Ineligible(RecapErrorCode.TOO_LITTLE_READING)
        }
        if (isRereadOnly(stats)) {
            return RecapEligibilityDecision.Ineligible(RecapErrorCode.REREAD_ONLY)
        }
        if (isDuplicate(excerpt, stats.previousExcerpt, stats.previousExcerptHash)) {
            return RecapEligibilityDecision.Ineligible(RecapErrorCode.DUPLICATE_OF_PREVIOUS)
        }
        return RecapEligibilityDecision.Eligible
    }

    /** Ended behind the start and never got past it. */
    private fun isRereadOnly(stats: RecapSessionStats): Boolean {
        val start = stats.startTotalProgression ?: return false
        val end = stats.endTotalProgression ?: return false
        val furthest = maxOf(stats.furthestTotalProgression ?: end, end)
        return end < start - PROGRESSION_EPSILON && furthest <= start + PROGRESSION_EPSILON
    }

    fun isDuplicate(excerpt: String, previous: String?, previousHash: String?): Boolean {
        if (previousHash != null && previousHash == fingerprint(excerpt)) return true
        if (previous.isNullOrBlank()) return false
        val current = trigrams(excerpt)
        if (current.isEmpty()) return false
        val before = trigrams(previous)
        val shared = current.count { it in before }
        return shared.toDouble() / current.size >= DUPLICATE_OVERLAP
    }

    /** Stable FNV-1a 64-bit hash of the normalised text; not a secret. */
    fun fingerprint(text: String): String {
        var hash = FNV_OFFSET
        for (char in normalize(text)) {
            hash = hash xor char.code.toULong()
            hash *= FNV_PRIME
        }
        return hash.toString(16).padStart(16, '0')
    }

    private fun normalize(text: String): String =
        words(text).joinToString(" ")

    private fun words(text: String): List<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    private fun trigrams(text: String): Set<String> {
        val words = words(text)
        if (words.size < 3) return words.toSet()
        return words.windowed(3) { it.joinToString(" ") }.toSet()
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private const val FNV_OFFSET = 0xcbf29ce484222325uL
    private const val FNV_PRIME = 0x100000001b3uL
}
