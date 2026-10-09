package com.retro99.reader.ui.navigator

/**
 * Starts read-aloud where there is something to read: in the chapter the reader is on, or in
 * the next one that has text (TTS-F14). Cover pages, title pages and image plates are the
 * ordinary case in a Project Gutenberg EPUB, and pressing play on one must not dead-end.
 *
 * The move itself is the hand-off a finished chapter already uses — go to the next chapter,
 * then start at its first sentence — so there is one mechanism, not two.
 *
 * The walk is bounded by [maxChapterMoves] (the length of the spine) and ends as soon as
 * [goToNextChapter] reports the end of the book. It only suspends, so a stop cancels it.
 *
 * @param hasSentencesHere whether the chapter now loaded has sentences to read.
 * @param startHere starts in the chapter the reader is on, at the sentence it chose.
 * @param goToNextChapter moves one chapter forward; false at the end of the book.
 * @param startAtChapterStart starts at the first sentence of the chapter moved to.
 * @return null when a start was made, [TtsPlaybackFailureReason.CONTENT_UNAVAILABLE] when no
 * chapter from here to the end of the book has anything to read.
 */
internal suspend fun startAtFirstChapterWithText(
    maxChapterMoves: Int,
    hasSentencesHere: suspend () -> Boolean,
    startHere: suspend () -> TtsPlaybackFailureReason?,
    goToNextChapter: suspend () -> Boolean,
    startAtChapterStart: suspend () -> TtsPlaybackFailureReason?,
): TtsPlaybackFailureReason? {
    if (hasSentencesHere()) return startHere()
    return TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
}
