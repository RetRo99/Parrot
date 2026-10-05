package com.retro99.reader.ui.navigator

import android.util.Log
import com.retro99.reader.ui.di.InitialAudioPosition
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.media.MediaOverlayPlayer
import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.AudioPlaybackState
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PlaybackState
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.publication.EpubPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.readium.r2.shared.util.Url

private const val TAG = "čič123"

@Scope(ReaderScope::class)
@Scoped(binds = [AudioController::class])
class AndroidAudioController(
    private val publication: EpubPublication,
    private val player: MediaOverlayPlayer,
    private val mediaPlaybackController: MediaPlaybackController,
    private val initialAudioPosition: InitialAudioPosition,
) : AudioController {

    private var currentBookLocation: LocatorState? = null

    private var controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Tracks whether playback has been started at least once.
     * Used to determine whether to start fresh (with positioning) or resume.
     */
    private var hasStartedPlayback = false

    /**
     * Tracks whether we're reconnecting to existing playback.
     * When true, we skip prepareChapterDuration calls since the player already has the content.
     * Reset to false after the first chapter change event.
     */
    private var isReconnecting = false

    /**
     * Tracks whether the audio system (SMIL files) has been initialized.
     * This is separate from ExoPlayer's STATE_READY - initialization completes
     * when SMIL files are loaded and indexed, not when media is buffered.
     *
     * This is used to hide the loading overlay once the reader is ready to use,
     * even before the user presses play.
     */
    private val _isAudioInitialized = MutableStateFlow(false)

    /**
     * Current audio locator filtered to only emit when the currently playing book
     * matches this reader's book. This prevents highlighting from being applied
     * to the wrong book when switching books from Android Auto.
     */
    override val currentAudioLocator: StateFlow<AudioLocatorState?> =
        combine(
            mediaPlaybackController.currentLocator,
            mediaPlaybackController.nowPlayingBook,
        ) { locator, nowPlaying ->
            // Only emit locator if it's for this book (or no book info available yet)
            if (nowPlaying == null || nowPlaying.bookUuid == publication.bookUuid) {
                locator
            } else {
                // Different book is playing - don't apply highlights to this reader
                null
            }
        }.stateIn(
            scope = controllerScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Audio playback state filtered to only show playing state when this book is playing.
     * When a different book is playing (e.g., from Android Auto), this reader shows as stopped.
     */
    override val audioPlaybackState: Flow<AudioPlaybackState>
        get() {
            // Combine base playback state with book check
            val baseStateFlow = combine(
                mediaPlaybackController.normalizedPosition,
                mediaPlaybackController.totalDuration,
                mediaPlaybackController.isPlaying,
                mediaPlaybackController.playbackState,
                _isAudioInitialized,
            ) { positionMs, durationMs, isPlaying, playbackState, isAudioInitialized ->
                AudioPlaybackState(
                    currentPositionMs = positionMs,
                    totalDurationMs = durationMs,
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    isPlayerReady = isAudioInitialized,
                )
            }

            // Filter based on whether this book is currently playing
            return combine(
                baseStateFlow,
                mediaPlaybackController.nowPlayingBook,
            ) { state, nowPlaying ->
                val isThisBookPlaying = nowPlaying == null || nowPlaying.bookUuid == publication.bookUuid
                if (isThisBookPlaying) {
                    state
                } else {
                    // Different book is playing - show this reader as stopped
                    AudioPlaybackState(
                        currentPositionMs = 0L,
                        totalDurationMs = null,
                        isPlaying = false,
                        playbackState = PlaybackState.STOPPED,
                        isPlayerReady = state.isPlayerReady,
                    )
                }
            }
        }

    /**
     * Playback state filtered by book UUID.
     * Shows STOPPED when a different book is playing.
     */
    override val playbackState: Flow<PlaybackState>
        get() = combine(
            mediaPlaybackController.playbackState,
            mediaPlaybackController.nowPlayingBook,
        ) { state, nowPlaying ->
            val isThisBook = nowPlaying == null || nowPlaying.bookUuid == publication.bookUuid
            if (isThisBook) state else PlaybackState.STOPPED
        }

    override val showPermissionDeniedDialog: Flow<Boolean>
        get() = player.showPermissionDeniedDialog

    override val showPermissionRationale: Flow<Boolean>
        get() = player.showPermissionRationale

    override val chapterAudioCompleted: Flow<String>
        get() = mediaPlaybackController.chapterAudioCompleted.map {
            // Return the current chapter href when audio completes
            currentBookLocation?.href ?: ""
        }.filter { it.isNotEmpty() }

    /**
     * Initial chapter href from saved reading progress.
     * Used for initializing media overlays at the correct chapter.
     */
    private val initialChapterHref: String? = initialAudioPosition.href

    /**
     * Initial audio position from saved reading progress.
     * Used to restore the seek bar to the saved position.
     */
    private val initialPositionMs: Long? = initialAudioPosition.positionMs

    init {
        controllerScope.launch {
            initializeMediaOverlays()
        }
    }

    private suspend fun initializeMediaOverlays() {
        val chapterHref = initialChapterHref
            ?: publication.publication.readingOrder.firstOrNull()?.href?.toString()
        val chapterUrl = chapterHref?.let { Url(it) }

        // initialize() returns true if this is a reconnection to existing playback
        isReconnecting = player.initialize(chapterHref)

        if (!isReconnecting && chapterUrl != null) {
            // Only prepare chapter duration for fresh initialization
            // During reconnection, the player already has the content loaded
            // Use saved position from reading progress (initialPositionMs), not currentPosition (which is 0 at startup)
            player.prepareChapterDuration(chapterUrl, initialPositionMs)

            // Also set the initial position directly on the controller so the seek bar shows correctly
            // This is needed because the service may not be started yet at this point
            initialPositionMs?.let { mediaPlaybackController.setInitialPosition(it) }
        }

        // Mark audio as initialized - this hides the loading overlay
        // Note: This happens after SMIL loading completes, not when ExoPlayer is STATE_READY
        _isAudioInitialized.value = true
    }

    override fun togglePlayback() {
        val isCurrentlyPlaying = mediaPlaybackController.isPlaying.value
        if (isCurrentlyPlaying) {
            player.pause()
        } else {
            if (!hasStartedPlayback) {
                startPlaybackFromCurrentPosition()
            } else {
                player.resume()
            }
        }
    }

    override fun pause() {
        player.pause()
    }

    override fun resume() {
        if (hasStartedPlayback) {
            player.resume()
        }
    }

    override fun resetPlaybackState() {
        // Only reset if not currently playing - when playing, the audio drives the state
        if (mediaPlaybackController.isPlaying.value) return
        hasStartedPlayback = false
    }

    override fun setInitialAudioPosition(positionMs: Long?) {
        hasStartedPlayback = false
        // Update the controller so the seek bar reflects the new position
        if (positionMs != null) {
            mediaPlaybackController.setInitialPosition(positionMs)
        }
    }

    override fun pauseAudio() {
        player.pause()
    }

    /**
     * Starts playback from the current position shown on the seek bar.
     * Uses saved position (initialPositionMs) if controller position is 0,
     * which indicates no user navigation has occurred since opening the book.
     */
    private fun startPlaybackFromCurrentPosition() {
        val controllerPosition = mediaPlaybackController.currentPosition.value
        Log.d(TAG, "startPlaybackFromCurrentPosition: controllerPosition=$controllerPosition, initialPositionMs=$initialPositionMs")
        // If controller position is 0 and we have a saved position, use the saved position
        // This handles the case where the book was opened but the user hasn't navigated
        val positionToUse = if (controllerPosition == 0L && initialPositionMs != null && initialPositionMs > 0) {
            Log.d(TAG, "startPlaybackFromCurrentPosition: using initialPositionMs=$initialPositionMs")
            initialPositionMs
        } else {
            Log.d(TAG, "startPlaybackFromCurrentPosition: using controllerPosition=$controllerPosition")
            controllerPosition
        }
        executePlayCommand(positionToUse)
        hasStartedPlayback = true
    }

    override fun seekToAudioPosition(timestampMs: Long) {
        // Convert normalized position (from UI) to raw ExoPlayer position
        val rawPosition = mediaPlaybackController.normalizedToRawPosition(timestampMs)
        player.seekTo(rawPosition)
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun skipForward() {
        player.seekForward()
    }

    override fun skipBackward() {
        player.seekBackward()
    }

    override fun playFromFragment(fragmentId: String, chapterHref: String?) {
        val href = chapterHref?.let { Url(it) } ?: currentBookLocation?.href?.let { Url(it) }
        player.play(
            chapterHref = href,
            initialFragmentId = fragmentId,
            initialProgression = null,
            initialPositionMs = null,
        )
    }

    override fun updatePositionForFragment(fragmentId: String) {
        // Only update position when not playing - when playing, the position
        // is driven by the audio playback itself
        if (mediaPlaybackController.isPlaying.value) return

        // First, find the clip WITHOUT seeking ExoPlayer yet.
        // We need to check if audio file switch is needed before seeking,
        // because seeking to a position beyond the current file's duration
        // would cause issues.
        val matchingClip = mediaPlaybackController.findClipForFragment(fragmentId)
        if (matchingClip == null) {
            return
        }

        val clipAudioHref = matchingClip.audioHref
        val currentAudioHref = player.getCurrentAudioHref()
        val positionMs = (matchingClip.startTime * 1000.0).toLong()

        if (clipAudioHref != currentAudioHref) {
            // Audio file switch needed - update internal position state,
            // but let switchAudioFileIfNeeded handle the actual seek
            mediaPlaybackController.updatePositionForFragment(fragmentId, skipSeek = true)
            player.switchAudioFileIfNeeded(clipAudioHref, positionMs)
        } else {
            // Same audio file - update position and seek normally
            mediaPlaybackController.updatePositionForFragment(fragmentId, skipSeek = false)
        }
    }

    override fun dismissPermissionDeniedDialog() {
        player.dismissPermissionDeniedDialog()
    }

    override fun onBookLocationChanged(locator: LocatorState, visibleSentenceId: String?) {
        val isChapterChange = currentBookLocation?.href != locator.href
        currentBookLocation = locator
        if (isChapterChange) {
            onChapterChanged(locator, visibleSentenceId)
        } else if (visibleSentenceId != null) {
            updatePositionForFragment(visibleSentenceId)
        }
        resetPlaybackState()
    }

    private fun onChapterChanged(locator: LocatorState, visibleSentenceId: String?) {
        // Skip chapter preparation during reconnection - the player already has the content
        if (isReconnecting) {
            isReconnecting = false // Clear flag after first chapter event
            return
        }

        val chapterUrl = Url(locator.href) ?: return
        // Get the visible fragment ID (sentence) so we can prepare the correct audio file
        // for chapters that span multiple audio files
        val fragmentId = locator.fragments?.firstOrNull()
        controllerScope.launch {
            player.prepareChapterDuration(chapterUrl, targetFragmentId = fragmentId)
            visibleSentenceId?.let { updatePositionForFragment(it) }
        }
    }

    override fun close() {
        player.release()
        controllerScope.cancel()
    }

    override fun setNowPlayingInfo(bookUuid: String, bookTitle: String, coverUrl: String?) {
        mediaPlaybackController.updateNowPlayingBookInfo(bookUuid, bookTitle, coverUrl)
    }

    private fun executePlayCommand(initialPositionMs: Long?) {
        val currentLocator = currentBookLocation
        val currentChapterHref = currentLocator?.href?.let { Url(it) }
        val fragmentId = currentLocator?.fragments?.firstOrNull()
        val progression = currentLocator?.progression
        if (currentChapterHref == null) {
            player.play(initialPositionMs = initialPositionMs)
            return
        }
        player.play(currentChapterHref, fragmentId, progression, initialPositionMs)
    }
}