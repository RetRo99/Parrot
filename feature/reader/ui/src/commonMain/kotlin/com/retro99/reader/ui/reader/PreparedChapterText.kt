package com.retro99.reader.ui.reader

import kotlinx.coroutines.delay

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
@Suppress("ReturnCount", "LongParameterList")
internal suspend fun readPreparedChapterText(
    chapterHref: String,
    /** Read-aloud's own sentences when they belong to this chapter; they are only counted. */
    loaded: () -> List<String>?,
    currentHref: () -> String?,
    readPage: suspend () -> List<String>,
    attempts: Int = 3,
    retryDelayMs: Long = 400,
): PreparedChapterText? {
    loaded()?.takeIf { texts -> texts.isNotEmpty() }?.let { texts -> return counted(chapterHref, texts) }
    repeat(attempts) { attempt ->
        if (currentHref() != chapterHref) return null
        val texts = readPage()
        // The page answers for whatever chapter it holds now, not the one that was asked for.
        if (currentHref() != chapterHref) return null
        if (texts.isNotEmpty()) return counted(chapterHref, texts)
        // A page still laying out has no sentences yet; an empty chapter never will.
        if (attempt < attempts - 1) delay(retryDelayMs)
    }
    return PreparedChapterText(chapterHref, sentences = 0, characters = 0)
}

private fun counted(chapterHref: String, texts: List<String>) = PreparedChapterText(
    chapterHref = chapterHref,
    sentences = texts.size,
    characters = texts.sumOf { text -> text.length },
)

/**
 * The estimate the not-prepared row shows for the chapter on screen. Null leaves the plain
 * line: the count is still being read, it belongs to a chapter the reader has left, or the
 * chapter has nothing to read. Never an estimate of zero.
 */
internal fun preparedChapterRowEstimate(
    chapterHref: String?,
    text: PreparedChapterText?,
    voiceKind: PreparedVoiceKind,
    measured: PreparedChapterMeasured? = null,
    rate: Float = 1f,
): PreparedChapterEstimate? {
    if (chapterHref == null || text == null || text.chapterHref != chapterHref) return null
    return preparedChapterEstimate(text.sentences, voiceKind, measured, text.characters, rate)?.copy(
        audioMinutes = preparedChapterAudioMinutes(text.sentences, text.characters, voiceKind, measured, rate),
    )
}
