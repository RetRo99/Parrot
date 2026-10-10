package com.retro99.reader.ui.tts

/**
 * What the one chapter-preparation job is doing. One chapter at a time, app-wide: the job
 * outlives the reader screen, so this is not reader-scoped state. iPhone has no preparation
 * and stays [Idle].
 */
sealed interface TtsChapterPreparationState {

    data object Idle : TtsChapterPreparationState

    /** [done] of [total] sentences are prepared for [chapterHref]. */
    data class Running(val chapterHref: String, val done: Int, val total: Int) : TtsChapterPreparationState

    data class Completed(val chapterHref: String) : TtsChapterPreparationState

    data class Failed(
        val chapterHref: String,
        val reason: TtsChapterPreparationFailure,
    ) : TtsChapterPreparationState

    /** Stopped by the user after the sentence in flight; what was prepared is kept. */
    data class Cancelled(val chapterHref: String) : TtsChapterPreparationState
}

enum class TtsChapterPreparationFailure(val analyticsValue: String) {
    /** A sentence could not be synthesized or encoded, even on the one retry. */
    SENTENCE_FAILED("sentence_failed"),
    NOT_ENOUGH_SPACE("not_enough_space"),
    VOICE_UNUSABLE("voice_unusable"),
    NOTIFICATIONS_DENIED("notifications_denied"),
    UNEXPECTED_ERROR("unexpected_error"),
}

/**
 * The answer to one press of "Prepare this chapter". A second request while one runs is
 * refused, never queued: the state flow keeps reporting the chapter already running, which
 * is what the row shows as "preparing another chapter".
 */
enum class TtsChapterPreparationRequest {
    STARTED,
    ALREADY_PREPARING,
    NOT_ENOUGH_SPACE,
    VOICE_UNUSABLE,
    NOTIFICATIONS_DENIED,

    /** Nothing to prepare, or this platform has no preparation at all. */
    UNAVAILABLE,
}

/** Which kind of voice a preparation used; the only voice dimension analytics may carry. */
enum class TtsPreparationVoiceKind(val analyticsValue: String) {
    NEURAL("neural"),
    SYSTEM("system"),
}
