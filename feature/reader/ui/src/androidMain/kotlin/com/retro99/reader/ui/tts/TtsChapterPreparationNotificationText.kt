package com.retro99.reader.ui.tts

import com.retro99.reader.ui.reader.PreparedTimeLeftLabel
import com.retro99.reader.ui.reader.preparedTimeLeftLabel

/** Which of the notification's progress lines to show; the service maps each to its string. */
internal enum class TtsChapterPreparationNotificationLine {
    COUNT,

    /** The counts plus a promise that a time is coming, while none has been worked out yet. */
    WAITING,
    TIME_LEFT,
    TIME_LEFT_SHORT,
    TIME_LEFT_HOURS,
    CANCELLING,
}

/** The notification's progress line and its arguments, decided here so it can be tested. */
internal data class TtsChapterPreparationNotificationText(
    val line: TtsChapterPreparationNotificationLine,
    val args: List<Any>,
)

/** The same counts and the same time left as the row, and never a book or a chapter. */
internal fun chapterPreparationNotificationText(
    running: TtsChapterPreparationState.Running?,
): TtsChapterPreparationNotificationText {
    if (running?.isCancelling == true) {
        return TtsChapterPreparationNotificationText(TtsChapterPreparationNotificationLine.CANCELLING, emptyList())
    }
    val counts = listOf<Any>(running?.done ?: 0, running?.total ?: 0)
    return when (val left = preparedTimeLeftLabel(running?.remainingMs)) {
        // The same promise the row makes, in the same place. With nothing running there is
        // no run to promise anything about, so the bare counts stay.
        null -> if (running == null) {
            TtsChapterPreparationNotificationText(TtsChapterPreparationNotificationLine.COUNT, counts)
        } else {
            TtsChapterPreparationNotificationText(TtsChapterPreparationNotificationLine.WAITING, counts)
        }
        PreparedTimeLeftLabel.UnderMinute ->
            TtsChapterPreparationNotificationText(TtsChapterPreparationNotificationLine.TIME_LEFT_SHORT, counts)
        is PreparedTimeLeftLabel.Minutes ->
            TtsChapterPreparationNotificationText(TtsChapterPreparationNotificationLine.TIME_LEFT, counts + left.minutes)
        is PreparedTimeLeftLabel.Hours -> TtsChapterPreparationNotificationText(
            TtsChapterPreparationNotificationLine.TIME_LEFT_HOURS,
            counts + left.hours + left.minutes,
        )
    }
}
