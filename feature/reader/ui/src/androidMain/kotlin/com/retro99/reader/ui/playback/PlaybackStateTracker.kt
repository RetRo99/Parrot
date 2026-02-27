package com.retro99.reader.ui.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.PlaybackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Authoritative source for playback state, with support for optimistic updates
 * from the playback orchestrator.
 *
 * ## State Management Pattern
 *
 * This class uses an **optimistic update pattern** for state management:
 *
 * 1. **Optimistic writes**: [MediaOverlayPlayer] calls [setPlayingState] and [setPlaybackState]
 *    immediately when starting playback to provide instant UI feedback (e.g., show buffering spinner).
 *
 * 2. **Authoritative writes**: The [Player.Listener] callbacks update state based on actual
 *    ExoPlayer events, which may confirm or override the optimistic state.
 *
 * 3. **Rollback on failure**: If permission/focus/service fails, [MediaOverlayPlayer] resets
 *    state back to STOPPED/false.
 *
 * This means consumers of [isPlaying] and [playbackState] may see transient state sequences like:
 * - `false -> true -> false` (optimistic set, then permission denied)
 * - `STOPPED -> BUFFERING -> STOPPED` (optimistic set, then focus failed)
 * - `false -> true -> true` (optimistic set, then listener confirms - no-op due to StateFlow)
 *
 * Consumers should handle these transitions gracefully. StateFlow's conflation means
 * duplicate values won't emit, but rapid changes will.
 *
 * @param mediaPlaybackController Controller to get the ExoPlayer from the service
 * @param analytics Analytics for logging errors
 * @param audioFocusManager Manager for audio focus (to abandon focus on playback end/error)
 * @param foregroundServiceController Controller for foreground service (to stop on playback end/error)
 * @param locatorTracker Tracker for position updates (to start/stop based on playing state)
 */
@Scope(ReaderScope::class)
@Scoped
class PlaybackStateTracker(
    private val mediaPlaybackController: MediaPlaybackController,
    private val analytics: Analytics,
    private val audioFocusManager: AudioFocusManager,
    private val foregroundServiceController: ForegroundServiceController,
    private val locatorTracker: LocatorTracker,
) {
    /** Gets the ExoPlayer from the service. Throws if service not started. */
    private val player: ExoPlayer
        get() = mediaPlaybackController.player ?: error("Service not started")
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _totalDuration = MutableStateFlow<Long?>(null)
    val totalDuration: StateFlow<Long?> = _totalDuration.asStateFlow()

    private val _isPlayerReady = MutableStateFlow(false)
    val isPlayerReady: StateFlow<Boolean> = _isPlayerReady.asStateFlow()

    private val _chapterAudioCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Flow that emits when the current chapter's audio playback completes naturally.
     * Does not emit if playback was manually stopped or paused.
     */
    val chapterAudioCompleted: Flow<Unit> = _chapterAudioCompleted.asSharedFlow()

    // Pending initial position to seek to after audio is prepared
    private var pendingInitialPositionMs: Long? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                locatorTracker.startPositionUpdates()
            } else {
                locatorTracker.stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(playerState: Int) {
            updatePlaybackState(playerState, player.isPlaying)

            // Update isPlayerReady based on player state
            _isPlayerReady.value = playerState == Player.STATE_READY

            when (playerState) {
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    // Don't abandon audio focus or stop service here - we may be
                    // auto-playing the next chapter. The service will be stopped
                    // when the user explicitly pauses or the reader is closed.
                    // Emit chapter completion event for auto-play next chapter
                    _chapterAudioCompleted.tryEmit(Unit)
                }

                Player.STATE_READY -> {
                    val duration = player.duration
                    // NOTE: Don't let ExoPlayer duration overwrite SMIL-calculated duration.
                    // SMIL clips give us the correct chapter duration; ExoPlayer's duration
                    // may be the full audio file which can contain multiple chapters.
                    // Only set duration if not already set (i.e., no SMIL duration calculated yet)
                    if (duration > 0 && _totalDuration.value == null) {
                        _totalDuration.value = duration
                    }
                    // Handle pending seek position
                    pendingInitialPositionMs?.let { positionMs ->
                        if (positionMs > 0) {
                            player.seekTo(positionMs)
                        }
                    }
                    pendingInitialPositionMs = null

                    // NOTE: Removed force-play hack that called player.play() when
                    // playWhenReady && !isPlaying. This caused loops with audio focus:
                    // 1. Focus lost -> player pauses
                    // 2. STATE_READY fires -> force play() called
                    // 3. play() triggers focus request -> may fail or conflict
                    // ExoPlayer handles playWhenReady correctly; trust it.
                }

                Player.STATE_IDLE, Player.STATE_BUFFERING -> {
                    // No special handling needed
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            analytics.logException(error, "ExoPlayer playback error: ${error.errorCodeName}")
            // Update state to reflect the error so UI can show appropriate feedback
            _playbackState.value = PlaybackState.ERROR
            _isPlaying.value = false
            // Clean up (stop foreground service, abandon focus)
            audioFocusManager.abandonFocus()
            foregroundServiceController.stopService()
        }
    }

    /** Whether the listener has been registered */
    private var listenerRegistered = false

    /**
     * Registers the player listener if not already registered.
     * Called lazily when playback starts and player is available.
     */
    fun ensureListenerRegistered() {
        if (!listenerRegistered) {
            player.addListener(playerListener)
            listenerRegistered = true
        }
    }

    /**
     * Sets the pending initial position to seek to after audio is prepared.
     */
    fun setPendingSeekPosition(positionMs: Long?) {
        pendingInitialPositionMs = positionMs
    }

    /**
     * Sets the playing state immediately.
     * Used when starting playback to ensure state change emissions work correctly.
     */
    fun setPlayingState(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    /**
     * Sets the playback state immediately.
     */
    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
    }

    /**
     * Updates the total duration (used when calculating from SMIL clips).
     */
    fun setTotalDuration(durationMs: Long) {
        _totalDuration.value = durationMs
    }

    /**
     * Sets the player ready state.
     * Called when audio content is loaded and ready (clips parsed, duration set),
     * allowing the UI to show controls even before ExoPlayer is actually prepared.
     */
    fun setPlayerReady(ready: Boolean) {
        _isPlayerReady.value = ready
    }

    /**
     * Emits a chapter completion event.
     * Used when a chapter has no audio content and should be skipped.
     */
    fun emitChapterCompleted() {
        _chapterAudioCompleted.tryEmit(Unit)
    }

    private fun updatePlaybackState(playerState: Int, isPlaying: Boolean) {
        _playbackState.value = when {
            isPlaying -> PlaybackState.PLAYING
            playerState == Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            playerState == Player.STATE_ENDED -> PlaybackState.STOPPED
            playerState == Player.STATE_IDLE -> PlaybackState.STOPPED
            else -> PlaybackState.PAUSED
        }
    }

    /**
     * Removes the player listener. Call when releasing the player.
     */
    fun release() {
        if (listenerRegistered) {
            mediaPlaybackController.player?.removeListener(playerListener)
            listenerRegistered = false
        }
        _playbackState.value = PlaybackState.STOPPED
        _isPlayerReady.value = false
    }
}

