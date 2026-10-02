package com.retro99.reader.domain.recap

/**
 * Capture side of a reading session, called by the reader. All calls return
 * immediately; writes happen on an app-scoped queue, in call order.
 *
 * Session ids are minted by the reader when a book opens (a random UUID).
 * Nothing is stored unless cloud recaps are enabled when the session starts.
 */
interface RecapSessionRecorder {
    fun onSessionStarted(
        sessionId: String,
        serverId: String,
        bookId: String,
        startPosition: RecapPosition,
        chapter: RecapChapter?,
        language: String? = null,
    )

    /**
     * Adds text the reader has demonstrably read or heard, in reading order.
     * Keeps only the most recent [RecapLimits.MAX_EXCERPT_CHARS] chars.
     */
    fun appendReadText(
        sessionId: String,
        text: String,
        chapter: RecapChapter?,
        position: RecapPosition?,
        source: RecapTextSource = RecapTextSource.PAGE,
    )

    /**
     * Ends capture: CAPTURING becomes PENDING or SKIPPED_INELIGIBLE, and the
     * job runner is woken. [activeReadingMs] is wall clock until Stage 2
     * measures foreground time.
     */
    fun onSessionEnded(
        sessionId: String,
        endPosition: RecapPosition?,
        lastSentence: String?,
        activeReadingMs: Long,
    )
}
