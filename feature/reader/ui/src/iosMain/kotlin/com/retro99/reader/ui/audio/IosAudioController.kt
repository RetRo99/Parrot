package com.retro99.reader.ui.audio

import com.retro99.reader.ui.bridge.AudioLocator
import com.retro99.reader.ui.di.InitialAudioPosition
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.AudioPlaybackState
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PlaybackState
import com.retro99.reader.ui.navigator.AudioController
import com.retro99.reader.ui.playback.IosNowPlayingProvider
import com.retro99.reader.ui.playback.NowPlayingInfo
import com.retro99.reader.ui.publication.EpubPublication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * iOS implementation of [AudioController].
 * Delegates audio playback operations to the bridge from [EpubPublication].
 */
@Scope(ReaderScope::class)
@Scoped(binds = [AudioController::class])
class IosAudioController(
    @Provided private val publication: EpubPublication,
    @Provided private val initialAudioPosition: InitialAudioPosition,
    private val nowPlayingProvider: IosNowPlayingProvider,
) : AudioController {

    private val bridge = publication.bridge

    private var mediaOverlaysInitialized = false

    /**
     * Tracks whether playback has been started at least once.
     * Used to determine whether to start fresh (with positioning) or resume.
     */
    private var hasStartedPlayback = false

    /**
     * Initial audio position from saved reading progress.
     * Used on first playback, then cleared.
     * Initialized from the injected InitialAudioPosition.
     */
    private var initialPositionMs: Long? = initialAudioPosition.positionMs

    /**
     * Currently visible sentence ID, updated by the sync coordinator.
     * Used for precise positioning when starting playback.
     */
    private var currentVisibleSentenceId: String? = null

    // Internal mutable flows for state observation
    private val _audioPlaybackState = MutableStateFlow(
        AudioPlaybackState(
            currentPositionMs = initialAudioPosition.positionMs,
            totalDurationMs = null,
            isPlaying = false,
            playbackState = PlaybackState.STOPPED,
            isPlayerReady = false,
        )
    )
    private val _showPermissionDeniedDialog = MutableStateFlow(false)
    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    private val _currentAudioLocator = MutableStateFlow<AudioLocatorState?>(null)
    private val _chapterAudioCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // AudioController state observation flows
    override val audioPlaybackState: Flow<AudioPlaybackState> = _audioPlaybackState
    override val playbackState: Flow<PlaybackState> = _playbackState
    override val currentAudioLocator: StateFlow<AudioLocatorState?> = _currentAudioLocator

    // iOS doesn't require notification permission for audio playback
    override val showPermissionDeniedDialog: Flow<Boolean> = _showPermissionDeniedDialog

    // iOS doesn't need permission rationale - always false
    override val showPermissionRationale: Flow<Boolean> = flowOf(false)

    override val chapterAudioCompleted: Flow<String> = _chapterAudioCompleted.asSharedFlow()

    init {
        setupCallbacks()
        initializeMediaOverlaysIfNeeded()
    }

    private fun initializeMediaOverlaysIfNeeded() {
        if (mediaOverlaysInitialized) {
            return
        }

        if (!bridge.hasMediaOverlays()) {
            // For non-readaloud books, mark as ready immediately
            _audioPlaybackState.value = _audioPlaybackState.value.copy(
                isPlayerReady = true,
            )
            return
        }

        bridge.initializeMediaOverlays {
            mediaOverlaysInitialized = true
            // The callback will trigger isPlayerReady update via setupCallbacks
        }
    }

    private fun setupCallbacks() {
        bridge.setOnPlaybackStateChangedCallback { state ->
            nowPlayingProvider.updateIsPlaying(state.isPlaying)
            _playbackState.value = if (state.isPlaying) {
                PlaybackState.PLAYING
            } else {
                PlaybackState.PAUSED
            }
            _audioPlaybackState.value = _audioPlaybackState.value.copy(
                currentPositionMs = state.currentPositionMs,
                totalDurationMs = state.durationMs,
                isPlaying = state.isPlaying,
                playbackState = _playbackState.value,
            )
        }

        bridge.setOnMediaPlayerReadyCallback {
            _audioPlaybackState.value = _audioPlaybackState.value.copy(
                isPlayerReady = true,
            )
        }

        bridge.setOnAudioLocatorChangedCallback { locator ->
            _currentAudioLocator.value = locator.toAudioLocatorState()
        }

        bridge.setOnChapterAudioCompletedCallback { chapterHref ->
            _chapterAudioCompleted.tryEmit(chapterHref)
        }
    }

    override fun togglePlayback() {
        val isCurrentlyPlaying = _audioPlaybackState.value.isPlaying
        if (isCurrentlyPlaying) {
            bridge.pauseAudio()
        } else {
            if (!hasStartedPlayback) {
                startPlaybackFromCurrentPosition()
            } else {
                bridge.resumeAudio()
            }
        }
    }

    override fun pause() {
        bridge.pauseAudio()
    }

    override fun resume() {
        if (hasStartedPlayback) {
            bridge.resumeAudio()
        }
    }

    override fun resetPlaybackState() {
        // Only reset if not currently playing - when playing, the audio drives the state
        if (_audioPlaybackState.value.isPlaying) return
        hasStartedPlayback = false
    }

    override fun setInitialAudioPosition(positionMs: Long?) {
        hasStartedPlayback = false
        // Update the playback state flow so the seek bar reflects the new position
        _audioPlaybackState.value = _audioPlaybackState.value.copy(
            currentPositionMs = positionMs,
        )
    }

    override fun pauseAudio() {
        bridge.pauseAudio()
    }

    /**
     * Starts playback from the current position shown on the seek bar.
     * The _audioPlaybackState maintains the position - either from saved state or user navigation.
     */
    private fun startPlaybackFromCurrentPosition() {
        val currentPosition = _audioPlaybackState.value.currentPositionMs
        ensureMediaOverlaysInitialized {
            bridge.playAudio(currentPosition)
        }
        hasStartedPlayback = true
    }

    override fun seekToAudioPosition(timestampMs: Long) {
        bridge.seekToAudioPosition(timestampMs)
    }

    override fun setPlaybackSpeed(speed: Float) {
        bridge.setPlaybackSpeed(speed)
    }

    override fun skipForward() {
        bridge.skipForward()
    }

    override fun skipBackward() {
        bridge.skipBackward()
    }

    override fun playFromFragment(fragmentId: String, chapterHref: String?) {
        ensureMediaOverlaysInitialized {
            bridge.playFromFragment(fragmentId, chapterHref)
        }
    }

    override fun updatePositionForFragment(fragmentId: String) {
        // Only update position when not playing - when playing, the position
        // is driven by the audio playback itself
        if (_audioPlaybackState.value.isPlaying) return
        // Don't overwrite initial position before user has started playback
        if (!hasStartedPlayback && _audioPlaybackState.value.currentPositionMs != null) return
        bridge.updatePositionForFragment(fragmentId)
    }

    override fun dismissPermissionDeniedDialog() {
        _showPermissionDeniedDialog.value = false
    }

    override fun onBookLocationChanged(locator: LocatorState, visibleSentenceId: String?) {
        // No-op on iOS for now; media overlay player handles its own chapter prep.
    }

    override fun setNowPlayingInfo(bookUuid: String, bookTitle: String, coverUrl: String?) {
        nowPlayingProvider.updateNowPlayingInfo(
            NowPlayingInfo(
                serverId = publication.serverId,
                bookUuid = bookUuid,
                bookType = publication.bookType,
                bookTitle = bookTitle,
                coverUrl = coverUrl,
            )
        )
    }

    private fun ensureMediaOverlaysInitialized(onReady: () -> Unit) {
        if (mediaOverlaysInitialized) {
            onReady()
            return
        }

        bridge.initializeMediaOverlays {
            mediaOverlaysInitialized = true
            onReady()
        }
    }

    override fun close() {
    }
}

private fun AudioLocator.toAudioLocatorState(): AudioLocatorState {
    return AudioLocatorState(
        locator = LocatorState(
            href = href,
            type = type,
            title = title,
            progression = progression,
            position = position,
            totalProgression = totalProgression,
            fragments = fragment?.let { listOf(it) },
        ),
        sentenceDurationMs = sentenceDurationMs,
    )
}
