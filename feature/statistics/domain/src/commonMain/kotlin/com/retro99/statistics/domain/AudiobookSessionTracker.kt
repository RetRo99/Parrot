package com.retro99.statistics.domain

import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import kotlin.time.Clock

/**
 * Owned by playback, not the screen, so notification and background listening count too.
 *
 * Pauses shorter than [mergePauseMs] (a dictionary "speak word" interruption, a quick stop)
 * continue the same session instead of splitting Statistics in two; longer pauses end it.
 * The session is written at [finish] (pause beyond the grace, book change or service
 * teardown), so the common stop path saves exactly one session per listening stretch.
 */
class AudiobookSessionTracker(
    private val saveSession: (ReadingSessionDomainModel) -> Unit,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val createTimer: () -> ActiveSessionTimer = { ActiveSessionTimer() },
    private val mergePauseMs: Long = DEFAULT_MERGE_PAUSE_MS,
) {
    private var bookUuid: String? = null
    private var bookTitle = ""
    private var timer: ActiveSessionTimer? = null
    private var startTime = 0L
    private var pausedAt: Long? = null

    fun setBook(uuid: String?, title: String) {
        if (uuid != bookUuid) finish()
        bookUuid = uuid
        bookTitle = title
    }

    fun setPlaying(playing: Boolean) {
        val activeTimer = timer
        if (playing) {
            if (activeTimer != null && pausedAt == null) return
            val pauseStartedAt = pausedAt
            if (activeTimer != null && pauseStartedAt != null && nowMillis() - pauseStartedAt <= mergePauseMs) {
                // A short pause resumes the same session.
                pausedAt = null
                activeTimer.setActive(true)
                return
            }
            finish()
            if (bookUuid != null) {
                startTime = nowMillis()
                pausedAt = null
                timer = createTimer().also { it.setActive(true) }
            }
        } else {
            if (activeTimer == null || pausedAt != null) return
            pausedAt = nowMillis()
            activeTimer.setActive(false)
        }
    }

    fun finish() {
        val duration = timer?.finish() ?: return
        timer = null
        val uuid = bookUuid ?: return
        if (duration <= 0L) {
            pausedAt = null
            return
        }
        saveSession(
            ReadingSessionDomainModel(
                id = 0,
                bookUuid = uuid,
                bookTitle = bookTitle,
                bookType = BookType.AUDIOBOOK,
                startTime = startTime,
                endTime = pausedAt ?: nowMillis(),
                durationMs = duration,
                pagesRead = null,
                startProgression = null,
                endProgression = null,
                readingSpeedWpm = 0,
            ),
        )
        pausedAt = null
    }

    companion object {
        /** Comfortably longer than a spoken word (a few seconds), shorter than a real stop. */
        const val DEFAULT_MERGE_PAUSE_MS = 10_000L
    }
}
