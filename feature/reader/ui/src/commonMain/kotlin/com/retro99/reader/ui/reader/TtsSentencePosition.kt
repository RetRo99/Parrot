package com.retro99.reader.ui.reader

/** Where read-aloud is in the chapter, as the Listening sheet and the card show it. */
internal data class TtsSentencePosition(val number: Int, val count: Int)

/**
 * The position to show, or null when there is none to show.
 *
 * @param sentenceNumber the sentence being read, counted from one; null when none is.
 * @param sentenceCount sentences in the loaded chapter; zero before any are loaded.
 */
internal fun ttsSentencePosition(
    sentenceNumber: Int?,
    sentenceCount: Int,
): TtsSentencePosition? = TtsSentencePosition(
    number = (sentenceNumber ?: 1).coerceAtLeast(1),
    count = sentenceCount,
)
