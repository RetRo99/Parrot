package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.AudioPlaybackState
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Controller interface for audio playback in ReadAloud books.
 *
 * This controller is injected into the ViewModel and provides:
 * - Playback control methods (play, pause, resume, seek, etc.)
 * - Unified playback state via [audioPlaybackState]
 * - Platform-agnostic API for audio playback
 *
 * Platform implementations:
 * - Android: Uses ExoPlayer with SMIL parsing
 * - iOS: Bridges to Swift MediaOverlayPlayer with AVPlayer
 */
interface AudioController : NarrationController {

    // State observation flows

    /**
     * Unified playback state for ReadAloud books.
     * Includes position, playing state, playback state, and readiness.
     */
    val audioPlaybackState: Flow<AudioPlaybackState>

    override val isPlaying: Flow<Boolean>
        get() = audioPlaybackState
            .map { playbackState -> playbackState.isPlaying }
            .distinctUntilChanged()

    override val isLoading: Flow<Boolean>
        get() = audioPlaybackState
            .map { playbackState -> playbackState.playbackState == PlaybackState.BUFFERING }
            .distinctUntilChanged()

    /**
     * Flow of playback state changes for ReadAloud books.
     * This includes states like PLAYING, PAUSED, BUFFERING, STOPPED, and ERROR.
     * Use this to show error feedback to the user when SMIL parsing fails or other errors occur.
     * Returns an empty flow if the book doesn't support media overlays.
     */
    val playbackState: Flow<PlaybackState>


    /**
     * Flow that emits true when the notification permission was denied
     * and a dialog should be shown to the user.
     *
     * **Platform behavior:**
     * - **Android**: Emits true when POST_NOTIFICATIONS permission is denied (Android 13+).
     *   The dialog prompts the user to open app settings.
     * - **iOS**: Always emits false (no-op). iOS doesn't require notification permission
     *   for audio playback, so this flow is included only for API parity.
     *
     * Returns an empty flow if the book doesn't support media overlays.
     */
    val showPermissionDeniedDialog: Flow<Boolean>

    /**
     * Flow that emits true when the permission denial dialog should show a rationale
     * (user can be asked again) vs directing to settings (permanently denied).
     *
     * **Platform behavior:**
     * - **Android**: Emits true when user denied but can be asked again.
     * - **iOS**: Always emits false (no-op).
     */
    val showPermissionRationale: Flow<Boolean>

    // Media playback methods for ReadAloud books

    /**
     * Toggles audio playback (play/pause).
     *
     * The controller internally tracks whether playback has been started:
     * - If not started yet: starts playback from the initial position or visible sentence
     * - If started and playing: pauses
     * - If started and paused: resumes from current position
     *
     * Uses the visible sentence ID set via [setVisibleSentenceId] for precise positioning.
     */
    override fun togglePlayback()

    /**
     * Resets the playback state so the next play starts fresh.
     * Should be called when the user navigates to a new position while paused.
     */
    fun resetPlaybackState()

    /**
     * Sets the initial audio position for playback.
     * Used when the user resolves a position conflict by choosing the remote position,
     * so the audio starts from the remote position's timestamp instead of the local one.
     *
     * This also resets the playback state so the next play starts from this position.
     *
     * @param positionMs The audio position in milliseconds, or null to clear the initial position
     */
    fun setInitialAudioPosition(positionMs: Long?)

    /**
     * Pauses audio playback.
     */
    fun pauseAudio()

    /**
     * Seeks to a specific audio position.
     *
     * @param timestampMs The position in milliseconds
     */
    fun seekToAudioPosition(timestampMs: Long)

    /**
     * Sets the playback speed.
     *
     * @param speed The playback speed (e.g., 0.5, 1.0, 1.5, 2.0)
     */
    override fun setPlaybackSpeed(speed: Float)

    /**
     * Skips forward by a fixed increment (10 seconds).
     * Uses the player's authoritative position rather than ViewModel state.
     */
    override fun skipForward()

    /**
     * Skips backward by a fixed increment (10 seconds).
     * Uses the player's authoritative position rather than ViewModel state.
     */
    override fun skipBackward()

    /**
     * Starts audio playback from a specific text fragment (sentence).
     * Used when user double-taps on a sentence to start playback from that point.
     *
     * @param fragmentId The fragment ID of the sentence (e.g., "chapter44.xhtml-sentence50")
     * @param chapterHref Optional chapter href. If null, uses the current chapter.
     */
    fun playFromFragment(fragmentId: String, chapterHref: String? = null)

    override fun playFromSentence(fragmentId: String, chapterHref: String?) {
        playFromFragment(fragmentId, chapterHref)
    }

    override fun playFromChapterStart(chapterHref: String) {
        playFromFragment(fragmentId = "", chapterHref = chapterHref)
    }

    /**
     * Updates the audio position to match a given text fragment ID without starting playback.
     *
     * This is used when the user navigates while audio is not playing, so the seek bar
     * reflects where playback would start. The position is emitted through [audioPlaybackState].
     *
     * @param fragmentId The fragment ID of the sentence (e.g., "chapter44.xhtml-sentence50")
     */
    fun updatePositionForFragment(fragmentId: String)

    /**
     * Dismisses the permission denied dialog.
     */
    fun dismissPermissionDeniedDialog()

    /**
     * Updates the now-playing book info with the title and cover URL.
     * Called when this metadata becomes available (e.g., after reader initialization).
     *
     * @param bookUuid The book's unique identifier
     * @param bookTitle The book's title for display
     * @param coverUrl The cover image URL (from server, not EPUB internal)
     */
    fun setNowPlayingInfo(bookUuid: String, bookTitle: String, coverUrl: String?)

    fun onBookLocationChanged(locator: LocatorState, visibleSentenceId: String?)

    /**
     * Flow of current audio locator with timing information.
     * Emits whenever the audio playback moves to a new sentence.
     * Includes sentence duration for pre-emptive page turn calculations.
     */
    val currentAudioLocator: StateFlow<AudioLocatorState?>

    /**
     * Flow that emits when the current chapter's audio playback completes.
     * Used to trigger auto-play of the next chapter.
     *
     * Emits the href of the completed chapter when audio reaches the end.
     * Also emits when a chapter has no audio content, allowing the coordinator
     * to skip to the next chapter.
     */
    val chapterAudioCompleted: Flow<String>

    override val chapterCompleted: Flow<String>
        get() = chapterAudioCompleted
}
