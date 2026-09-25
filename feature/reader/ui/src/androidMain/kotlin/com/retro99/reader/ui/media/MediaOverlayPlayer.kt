package com.retro99.reader.ui.media

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.media.smil.SmilClip
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.model.PlaybackState
import com.retro99.reader.ui.playback.ForegroundServiceController
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.MediaSessionManager
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.reader.ui.playback.PermissionDenialState
import com.retro99.reader.ui.playback.SchedulableClip
import com.retro99.reader.ui.publication.EpubPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "čič123"

/** Conversion factor from seconds to milliseconds */
private const val SECONDS_TO_MS = 1000.0

/** Seek increment in milliseconds (10 seconds) */
private const val SEEK_INCREMENT_MS = 10_000L

/**
 * Player for EPUB Media Overlays (SMIL-based text-audio synchronization).
 *
 * This player:
 * 1. Parses SMIL files from the EPUB to get text-audio sync data
 * 2. Uses ExoPlayer to play the audio files
 * 3. Tracks playback position and emits the current Locator for text highlighting
 *
 * Audio files are read directly from the EPUB container using Readium's Publication API
 * and provided to ExoPlayer via ByteArrayDataSource.
 *
 * @param epubPublication The EpubPublication containing the EPUB (Readium Publication is extracted internally)
 */
@OptIn(UnstableApi::class)
@Scope(ReaderScope::class)
@Scoped
class MediaOverlayPlayer(
    private val epubPublication: EpubPublication,
    private val analytics: Analytics,
    private val smilLoadingManager: SmilLoadingManager,
    private val notificationPermissionHandler: NotificationPermissionHandler,
    private val mediaPlaybackController: MediaPlaybackController,
    private val mediaSessionManager: MediaSessionManager,
    private val foregroundServiceController: ForegroundServiceController,
) {
    /**
     * Gets the ExoPlayer from the service via controller.
     * Returns null if the service is not running.
     */
    private val exoPlayer: ExoPlayer?
        get() = mediaPlaybackController.currentPlayer

    private val publication: Publication = epubPublication.publication

    /**
     * Internal coroutine scope for this player.
     * Uses SupervisorJob so that failure of one child doesn't cancel siblings.
     * Uses Dispatchers.Main for UI-related operations.
     */
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Custom DataSource.Factory that reads audio from the EPUB container
    private val dataSourceFactory = PublicationDataSource.Factory(publication)

    // Event to signal that notification permission was denied
    private val _showPermissionDeniedDialog = MutableStateFlow(false)
    val showPermissionDeniedDialog: StateFlow<Boolean> = _showPermissionDeniedDialog.asStateFlow()

    /**
     * Flow that emits true when the permission denial dialog should show a rationale
     * (user can be asked again) vs directing to settings (permanently denied).
     */
    val showPermissionRationale: StateFlow<Boolean> =
        notificationPermissionHandler.denialState.map { state ->
            state is PermissionDenialState.ShowRationale
        }.stateIn(playerScope, SharingStarted.Eagerly, false)

    // Book metadata for media session
    private var bookTitle: String = "Reading Aloud"

    // Current audio file being played (tracked by href)
    private var currentAudioHref: Url? = null

    // Playlist tracking: maps audio href to track index in ExoPlayer playlist
    private var audioHrefToTrackIndex: Map<Url, Int> = emptyMap()

    // Ordered list of audio hrefs in the current playlist
    private var playlistAudioHrefs: List<Url> = emptyList()

    // Duration per audio file (from SMIL clips): maxEndTime - minStartTime
    private var audioDurations: Map<Url, Long> = emptyMap()

    // Chapter start offset per audio file (minStartTime of clips in that audio)
    // Used to normalize position display so chapter starts at 0:00
    private var audioStartOffsets: Map<Url, Long> = emptyMap()

    // Current chapter href being played (for chapter navigation from notifications)
    private var currentChapterHref: String? = null

    // Mutex to prevent concurrent playInternal() calls from double-taps
    private val playMutex = Mutex()

    /**
     * Listener for media item (track) transitions within a playlist.
     * When ExoPlayer automatically transitions from one audio file to the next,
     * we need to update the duration and start offset for the new track.
     */
    private val trackTransitionListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = exoPlayer ?: return
            val newTrackIndex = player.currentMediaItemIndex
            val newAudioHref = playlistAudioHrefs.getOrNull(newTrackIndex)

            // Only handle automatic transitions (not seeks or playlist changes we initiate)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (newAudioHref != null && newAudioHref != currentAudioHref) {
                    currentAudioHref = newAudioHref

                    // Update duration for the new audio file
                    val newDuration = audioDurations[newAudioHref]
                    if (newDuration != null && newDuration > 0) {
                        mediaPlaybackController.setTotalDuration(newDuration)
                    }

                    // Update start offset for position normalization
                    val newStartOffset = audioStartOffsets[newAudioHref] ?: 0L
                    mediaPlaybackController.setChapterStartOffset(newStartOffset)

                    // Update filtered clips for the new audio file
                    mediaPlaybackController.setCurrentAudioHref(newAudioHref)
                }
            }
        }
    }

    init {
        Log.d(TAG, "MediaOverlayPlayer init START")

        // Register track transition listener for multi-audio-file chapter support
        exoPlayer?.addListener(trackTransitionListener)

        // Set up callback for when playback exceeds chapter clip range
        // This handles the case where a single audio file contains multiple chapters
        mediaPlaybackController.setOnChapterClipsExceeded {
            exoPlayer?.pause()
            mediaPlaybackController.emitChapterCompleted()
        }

        // Listen for chapter navigation requests from notification/Android Auto
        playerScope.launch {
            mediaPlaybackController.nextChapterRequest.collect {
                skipToNextChapter()
            }
        }
        playerScope.launch {
            mediaPlaybackController.previousChapterRequest.collect {
                skipToPreviousChapter()
            }
        }

        // Note: Audio attributes are now configured by MediaPlaybackService (handleAudioFocus=true)

        // Initialize media session for system integration
        Log.d(TAG, "MediaOverlayPlayer init: calling mediaSessionManager.initialize()")
        mediaSessionManager.initialize()

        // Set book info for deep link navigation from notification
        Log.d(TAG, "MediaOverlayPlayer init: calling mediaSessionManager.setBookInfo()")
        mediaSessionManager.setBookInfo(
            epubPublication.serverId,
            epubPublication.bookUuid,
            epubPublication.bookType,
        )

        // Set initial book title from publication metadata
        bookTitle = publication.metadata.title ?: "Reading Aloud"
        mediaSessionManager.updateMetadata(bookTitle)
        Log.d(TAG, "MediaOverlayPlayer init END")
    }

    /**
     * Initializes the player with lazy SMIL loading.
     *
     * This builds a lightweight index of SMIL files without fully parsing them.
     * Full parsing happens on-demand when a chapter is prepared.
     *
     * If the same book is already playing (e.g., user left reader and returned),
     * this reconnects to the existing playback instead of starting fresh.
     *
     * @param initialChapterHref The initial chapter href to optimize index building for
     * @return true if this was a reconnection to existing playback (caller should skip prepareChapterDuration)
     */
    suspend fun initialize(initialChapterHref: String? = null): Boolean {
        Log.d(TAG, "initialize() START")
        val bookUuid = epubPublication.bookUuid

        // Check if this book is already loaded in the service (even if paused)
        // This handles reconnection when user leaves and re-enters the reader
        val isBookLoaded = mediaPlaybackController.isBookLoaded(bookUuid)
        Log.d(TAG, "initialize(): isBookLoaded=$isBookLoaded for bookUuid=$bookUuid")
        if (isBookLoaded) {
            // Reconnect to existing playback - don't reinitialize anything
            // The player, clips, and index are already set up in the service
            Log.d(TAG, "initialize(): RECONNECTING to existing playback")
            reconnectToExistingPlayback()
            return true
        }

        // New book or no existing playback - initialize fresh
        Log.d(TAG, "initialize(): FRESH initialization")
        smilLoadingManager.initialize(playerScope)

        // Load cover image ASYNC - don't block initialization on cover loading
        // The notification will show without cover initially, then update when ready
        playerScope.launch {
            val coverArtwork = epubPublication.cover()
            mediaSessionManager.updateMetadata(bookTitle, coverArtwork = coverArtwork)
        }

        // Build initial index - must complete before getClipsForChapter to avoid fallback scan
        val chapterHref = initialChapterHref
            ?: publication.readingOrder.firstOrNull()?.href?.toString()
            ?: return false

        val buildIndexStartTime = System.currentTimeMillis()
        smilLoadingManager.buildInitialIndex(chapterHref)
        return false
    }

    /**
     * Reconnects to existing playback when re-entering the reader.
     *
     * Called when the user left the reader while audio was playing and then returned.
     * The service is still playing, so we just need to sync our UI state with it.
     * Service-owned state flows already contain the correct state - we just need to
     * re-register our local listeners.
     */
    private fun reconnectToExistingPlayback() {
        Log.d(TAG, "reconnectToExistingPlayback() START")

        // Re-register our listeners with the existing player
        Log.d(TAG, "reconnectToExistingPlayback(): adding trackTransitionListener")
        exoPlayer?.addListener(trackTransitionListener)

        // Service already owns the state flows and position updates.
        // Just force an immediate position/locator update to sync UI
        Log.d(TAG, "reconnectToExistingPlayback(): calling forceUpdatePosition()")
        mediaPlaybackController.forceUpdatePosition()

        Log.d(TAG, "reconnectToExistingPlayback() END")
    }

    /**
     * Starts or resumes playback for the current chapter.
     *
     * @param chapterHref The href of the current chapter (XHTML file)
     * @param initialFragmentId Optional fragment ID to start from (e.g., "chapter44.xhtml-sentence50")
     * @param initialProgression Optional text progression (0.0 to 1.0) to estimate audio position
     * @param initialPositionMs Optional initial position in milliseconds to seek to before playing
     *                          (used if fragment ID and progression are not provided or not found)
     */
    fun play(
        chapterHref: Url? = null,
        initialFragmentId: String? = null,
        initialProgression: Double? = null,
        initialPositionMs: Long? = null,
    ) {
        playerScope.launch {
            // Use tryLock to prevent concurrent play calls from double-taps
            // If already playing/starting, ignore the second tap
            if (!playMutex.tryLock()) {
                return@launch
            }
            try {
                playInternal(chapterHref, initialFragmentId, initialProgression, initialPositionMs)
            } finally {
                playMutex.unlock()
            }
        }
    }

    private suspend fun playInternal(
        chapterHref: Url?,
        initialFragmentId: String?,
        initialProgression: Double?,
        initialPositionMs: Long?,
    ) {
        Log.d(TAG, "playInternal: START chapterHref=$chapterHref, fragmentId=$initialFragmentId")

        // Find a chapter with audio BEFORE starting the foreground service.
        // This prevents ForegroundServiceDidNotStartInTimeException when the user
        // clicks play on a chapter without audio (e.g., cover page, table of contents).
        val chapterToPlay = if (chapterHref != null) {
            val chapterWithAudio = smilLoadingManager.findChapterWithAudio(
                chapterHref.removeFragment().toString(),
            )
            Log.d(TAG, "playInternal: chapterWithAudio=$chapterWithAudio")
            if (chapterWithAudio == null) {
                // No chapters have audio - nothing to play
                Log.w(TAG, "playInternal: NO chapters with audio found!")
                analytics.logException(
                    IllegalStateException("No chapters with audio found"),
                    "Cannot play: no audio content available starting from $chapterHref",
                )
                return
            }
            Url(chapterWithAudio)
        } else {
            // No chapter specified - will resume current audio
            Log.d(TAG, "playInternal: no chapter specified, will resume")
            null
        }

        // Request notification permission before starting foreground service (Android 13+)
        // NOTE: We intentionally do NOT set optimistic state (isPlaying=true, BUFFERING)
        // before permission is granted. This prevents UI flicker where the play button
        // briefly shows "playing" state before reverting if permission is denied.
        Log.d(TAG, "playInternal: requesting notification permission")
        val permissionGranted = notificationPermissionHandler.ensurePermission()
        if (!permissionGranted) {
            Log.w(TAG, "playInternal: notification permission DENIED")
            _showPermissionDeniedDialog.value = true
            return
        }
        Log.d(TAG, "playInternal: notification permission granted")

        // Note: Audio focus is now handled automatically by ExoPlayer with handleAudioFocus=true

        // Prepare a deferred to wait for service ready BEFORE starting service
        Log.d(TAG, "playInternal: preparing service ready deferred")
        val serviceReadyDeferred = mediaPlaybackController.prepareServiceReady()

        // Start foreground service for background playback
        Log.d(TAG, "playInternal: starting foreground service")
        val serviceStarted = foregroundServiceController.startService()
        if (!serviceStarted) {
            // App was backgrounded during permission dialog or other system restriction
            Log.w(TAG, "playInternal: foreground service start BLOCKED")
            analytics.logException(
                IllegalStateException("Cannot start foreground service from background"),
                "Foreground service start blocked by system",
            )
            return
        }
        Log.d(TAG, "playInternal: foreground service started, awaiting service ready")

        // Wait for the service to be created and player to be available
        val player = mediaPlaybackController.awaitServiceReady(serviceReadyDeferred)
        if (player == null) {
            // Service didn't start in time - this is critical, stop the service
            Log.e(TAG, "playInternal: service did NOT start in time (timeout)")
            foregroundServiceController.stopService()
            analytics.logException(
                IllegalStateException("Service did not start in time"),
                "MediaPlaybackService onCreate timeout",
            )
            return
        }
        Log.d(TAG, "playInternal: service ready, player=$player")
        mediaPlaybackController.prepareForMediaOverlayPlayback()

        // Register this book as currently playing for reconnection support
        // Include book title for NowPlayingProvider display
        mediaPlaybackController.setCurrentPlayingBook(
            serverId = epubPublication.serverId,
            bookUuid = epubPublication.bookUuid,
            bookType = epubPublication.bookType,
            bookTitle = bookTitle,
        )

        if (chapterToPlay != null) {
            Log.d(TAG, "playInternal: calling prepareChapter($chapterToPlay)")
            // prepareChapter will handle seeking and then set playWhenReady
            // Note: If we found a different chapter with audio, we ignore the initial
            // fragment/progression since they were for the original chapter
            val useInitialPosition = chapterToPlay.toString() == chapterHref?.removeFragment()
                ?.toString()
            prepareChapter(
                chapterToPlay,
                if (useInitialPosition) initialFragmentId else null,
                if (useInitialPosition) initialProgression else null,
                if (useInitialPosition) initialPositionMs else null,
            )
            Log.d(TAG, "playInternal: prepareChapter returned")
        } else {
            Log.d(TAG, "playInternal: resuming without chapter change")
            // No chapter change - check if we need to switch audio files for the target fragment
            val targetClip = initialFragmentId?.let { mediaPlaybackController.findClipForFragment(it) }
            val targetAudioHref = targetClip?.audioHref

            // Determine position to seek to
            val positionToSeek = initialPositionMs
                ?: targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() }
                ?: mediaPlaybackController.findPositionForProgression(initialProgression)
            Log.d(TAG, "playInternal: positionToSeek=$positionToSeek, currentAudioHref=$currentAudioHref, targetAudioHref=$targetAudioHref")

            // Check if we need to switch audio files (for multi-audio-file chapters)
            if (targetAudioHref != null && targetAudioHref != currentAudioHref) {
                Log.d(TAG, "playInternal: switching audio file")
                switchAudioFileIfNeeded(targetAudioHref, positionToSeek ?: 0L)
            } else if (positionToSeek != null && positionToSeek > 0) {
                Log.d(TAG, "playInternal: seeking to $positionToSeek")
                exoPlayer?.seekTo(positionToSeek)
            }

            // Set playWhenReady and call play() after seeking
            val exo = exoPlayer
            Log.d(TAG, "playInternal: exoPlayer=$exo, calling play()")
            exo?.let { p ->
                p.playWhenReady = true
                p.play()
                Log.d(TAG, "playInternal: play() called, playWhenReady=${p.playWhenReady}, isPlaying=${p.isPlaying}, state=${p.playbackState}, mediaItemCount=${p.mediaItemCount}")
            }
        }
        Log.d(TAG, "playInternal: END")
    }

    fun pause() {
        // Note: Audio focus is handled automatically by ExoPlayer with handleAudioFocus=true
        exoPlayer?.pause()
    }

    /**
     * Resumes playback from the current position without seeking.
     */
    fun resume() {
        exoPlayer?.play()
    }

    /**
     * Dismisses the permission denied dialog and clears the denial state.
     * This ensures stale denial state doesn't persist across book sessions.
     */
    fun dismissPermissionDeniedDialog() {
        _showPermissionDeniedDialog.value = false
        notificationPermissionHandler.clearDenialState()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        mediaPlaybackController.forceUpdatePosition()
    }

    /**
     * Seeks forward by 10 seconds from the current position.
     * The position is clamped to the duration of the current media.
     */
    fun seekForward() {
        val player = exoPlayer ?: return
        val newPosition = (player.currentPosition + SEEK_INCREMENT_MS)
            .coerceAtMost(player.duration.coerceAtLeast(0L))
        seekTo(newPosition)
    }

    /**
     * Seeks backward by 10 seconds from the current position.
     * The position is clamped to 0.
     */
    fun seekBackward() {
        val player = exoPlayer ?: return
        val newPosition = (player.currentPosition - SEEK_INCREMENT_MS)
            .coerceAtLeast(0L)
        seekTo(newPosition)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    /**
     * Skips to the next chapter with audio.
     * Called from notification/Android Auto chapter navigation buttons.
     */
    private suspend fun skipToNextChapter() {
        val current = currentChapterHref
        if (current == null) {
            Log.d(TAG, "skipToNextChapter: no current chapter")
            return
        }

        val nextChapter = smilLoadingManager.findNextChapterWithAudio(current)
        if (nextChapter == null) {
            Log.d(TAG, "skipToNextChapter: no next chapter with audio")
            return
        }

        Log.d(TAG, "skipToNextChapter: navigating from $current to $nextChapter")
        val success = prepareChapterAsync(Url(nextChapter)!!, null, null, null)
        if (!success) {
            Log.w(TAG, "skipToNextChapter: failed to prepare next chapter")
        }
    }

    /**
     * Skips to the previous chapter with audio.
     * Called from notification/Android Auto chapter navigation buttons.
     */
    private suspend fun skipToPreviousChapter() {
        val current = currentChapterHref
        if (current == null) {
            Log.d(TAG, "skipToPreviousChapter: no current chapter")
            return
        }

        val prevChapter = smilLoadingManager.findPreviousChapterWithAudio(current)
        if (prevChapter == null) {
            Log.d(TAG, "skipToPreviousChapter: no previous chapter with audio")
            return
        }

        Log.d(TAG, "skipToPreviousChapter: navigating from $current to $prevChapter")
        val success = prepareChapterAsync(Url(prevChapter)!!, null, null, null)
        if (!success) {
            Log.w(TAG, "skipToPreviousChapter: failed to prepare previous chapter")
        }
    }

    /**
     * Prepares the duration for a specific chapter without starting playback.
     * This allows the UI to show the chapter duration before the user presses play.
     *
     * Uses lazy loading to parse SMIL files on-demand.
     *
     * @param chapterHref The href of the chapter to get duration for
     * @param initialPositionMs Optional initial position in milliseconds to seek to
     * @param targetFragmentId Optional fragment ID of the visible text. If provided and the chapter
     *                         spans multiple audio files, the audio file containing this fragment
     *                         will be prepared instead of the first audio file by startTime.
     */
    suspend fun prepareChapterDuration(
        chapterHref: Url,
        initialPositionMs: Long? = null,
        targetFragmentId: String? = null,
    ) {
        val normalizedHref = chapterHref.removeFragment().toString()

        // Load clips for this chapter using lazy loading
        val smilClips = smilLoadingManager.getClipsForChapter(normalizedHref)

        val allChapterClips = convertSmilClipsToMediaOverlayClips(smilClips)

        if (allChapterClips.isEmpty()) {
            val nextChapterWithAudio = smilLoadingManager.findNextChapterWithAudio(normalizedHref)
            if (nextChapterWithAudio != null) {
                // Recursively prepare the next chapter
                prepareChapterDuration(Url(nextChapterWithAudio)!!, initialPositionMs, targetFragmentId)
            }
            return
        }

        // Store ALL clips in the controller/service for fragment lookup.
        // This is important because chapters may span multiple audio files, and we need
        // to be able to find any sentence regardless of which audio file it's in.
        mediaPlaybackController.setChapterClips(allChapterClips)

        // Single-pass calculation: compute audioStartOffsets, audioDurations, audioFiles,
        // and find targetClip all in one iteration for better performance on slow devices.
        data class AudioStats(var minStart: Long = Long.MAX_VALUE, var maxEnd: Long = 0L)
        val audioStatsMap = mutableMapOf<Url, AudioStats>()
        val audioFilesOrdered = mutableListOf<Url>()
        var targetClip: MediaOverlayClip? = null

        for (clip in allChapterClips) {
            val startMs = (clip.startTime * SECONDS_TO_MS).toLong()
            val endMs = (clip.endTime * SECONDS_TO_MS).toLong()

            // Track audio files in order of first appearance
            if (clip.audioHref !in audioStatsMap) {
                audioFilesOrdered.add(clip.audioHref)
            }

            // Update min/max stats for this audio file
            val stats = audioStatsMap.getOrPut(clip.audioHref) { AudioStats() }
            if (startMs < stats.minStart) stats.minStart = startMs
            if (endMs > stats.maxEnd) stats.maxEnd = endMs

            // Find target clip for fragment lookup
            if (targetFragmentId != null && clip.fragmentId == targetFragmentId) {
                targetClip = clip
            }
        }

        // Convert to final maps
        audioStartOffsets = audioStatsMap.mapValues { it.value.minStart }
        audioDurations = audioStatsMap.mapValues { it.value.maxEnd - it.value.minStart }
        val audioFiles = audioFilesOrdered.toList()

        // Determine which audio file to start at
        val targetAudioHref = targetClip?.audioHref ?: audioFiles.firstOrNull()
        val targetTrackIndex = audioFiles.indexOf(targetAudioHref).coerceAtLeast(0)

        // Set duration, start offset, and audio href filter for the initial track
        val initialDuration = audioDurations[targetAudioHref]
        if (initialDuration != null && initialDuration > 0) {
            mediaPlaybackController.setTotalDuration(initialDuration)
        }
        val initialStartOffset = audioStartOffsets[targetAudioHref] ?: 0L
        mediaPlaybackController.setChapterStartOffset(initialStartOffset)
        if (targetAudioHref != null) {
            mediaPlaybackController.setCurrentAudioHref(targetAudioHref)
        }

        // Determine the position to seek to within the target track
        val positionToSeek = if (targetClip != null) {
            (targetClip.startTime * SECONDS_TO_MS).toLong()
        } else {
            initialPositionMs ?: 0L
        }

        // Check if we need to rebuild the playlist
        val needsNewPlaylist = playlistAudioHrefs != audioFiles ||
            currentAudioHref != targetAudioHref ||
            audioHrefToTrackIndex.isEmpty()

        if (needsNewPlaylist) {
            preparePlaylist(audioFiles, targetTrackIndex, positionToSeek, allChapterClips)
        } else if (positionToSeek > 0) {
            // Same playlist and track - just seek to position
            exoPlayer?.seekTo(positionToSeek)
            mediaPlaybackController.forceUpdatePosition()
        }

        // Track the current chapter for navigation from notifications/Android Auto
        currentChapterHref = normalizedHref

        // Prefetch next chapter in background
        smilLoadingManager.prefetchNextChapter(normalizedHref)
    }

    fun release() {
        // Cancel the scope to stop any in-flight coroutines
        playerScope.cancel()

        // Remove track transition listener (player is owned by service, so don't release it)
        exoPlayer?.removeListener(trackTransitionListener)

        // Clear chapter clips exceeded callback
        mediaPlaybackController.setOnChapterClipsExceeded(null)

        // Release media session manager (no longer releases MediaSession - service owns it)
        mediaSessionManager.release()

        // Only stop the foreground service if playback is NOT active.
        // If the user is playing audio and leaves the reader screen, let the service
        // continue running for background playback (Android Auto use case).
        // The service will stop itself when playback ends or user stops from notification.
        val player = exoPlayer
        val isPlaybackActive = player != null && player.isPlaying
        if (!isPlaybackActive) {
            Log.d(TAG, "release: stopping service (playback not active)")
            foregroundServiceController.stopService()
        } else {
            Log.d(TAG, "release: keeping service running (playback active)")
        }

        smilLoadingManager.release()
    }

    /**
     * Prepares playback for a specific chapter.
     * Uses lazy loading to parse SMIL files on-demand.
     *
     * If preparation fails (exception, no clips, no audio), the foreground service
     * is stopped and playback state is reset to prevent zombie notifications.
     *
     * @param chapterHref The href of the chapter to prepare
     * @param initialFragmentId Optional fragment ID to start from
     * @param initialProgression Optional text progression (0.0 to 1.0) to estimate audio position
     * @param initialPositionMs Optional initial position to seek to after audio is ready
     *                          (used if fragment ID and progression are not provided or not found)
     */
    private fun prepareChapter(
        chapterHref: Url,
        initialFragmentId: String? = null,
        initialProgression: Double? = null,
        initialPositionMs: Long? = null,
    ) {
        Log.d(TAG, "prepareChapter: $chapterHref, fragmentId=$initialFragmentId")
        playerScope.launch {
            try {
                val success = prepareChapterAsync(
                    chapterHref,
                    initialFragmentId,
                    initialProgression,
                    initialPositionMs,
                )
                Log.d(TAG, "prepareChapter: prepareChapterAsync returned success=$success")
                if (!success) {
                    // No playable content - emit completion to skip to next chapter
                    Log.w(TAG, "prepareChapter: no playable content, handling failure")
                    handlePreparationFailure(
                        reason = "Chapter preparation returned no playable content",
                        emitCompletion = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "prepareChapter: exception", e)
                analytics.logException(e, "Failed to prepare chapter: $chapterHref")
                handlePreparationFailure("Exception during chapter preparation: ${e.message}")
            }
        }
    }

    /**
     * Handles preparation failure by stopping the foreground service and resetting state.
     * This prevents zombie notifications when SMIL parsing fails, audio is missing, etc.
     *
     * @param reason Description of why preparation failed
     * @param emitCompletion If true, emits a chapter completion event to skip to next chapter
     */
    private fun handlePreparationFailure(reason: String, emitCompletion: Boolean = false) {
        mediaPlaybackController.setPlayingState(false)
        mediaPlaybackController.setPlaybackState(PlaybackState.ERROR)
        foregroundServiceController.stopService()

        if (emitCompletion) {
            // Emit completion event so coordinator can skip to next chapter
            mediaPlaybackController.emitChapterCompleted()
        }
    }

    /**
     * Async implementation of chapter preparation with lazy SMIL loading.
     * Uses playlist approach to load ALL audio files for the chapter.
     *
     * @return true if audio was successfully prepared, false if no playable content found
     */
    private suspend fun prepareChapterAsync(
        chapterHref: Url,
        initialFragmentId: String?,
        initialProgression: Double?,
        initialPositionMs: Long?,
    ): Boolean {
        val totalStartTime = System.currentTimeMillis()
        val normalizedHref = chapterHref.removeFragment().toString()

        // Load clips for this chapter using lazy loading
        val smilClips = smilLoadingManager.getClipsForChapter(normalizedHref)

        // Convert SmilClip to MediaOverlayClip
        val allChapterClips = convertSmilClipsToMediaOverlayClips(smilClips)

        if (allChapterClips.isEmpty()) {
            return false
        }

        // Only update clips if they're different from what's already stored.
        // This prevents resetting position when prepareChapterAsync is called after
        // prepareChapterDuration has already set up the clips and position.
        val existingClips = mediaPlaybackController.getChapterClips()
        val clipsAlreadySet = existingClips.size == allChapterClips.size &&
            existingClips.firstOrNull()?.fragmentId == allChapterClips.firstOrNull()?.fragmentId

        val clipsWereUpdated = !clipsAlreadySet
        if (clipsWereUpdated) {
            mediaPlaybackController.setChapterClips(allChapterClips)
        }

        // Single-pass calculation: compute audioStartOffsets, audioDurations, audioFiles,
        // and find targetClip all in one iteration for better performance on slow devices.
        val singlePassStartTime = System.currentTimeMillis()
        data class AudioStats(var minStart: Long = Long.MAX_VALUE, var maxEnd: Long = 0L)
        val audioStatsMap = mutableMapOf<Url, AudioStats>()
        val audioFilesOrdered = mutableListOf<Url>()
        var targetClip: MediaOverlayClip? = null

        for (clip in allChapterClips) {
            val startMs = (clip.startTime * SECONDS_TO_MS).toLong()
            val endMs = (clip.endTime * SECONDS_TO_MS).toLong()

            // Track audio files in order of first appearance
            if (clip.audioHref !in audioStatsMap) {
                audioFilesOrdered.add(clip.audioHref)
            }

            // Update min/max stats for this audio file
            val stats = audioStatsMap.getOrPut(clip.audioHref) { AudioStats() }
            if (startMs < stats.minStart) stats.minStart = startMs
            if (endMs > stats.maxEnd) stats.maxEnd = endMs

            // Find target clip for fragment lookup
            if (initialFragmentId != null && clip.fragmentId == initialFragmentId) {
                targetClip = clip
            }
        }

        // Convert to final maps
        audioStartOffsets = audioStatsMap.mapValues { it.value.minStart }
        audioDurations = audioStatsMap.mapValues { it.value.maxEnd - it.value.minStart }
        val audioFiles = audioFilesOrdered.toList()

        // Determine which audio file to start at:
        // 1. If initialFragmentId is provided, use the audio file containing that fragment
        // 2. If we already have a currentAudioHref (from previous switchAudioFileIfNeeded), keep using it
        // 3. Otherwise, default to the first audio file
        val targetAudioHref = targetClip?.audioHref
            ?: currentAudioHref?.takeIf { it in audioStatsMap }
            ?: audioFiles.firstOrNull()
            ?: return false
        val targetTrackIndex = audioFiles.indexOf(targetAudioHref).coerceAtLeast(0)

        // Only update duration if we're changing tracks or don't have a duration set
        val currentDuration = mediaPlaybackController.totalDuration.value
        val targetDuration = audioDurations[targetAudioHref]
        if (targetDuration != null && targetDuration > 0 && currentDuration != targetDuration) {
            mediaPlaybackController.setTotalDuration(targetDuration)
        }

        // Update metadata with chapter title for notification display
        val chapterTitle = getChapterTitle(chapterHref)
        mediaSessionManager.updateMetadata(bookTitle, chapterTitle)

        // Check if we need to rebuild the playlist
        val needsNewPlaylist = playlistAudioHrefs != audioFiles || audioHrefToTrackIndex.isEmpty()

        if (needsNewPlaylist) {
            // Need to build a new playlist - calculate position to seek to
            val positionToSeek = initialPositionMs
                ?: (targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() })
                ?: mediaPlaybackController.findPositionForFragment(initialFragmentId)
                ?: mediaPlaybackController.findPositionForProgression(initialProgression)
                ?: 0L
            preparePlaylist(audioFiles, targetTrackIndex, positionToSeek, allChapterClips)
            val newStartOffset = audioStartOffsets[targetAudioHref] ?: 0L
            mediaPlaybackController.setChapterStartOffset(newStartOffset)
            mediaPlaybackController.forceUpdatePosition()
        } else {
            // Playlist already set up - verify we're on the correct track and position
            val player = exoPlayer ?: return false
            val currentTrackIndex = player.currentMediaItemIndex
            val expectedTrackIndex = audioHrefToTrackIndex[targetAudioHref] ?: 0

            // Calculate target position - needed for both track switch and same-track seek
            val positionToSeek = initialPositionMs
                ?: (targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() })

            if (currentTrackIndex != expectedTrackIndex) {
                // Wrong track - need to switch
                player.seekTo(expectedTrackIndex, positionToSeek ?: 0L)

                // Update duration, offset, and clip filter for the new track
                val newDuration = audioDurations[targetAudioHref]
                if (newDuration != null && newDuration > 0) {
                    mediaPlaybackController.setTotalDuration(newDuration)
                }
                val newStartOffset = audioStartOffsets[targetAudioHref] ?: 0L
                mediaPlaybackController.setChapterStartOffset(newStartOffset)
                mediaPlaybackController.setCurrentAudioHref(targetAudioHref)
            } else if (positionToSeek != null && positionToSeek > 0) {
                // Same track but different position (e.g., double-tap on a sentence)
                player.seekTo(positionToSeek)
            }
            currentAudioHref = targetAudioHref
        }

        // If clips were updated (new chapter), ensure audio href filter is set
        // This handles the case where we reused the playlist and same track
        if (clipsWereUpdated) {
            mediaPlaybackController.setCurrentAudioHref(targetAudioHref)
        }

        // Set playWhenReady AFTER all seeking/preparation is done.
        // This ensures playback starts from the correct position without flickering.
        exoPlayer?.playWhenReady = true

        // Track the current chapter for navigation from notifications/Android Auto
        currentChapterHref = normalizedHref

        // Prefetch next chapter in background
        smilLoadingManager.prefetchNextChapter(normalizedHref)
        return true
    }

    /**
     * Prepares ExoPlayer with a playlist containing all audio files for the chapter.
     *
     * This enables seamless switching between audio files using seekTo(trackIndex, positionMs)
     * instead of re-preparing the player. ExoPlayer only buffers the current track,
     * so this doesn't increase memory usage or loading time.
     *
     * @param audioHrefs Ordered list of audio file hrefs for this chapter
     * @param initialTrackIndex The track index to start at
     * @param initialPositionMs The position within the initial track to start at
     */
    private fun preparePlaylist(
        audioHrefs: List<Url>,
        initialTrackIndex: Int,
        initialPositionMs: Long,
        allChapterClips: List<MediaOverlayClip> = emptyList(),
    ) {
        Log.d(TAG, "preparePlaylist: audioHrefs=${audioHrefs.size}, initialTrack=$initialTrackIndex, initialPos=$initialPositionMs")
        if (audioHrefs.isEmpty()) {
            Log.w(TAG, "preparePlaylist: audioHrefs is EMPTY, returning")
            return
        }

        // Build MediaItems for all audio files
        val mediaItems = audioHrefs.map { audioHref ->
            val audioUrl = publication.baseUrl?.resolve(audioHref)?.toString()
                ?: audioHref.toString()

            MediaItem.Builder()
                .setUri(audioUrl)
                .setMediaMetadata(mediaSessionManager.buildCurrentMetadata())
                .build()
        }

        // Build media sources from items
        val mediaSources = mediaItems.map { mediaItem ->
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Set playlist and seek to initial position
        val player = exoPlayer
        Log.d(TAG, "preparePlaylist: exoPlayer=$player")
        if (player == null) {
            Log.e(TAG, "preparePlaylist: exoPlayer is NULL, cannot prepare!")
            return
        }
        Log.d(TAG, "preparePlaylist: calling setMediaSources with ${mediaSources.size} sources")
        player.setMediaSources(mediaSources, initialTrackIndex, initialPositionMs)
        mediaPlaybackController.onMediaItemsReplaced(player)
        Log.d(TAG, "preparePlaylist: calling player.prepare()")
        player.prepare()
        Log.d(TAG, "preparePlaylist: player.prepare() called, playbackState=${player.playbackState}, mediaItemCount=${player.mediaItemCount}")

        // Update tracking
        playlistAudioHrefs = audioHrefs
        audioHrefToTrackIndex = audioHrefs.mapIndexed { index, href -> href to index }.toMap()
        currentAudioHref = audioHrefs.getOrNull(initialTrackIndex)

        // Update controller's clip filter for the new audio file
        currentAudioHref?.let { mediaPlaybackController.setCurrentAudioHref(it) }

        // Schedule clip callbacks for text highlighting
        scheduleClipsForAllTracks(audioHrefs, allChapterClips)
        Log.d(TAG, "preparePlaylist: completed")
    }

    /**
     * Schedules PlayerMessage callbacks for all tracks in the playlist.
     * This enables CLIP_CHANGED broadcasts when playback reaches clip boundaries,
     * allowing text highlighting to work even when the UI reconnects after being destroyed.
     */
    private fun scheduleClipsForAllTracks(
        audioHrefs: List<Url>,
        allChapterClips: List<MediaOverlayClip>,
    ) {
        if (allChapterClips.isEmpty()) return

        // Clear any previously scheduled clips
        mediaPlaybackController.clearScheduledClips()

        // Group clips by audio href (track)
        val clipsByAudio = allChapterClips.groupBy { it.audioHref }

        // Schedule clips for each track
        audioHrefs.forEachIndexed { trackIndex, audioHref ->
            val trackClips = clipsByAudio[audioHref] ?: return@forEachIndexed
            val schedulableClips = trackClips.map { clip ->
                SchedulableClip(
                    fragmentId = clip.fragmentId,
                    textHref = clip.textHref.toString(),
                    startTimeMs = (clip.startTime * SECONDS_TO_MS).toLong(),
                    endTimeMs = (clip.endTime * SECONDS_TO_MS).toLong(),
                )
            }
            mediaPlaybackController.scheduleClipsForTrack(trackIndex, schedulableClips)
        }
    }

    /**
     * Switches to a different audio file in the playlist.
     * Uses seekTo(trackIndex, positionMs) for seamless switching without re-preparing.
     *
     * @param audioHref The audio file to switch to
     * @param positionMs The position to seek to in the target audio file
     */
    fun switchAudioFileIfNeeded(audioHref: Url, positionMs: Long) {
        if (currentAudioHref == audioHref) {
            // Same audio file - just seek to position
            exoPlayer?.seekTo(positionMs)
            return
        }

        val trackIndex = audioHrefToTrackIndex[audioHref]
        if (trackIndex != null) {
            // Audio file is in current playlist - use seekTo for seamless switch
            currentAudioHref = audioHref

            // Update duration, start offset, and audio href filter for the new audio file
            val durationMs = audioDurations[audioHref]
            if (durationMs != null && durationMs > 0) {
                mediaPlaybackController.setTotalDuration(durationMs)
            }
            val startOffset = audioStartOffsets[audioHref] ?: 0L
            mediaPlaybackController.setChapterStartOffset(startOffset)
            mediaPlaybackController.setCurrentAudioHref(audioHref)

            // Seamless track switch using ExoPlayer's playlist capability
            exoPlayer?.seekTo(trackIndex, positionMs)
        } else {
            // Audio file not in playlist (shouldn't happen normally)
            preparePlaylist(listOf(audioHref), 0, positionMs)
        }
    }

    /**
     * Gets the currently loaded audio file href.
     */
    fun getCurrentAudioHref(): Url? = currentAudioHref

    /**
     * Converts SmilClip (from shared parser) to MediaOverlayClip (Android-specific).
     *
     * SmilClip contains string references that are already resolved to absolute paths
     * (relative to publication root) by SmilLoadingManager.parseSmilFile().
     * This method parses them as Readium Url objects and extracts fragment IDs.
     *
     * @param smilClips The clips from the shared parser with resolved paths
     * @return List of MediaOverlayClip with Readium Url objects
     */
    private fun convertSmilClipsToMediaOverlayClips(
        smilClips: List<SmilClip>,
    ): List<MediaOverlayClip> {
        return smilClips.mapNotNull { raw ->
            try {
                // Paths are already resolved to absolute paths in SmilLoadingManager.parseSmilFile()
                // so we just need to parse them as URLs and extract fragment IDs
                val textUrl = Url(raw.textSrc) ?: return@mapNotNull null
                val fragmentId = textUrl.fragment

                val audioUrl = Url(raw.audioSrc) ?: return@mapNotNull null

                MediaOverlayClip(
                    textHref = textUrl.removeFragment(),
                    fragmentId = fragmentId,
                    audioHref = audioUrl.removeFragment(),
                    startTime = raw.clipBegin,
                    endTime = raw.clipEnd,
                )
            } catch (e: Exception) {
                analytics.logException(e, "Failed to convert clip")
                null
            }
        }
    }

    /**
     * Gets the chapter title from the publication's reading order or table of contents.
     *
     * First tries to find the chapter in the reading order by matching the href.
     * Falls back to the table of contents if not found in reading order.
     *
     * @param chapterHref The href of the chapter
     * @return The chapter title, or null if not found
     */
    private fun getChapterTitle(chapterHref: Url): String? {
        val normalizedHref = chapterHref.removeFragment().toString()

        // Try reading order first (most common case)
        val readingOrderTitle = publication.readingOrder.find { link ->
            link.href.toString().substringBefore('#') == normalizedHref
        }?.title

        if (readingOrderTitle != null) {
            return readingOrderTitle
        }

        // Fall back to table of contents (may have more descriptive titles)
        return findTitleInToc(publication.tableOfContents, normalizedHref)
    }

    /**
     * Recursively searches the table of contents for a matching href.
     */
    private fun findTitleInToc(
        links: List<org.readium.r2.shared.publication.Link>,
        normalizedHref: String,
    ): String? {
        for (link in links) {
            if (link.href.toString().substringBefore('#') == normalizedHref) {
                return link.title
            }
            // Search children recursively
            val childTitle = findTitleInToc(link.children, normalizedHref)
            if (childTitle != null) {
                return childTitle
            }
        }
        return null
    }
}
