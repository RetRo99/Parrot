package com.retro99.reader.ui.reader

/** Where read-aloud is in the chapter, as the Listening sheet and the card show it. */
internal data class TtsSentencePosition(val number: Int, val count: Int)

/**
 * The position to show, or null when there is none to show: a count of zero is not a chapter
 * anyone is anywhere in, and a stopped engine is not on a sentence (TTS-F24).
 *
 * @param sentenceNumber the sentence being read, counted from one; null when none is.
 * @param sentenceCount sentences in the loaded chapter; zero before any are loaded.
 */
internal fun ttsSentencePosition(
    sentenceNumber: Int?,
    sentenceCount: Int,
): TtsSentencePosition? {
    if (sentenceNumber == null || sentenceNumber < 1 || sentenceCount <= 0) return null
    return TtsSentencePosition(
        number = sentenceNumber.coerceAtMost(sentenceCount),
        count = sentenceCount,
    )
}
