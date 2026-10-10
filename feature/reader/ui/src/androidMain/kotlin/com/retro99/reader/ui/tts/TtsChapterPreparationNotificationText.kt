package com.retro99.reader.ui.tts

/** Which of the notification's progress lines to show; the service maps each to its string. */
internal enum class TtsChapterPreparationNotificationLine { COUNT, TIME_LEFT, TIME_LEFT_SHORT, TIME_LEFT_HOURS }

/** The notification's progress line and its arguments, decided here so it can be tested. */
internal data class TtsChapterPreparationNotificationText(
    val line: TtsChapterPreparationNotificationLine,
    val args: List<Any>,
)

/** The same counts and the same time left as the row, and never a book or a chapter. */
internal fun chapterPreparationNotificationText(
    running: TtsChapterPreparationState.Running?,
): TtsChapterPreparationNotificationText = TtsChapterPreparationNotificationText(
    TtsChapterPreparationNotificationLine.COUNT,
    listOf(running?.done ?: 0, running?.total ?: 0),
)
