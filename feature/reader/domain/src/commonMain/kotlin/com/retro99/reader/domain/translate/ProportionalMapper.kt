package com.retro99.reader.domain.translate

import com.retro99.epub.api.EpubChapterText

/** Where a proportional mapping lands: a whole-book progression, maybe a chapter and offset. */
data class ProportionalPlace(
    val totalProgression: Double,
    val chapterIndex: Int? = null,
    val progression: Double? = null,
)

/**
 * The fallback (§1.5, strategy 4): progression or audio time over duration. When both copies
 * have the same number of chapters, the chapter and the progression inside it carry over.
 */
class ProportionalMapper {

    fun sourceProgression(
        totalProgression: Double?,
        audioMs: Long?,
        durationMs: Long?,
    ): Double? {
        totalProgression?.let { value -> return value.coerceIn(0.0, 1.0) }
        if (audioMs != null && durationMs != null && durationMs > 0) {
            return (audioMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        }
        return null
    }

    fun map(
        totalProgression: Double,
        sourceChapterIndex: Int?,
        sourceChapterProgression: Double?,
        sourceChapterCount: Int?,
        targetChapters: List<EpubChapterText>?,
        targetChapterCount: Int?,
    ): ProportionalPlace {
        val chapterCount = targetChapters?.size ?: targetChapterCount
        val sameChapters = sourceChapterCount != null && sourceChapterCount == chapterCount &&
            sourceChapterIndex != null && sourceChapterIndex in 0 until chapterCount &&
            sourceChapterProgression != null
        if (sameChapters) {
            val chapterIndex = requireNotNull(sourceChapterIndex)
            val progression = requireNotNull(sourceChapterProgression).coerceIn(0.0, 1.0)
            val total = targetChapters?.let { chapters ->
                val chapter = chapters[chapterIndex]
                chapters.totalProgressionAt(
                    TextPoint(chapterIndex, (progression * chapter.text.length).toInt()),
                )
            } ?: totalProgression
            return ProportionalPlace(total.coerceIn(0.0, 1.0), chapterIndex, progression)
        }
        val wholeBook = ProportionalPlace(totalProgression.coerceIn(0.0, 1.0))
        val chapters = targetChapters ?: return wholeBook
        val point = chapters.pointAtTotalProgression(totalProgression) ?: return wholeBook
        return wholeBook.copy(
            chapterIndex = point.chapterIndex,
            progression = chapters.progressionAt(point),
        )
    }
}
