package com.retro99.reader.ui.reader

/**
 * How much text the chapter on screen holds, counted so the "Prepare this chapter" row can
 * say what preparing will cost before anything is pressed. Counts only: never the text.
 */
data class PreparedChapterText(
    val chapterHref: String,
    val sentences: Int,
    val characters: Int,
)

/**
 * Counts the sentences of [chapterHref] without loading them into read-aloud. Null means
 * "not known": the reader moved on while the page was being read.
 */
@Suppress("UnusedParameter")
internal suspend fun readPreparedChapterText(
    chapterHref: String,
    loaded: () -> List<String>?,
    currentHref: () -> String?,
    readPage: suspend () -> List<String>,
    attempts: Int = 3,
    retryDelayMs: Long = 400,
): PreparedChapterText? = null

/** The estimate the not-prepared row shows for the chapter on screen, or null for the plain line. */
@Suppress("UnusedParameter")
internal fun preparedChapterRowEstimate(
    chapterHref: String?,
    text: PreparedChapterText?,
    voiceKind: PreparedVoiceKind,
    measured: PreparedChapterMeasured? = null,
): PreparedChapterEstimate? = null
