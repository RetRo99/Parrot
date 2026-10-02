package com.retro99.statistics.domain

import com.retro99.books.domain.model.BookType
import com.retro99.statistics.domain.model.ReadingSessionDomainModel
import kotlin.time.Clock

/** Owned by playback, not the screen, so notification and background listening count too. */
class AudiobookSessionTracker(
    private val saveSession: (ReadingSessionDomainModel) -> Unit,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val createTimer: () -> ActiveSessionTimer = { ActiveSessionTimer() },
) {
    private var bookUuid: String? = null
    private var bookTitle = ""
    private var timer: ActiveSessionTimer? = null
    private var startTime = 0L

    fun setBook(uuid: String?, title: String) {
        if (uuid != bookUuid) finish()
        bookUuid = uuid
        bookTitle = title
    }

    fun setPlaying(playing: Boolean) {
        if (!playing) {
            finish()
        } else if (bookUuid != null && timer == null) {
            startTime = nowMillis()
            timer = createTimer().also { it.setActive(true) }
        }
    }

    fun finish() {
        val duration = timer?.finish() ?: return
        timer = null
        val uuid = bookUuid ?: return
        if (duration <= 0L) return
        saveSession(
            ReadingSessionDomainModel(
                id = 0,
                bookUuid = uuid,
                bookTitle = bookTitle,
                bookType = BookType.AUDIOBOOK,
                startTime = startTime,
                endTime = nowMillis(),
                durationMs = duration,
                pagesRead = null,
                startProgression = null,
                endProgression = null,
                readingSpeedWpm = 0,
            ),
        )
    }
}
