package com.retro99.reader.ui.navigator

import kotlinx.coroutines.flow.Flow

/**
 * Common playback contract for synchronized spoken narration.
 *
 * ReadAloud and TTS use different content sources, but expose the same reader-facing controls.
 */
interface NarrationController : AutoCloseable {

    val isPlaying: Flow<Boolean>

    val isLoading: Flow<Boolean>

    /** True only while a user-initiated playback request is being started. */
    val isPlaybackStartPending: Flow<Boolean>
        get() = isLoading

    /** Emits the href of a chapter after its narration finishes naturally. */
    val chapterCompleted: Flow<String>

    fun togglePlayback()

    fun setPlaybackSpeed(speed: Float)

    fun skipForward()

    fun skipBackward()

    fun playFromSentence(fragmentId: String, chapterHref: String? = null)

    fun playFromChapterStart(chapterHref: String)
}
