package com.retro99.reader.ui.tts

/**
 * What prepared audio exists for one chapter, read for the voice, speed and pitch selected
 * right now. The store itself is Android-only; this is the shape the screen sees.
 */
sealed interface TtsPreparedChapterAudio {

    data object NotPrepared : TtsPreparedChapterAudio

    /** [done] of [total] sentences are on disk for the current settings. */
    data class Partial(val done: Int, val total: Int) : TtsPreparedChapterAudio

    data class Ready(val bytes: Long) : TtsPreparedChapterAudio

    /** Prepared, wholly or partly, for a voice, speed or pitch that is no longer selected. */
    data class OtherSettings(
        val voiceId: String?,
        val rate: Float,
        val pitch: Float,
        val done: Int,
        val total: Int,
        val complete: Boolean,
    ) : TtsPreparedChapterAudio
}
