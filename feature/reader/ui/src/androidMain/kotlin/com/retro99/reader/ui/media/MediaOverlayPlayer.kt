package com.retro99.reader.ui.media

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
import com.retro99.reader.ui.playback.AudioFocusManager
import com.retro99.reader.ui.playback.ForegroundServiceController
import com.retro99.reader.ui.playback.LocatorTracker
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.reader.ui.playback.PermissionDenialState
import com.retro99.reader.ui.playback.PlaybackStateTracker
import com.retro99.reader.ui.publication.EpubPublication
import kotlin.coroutines.cancellation.CancellationException
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

/** Conversion factor from seconds to milliseconds */
private const val SECONDS_TO_MS = 1000.0

/** Seek increment in milliseconds (10 seconds) */
private const val SEEK_INCREMENT_MS = 10_000L

/**
 * Player for EPUB Media Overlays (SMIL-based text-audio synchronization).
 *
 * This player:
 * 1. Parses SMIL files from the EPUB to get text-audio sync data
 * 2. Uses the shared ExoPlayer (owned by MediaPlaybackService) to play audio files
 * 3. Tracks playback position and emits the current Locator for text highlighting
 *
 * Audio files are read directly from the EPUB container using Readium's Publication API
 * and provided to ExoPlayer via ByteArrayDataSource.
 *
 * Note: The ExoPlayer is owned by MediaPlaybackService and shared via MediaPlaybackController.
 * This ensures playback can continue for Android Auto even when the reader UI is closed.
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
    private val audioFocusManager: AudioFocusManager,
    private val foregroundServiceController: ForegroundServiceController,
    private val locatorTracker: LocatorTracker,
    private val playbackStateTracker: PlaybackStateTracker,
) {
    /**
     * Gets the ExoPlayer from the MediaPlaybackService via the controller.
     * The service owns the player to ensure it survives ReaderScope lifecycle.
     *
     * @throws IllegalStateException if the service hasn't been created yet
     */
    private val exoPlayer: ExoPlayer
        get() = mediaPlaybackController.player
            ?: throw IllegalStateException("MediaPlaybackService not started - call startService first")
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

    // Mutex to prevent concurrent playInternal() calls from double-taps
    private val playMutex = Mutex()

    /**
     * Pending playlist info to apply when the player becomes available.
     * This is set by prepareChapterDuration when called before the service is started,
     * and applied when playback starts.
     */
    private data class PendingPlaylist(
        val audioFiles: List<Url>,
        val targetTrackIndex: Int,
        val positionToSeek: Long,
    )

    private var pendingPlaylist: PendingPlaylist? = null

    /**
     * Listener for media item (track) transitions within a playlist.
     * When ExoPlayer automatically transitions from one audio file to the next,
     * we need to update the duration and start offset for the new track.
     */
    private val trackTransitionListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newTrackIndex = exoPlayer.currentMediaItemIndex
            val newAudioHref = playlistAudioHrefs.getOrNull(newTrackIndex)

            // Only handle automatic transitions (not seeks or playlist changes we initiate)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                if (newAudioHref != null && newAudioHref != currentAudioHref) {
                    currentAudioHref = newAudioHref

                    // Update duration for the new audio file
                    val newDuration = audioDurations[newAudioHref]
                    if (newDuration != null && newDuration > 0) {
                        playbackStateTracker.setTotalDuration(newDuration)
                    }

                    // Update start offset for position normalization
                    val newStartOffset = audioStartOffsets[newAudioHref] ?: 0L
                    locatorTracker.setChapterStartOffset(newStartOffset)

                    // Update filtered clips for the new audio file (with track index for message scheduling)
                    locatorTracker.setCurrentAudioHref(newAudioHref, newTrackIndex)
                }
            }
        }
    }

    /** Tracks whether the player listener has been registered */
    private var playerListenerRegistered = false

    init {
        // Set up callback for when playback exceeds chapter clip range
        // This handles the case where a single audio file contains multiple chapters
        locatorTracker.onChapterClipsExceeded = {
            mediaPlaybackController.player?.pause()
            playbackStateTracker.emitChapterCompleted()
        }

        // Configure audio attributes for speech content (will apply when player is available)
        audioFocusManager.configurePlayerAudioAttributes()

        // Set book info for deep link navigation from notification
        mediaPlaybackController.setBookInfo(
            epubPublication.serverId,
            epubPublication.bookUuid,
            epubPublication.bookType,
        )

        // Set initial book title from publication metadata
        bookTitle = publication.metadata.title ?: "Reading Aloud"
        mediaPlaybackController.updateMetadata(bookTitle)
    }

    /**
     * Registers the player listener if not already registered.
     * Called lazily when the service is started and player is available.
     */
    private fun ensurePlayerListenerRegistered() {
        if (!playerListenerRegistered) {
            exoPlayer.addListener(trackTransitionListener)
            // Register trackers now that player is available
            playbackStateTracker.ensureListenerRegistered()
            locatorTracker.ensureInitialSeekApplied()
            // Configure audio attributes now that player is available
            audioFocusManager.configurePlayerAudioAttributes()
            playerListenerRegistered = true

            // Apply any pending playlist that was deferred from prepareChapterDuration
            applyPendingPlaylistIfNeeded()
        }
    }

    /**
     * Applies any pending playlist that was deferred when prepareChapterDuration
     * was called before the service was started.
     */
    private fun applyPendingPlaylistIfNeeded() {
        val pending = pendingPlaylist ?: return
        pendingPlaylist = null

        // Check if we still need to rebuild the playlist
        val needsNewPlaylist = playlistAudioHrefs != pending.audioFiles ||
            audioHrefToTrackIndex.isEmpty()

        if (needsNewPlaylist) {
            preparePlaylist(pending.audioFiles, pending.targetTrackIndex, pending.positionToSeek)
        } else if (pending.positionToSeek > 0) {
            exoPlayer.seekTo(pending.positionToSeek)
            locatorTracker.forceUpdatePosition()
        }
    }

    /**
     * Initializes the player with lazy SMIL loading.
     *
     * This builds a lightweight index of SMIL files without fully parsing them.
     * Full parsing happens on-demand when a chapter is prepared.
     *
     * Also starts the MediaPlaybackService early to warm up the audio codec.
     * This prevents "Failed to query component interface" errors on first play,
     * which occur when the codec isn't initialized before prepare() is called.
     *
     * @param initialChapterHref The initial chapter href to optimize index building for
     */
    suspend fun initialize(initialChapterHref: String? = null) {
        smilLoadingManager.initialize(playerScope)

        // Start the service early to warm up the audio codec.
        // This creates the ExoPlayer before the user clicks play, giving the
        // MediaCodec system time to initialize and avoiding first-play failures.
        // Note: We don't need notification permission yet - the service can start
        // without showing a notification until playback actually begins.
        startServiceEarly()

        // Load cover image ASYNC - don't block initialization on cover loading
        // The notification will show without cover initially, then update when ready
        playerScope.launch {
            val coverArtwork = epubPublication.cover()
            mediaPlaybackController.updateMetadata(bookTitle, coverArtwork = coverArtwork)
        }

        // Build initial index - must complete before getClipsForChapter to avoid fallback scan
        val chapterHref = initialChapterHref
            ?: publication.readingOrder.firstOrNull()?.href?.toString()
            ?: return

        smilLoadingManager.buildInitialIndex(chapterHref)
    }

    /**
     * Starts the MediaPlaybackService early to warm up the audio codec.
     *
     * Before the refactoring, ExoPlayer was created when the ReaderScope was created
     * (when user opened the book). This gave the MediaCodec time to initialize before
     * the user clicked play. After moving ExoPlayer to MediaPlaybackService, it was
     * only created when the user clicked play, causing codec initialization failures.
     *
     * By starting the service during initialize(), we restore the original timing:
     * ExoPlayer is created when the book is opened, not when play is clicked.
     */
    private suspend fun startServiceEarly() {
        // Only start if not already running
        if (mediaPlaybackController.isServiceRunning()) {
            return
        }

        // Start the foreground service - this creates the ExoPlayer
        // Note: On Android 12+, this may fail if the app isn't in foreground,
        // but that's fine - we'll start it again when play is clicked
        val started = foregroundServiceController.startService()
        if (started) {
            // Wait for the service to be ready so the player is available
            mediaPlaybackController.awaitServiceReady()
            // Register listeners now that player is available
            ensurePlayerListenerRegistered()
        }
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
        // Find a chapter with audio BEFORE starting the foreground service.
        // This prevents ForegroundServiceDidNotStartInTimeException when the user
        // clicks play on a chapter without audio (e.g., cover page, table of contents).
        val chapterToPlay = if (chapterHref != null) {
            val chapterWithAudio = smilLoadingManager.findChapterWithAudio(
                chapterHref.removeFragment().toString(),
            )
            if (chapterWithAudio == null) {
                // No chapters have audio - nothing to play
                analytics.logException(
                    IllegalStateException("No chapters with audio found"),
                    "Cannot play: no audio content available starting from $chapterHref",
                )
                return
            }
            Url(chapterWithAudio)
        } else {
            // No chapter specified - will resume current audio
            null
        }

        // Request notification permission before starting foreground service (Android 13+)
        // NOTE: We intentionally do NOT set optimistic state (isPlaying=true, BUFFERING)
        // before permission is granted. This prevents UI flicker where the play button
        // briefly shows "playing" state before reverting if permission is denied.
        val permissionGranted = notificationPermissionHandler.ensurePermission()
        if (!permissionGranted) {
            _showPermissionDeniedDialog.value = true
            return
        }

        // NOTE: We do NOT manually request audio focus here because ExoPlayer is configured
        // with handleAudioFocus=true (set in AudioFocusManager.configurePlayerAudioAttributes()).
        // ExoPlayer will automatically request focus when playback starts.
        // Manually requesting focus here caused a conflict: both our AudioFocusManager and
        // ExoPlayer would request focus, causing brief focus loss/regain cycles that paused
        // playback immediately after starting.

        // Start foreground service for background playback
        val serviceStarted = foregroundServiceController.startService()
        if (!serviceStarted) {
            // App was backgrounded during permission dialog or other system restriction
            analytics.logException(
                IllegalStateException("Cannot start foreground service from background"),
                "Foreground service start blocked by system",
            )
            return
        }

        // Wait for service to be ready - startService() is async, the service's onCreate()
        // runs later on the main thread and calls onServiceCreated() which provides the player
        mediaPlaybackController.awaitServiceReady()

        // Now that service is started and player is available, register listeners
        ensurePlayerListenerRegistered()

        if (chapterToPlay != null) {
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
        } else {
            // No chapter change - check if we need to switch audio files for the target fragment
            val targetClip = initialFragmentId?.let { locatorTracker.findClipForFragment(it) }
            val targetAudioHref = targetClip?.audioHref

            // Determine position to seek to
            val positionToSeek = initialPositionMs
                ?: targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() }
                ?: locatorTracker.findPositionForProgression(initialProgression)

            // Check if we need to switch audio files (for multi-audio-file chapters)
            if (targetAudioHref != null && targetAudioHref != currentAudioHref) {
                switchAudioFileIfNeeded(targetAudioHref, positionToSeek ?: 0L)
            } else if (positionToSeek != null && positionToSeek > 0) {
                exoPlayer.seekTo(positionToSeek)
            }

            // Set playWhenReady and call play() after seeking
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    fun pause() {
        // Notify audio focus manager that user manually paused
        // This prevents auto-resume when focus is regained
        audioFocusManager.onUserPaused()
        exoPlayer.pause()
    }

    /**
     * Resumes playback from the current position without seeking.
     */
    fun resume() {
        exoPlayer.play()
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
        exoPlayer.seekTo(positionMs)
        locatorTracker.forceUpdatePosition()
    }

    /**
     * Seeks forward by 10 seconds from the current position.
     * The position is clamped to the duration of the current media.
     */
    fun seekForward() {
        val newPosition = (exoPlayer.currentPosition + SEEK_INCREMENT_MS)
            .coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
        seekTo(newPosition)
    }

    /**
     * Seeks backward by 10 seconds from the current position.
     * The position is clamped to 0.
     */
    fun seekBackward() {
        val newPosition = (exoPlayer.currentPosition - SEEK_INCREMENT_MS)
            .coerceAtLeast(0L)
        seekTo(newPosition)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
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

        // Store ALL clips in the locator tracker for fragment lookup.
        // This is important because chapters may span multiple audio files, and we need
        // to be able to find any sentence regardless of which audio file it's in.
        locatorTracker.setChapterClips(allChapterClips)

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
            playbackStateTracker.setTotalDuration(initialDuration)
        }
        val initialStartOffset = audioStartOffsets[targetAudioHref] ?: 0L
        locatorTracker.setChapterStartOffset(initialStartOffset)
        if (targetAudioHref != null) {
            locatorTracker.setCurrentAudioHref(targetAudioHref, targetTrackIndex)
        }

        // Mark player as ready from UI perspective - audio content is loaded and duration is set.
        // This allows the UI to show controls even before ExoPlayer is prepared (deferred until play).
        playbackStateTracker.setPlayerReady(true)

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

        // Check if the player is available (service has been started)
        val player = mediaPlaybackController.player
        if (player != null) {
            // Player is available - prepare playlist or seek immediately
            if (needsNewPlaylist) {
                preparePlaylist(audioFiles, targetTrackIndex, positionToSeek)
            } else if (positionToSeek > 0) {
                // Same playlist and track - just seek to position
                player.seekTo(positionToSeek)
                locatorTracker.forceUpdatePosition()
            }
            pendingPlaylist = null
        } else {
            // Player not available yet - store pending info to apply when playback starts
            if (needsNewPlaylist) {
                pendingPlaylist = PendingPlaylist(audioFiles, targetTrackIndex, positionToSeek)
            } else {
                // No new playlist needed, but store position for potential seeking
                pendingPlaylist = PendingPlaylist(audioFiles, targetTrackIndex, positionToSeek)
            }
        }

        // Prefetch next chapter in background
        smilLoadingManager.prefetchNextChapter(normalizedHref)
    }

    fun release() {
        // CRITICAL: Cancel the scope FIRST to stop any in-flight coroutines
        // that might be using exoPlayer. If we release exoPlayer while
        // playInternal() or prepareChapterAsync() is running, ExoPlayer throws
        // IllegalStateException on any method call after release().
        playerScope.cancel()

        // Remove track transition listener before releasing player (if registered)
        if (playerListenerRegistered) {
            mediaPlaybackController.player?.removeListener(trackTransitionListener)
            playerListenerRegistered = false
        }

        // Release trackers
        locatorTracker.release()
        playbackStateTracker.release()

        // Abandon audio focus
        audioFocusManager.abandonFocus()

        // Stop foreground service (service manages the player and session lifecycle)
        foregroundServiceController.stopService()

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
        playerScope.launch {
            try {
                val success = prepareChapterAsync(
                    chapterHref,
                    initialFragmentId,
                    initialProgression,
                    initialPositionMs,
                )
                if (!success) {
                    // No playable content - emit completion to skip to next chapter
                    handlePreparationFailure(
                        reason = "Chapter preparation returned no playable content",
                        emitCompletion = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        playbackStateTracker.setPlayingState(false)
        playbackStateTracker.setPlaybackState(PlaybackState.ERROR)
        audioFocusManager.abandonFocus()
        foregroundServiceController.stopService()

        if (emitCompletion) {
            // Emit completion event so coordinator can skip to next chapter
            playbackStateTracker.emitChapterCompleted()
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
        val existingClips = locatorTracker.getChapterClips()
        val clipsAlreadySet = existingClips.size == allChapterClips.size &&
            existingClips.firstOrNull()?.fragmentId == allChapterClips.firstOrNull()?.fragmentId

        val clipsWereUpdated = !clipsAlreadySet
        if (clipsWereUpdated) {
            locatorTracker.setChapterClips(allChapterClips)
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
        val currentDuration = playbackStateTracker.totalDuration.value
        val targetDuration = audioDurations[targetAudioHref]
        if (targetDuration != null && targetDuration > 0 && currentDuration != targetDuration) {
            playbackStateTracker.setTotalDuration(targetDuration)
        }

        // Update metadata with chapter title for notification display
        val chapterTitle = getChapterTitle(chapterHref)
        mediaPlaybackController.updateMetadata(bookTitle, chapterTitle)

        // Check if we need to rebuild the playlist
        val needsNewPlaylist = playlistAudioHrefs != audioFiles || audioHrefToTrackIndex.isEmpty()

        if (needsNewPlaylist) {
            // Need to build a new playlist - calculate position to seek to
            val positionToSeek = initialPositionMs
                ?: (targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() })
                ?: locatorTracker.findPositionForFragment(initialFragmentId)
                ?: locatorTracker.findPositionForProgression(initialProgression)
                ?: 0L
            preparePlaylist(audioFiles, targetTrackIndex, positionToSeek)
        } else {
            // Playlist already set up - verify we're on the correct track and position
            val currentTrackIndex = exoPlayer.currentMediaItemIndex
            val expectedTrackIndex = audioHrefToTrackIndex[targetAudioHref] ?: 0

            // Calculate target position - needed for both track switch and same-track seek
            val positionToSeek = initialPositionMs
                ?: (targetClip?.let { (it.startTime * SECONDS_TO_MS).toLong() })

            if (currentTrackIndex != expectedTrackIndex) {
                // Wrong track - need to switch
                exoPlayer.seekTo(expectedTrackIndex, positionToSeek ?: 0L)

                // Update duration, offset, and clip filter for the new track
                val newDuration = audioDurations[targetAudioHref]
                if (newDuration != null && newDuration > 0) {
                    playbackStateTracker.setTotalDuration(newDuration)
                }
                val newStartOffset = audioStartOffsets[targetAudioHref] ?: 0L
                locatorTracker.setChapterStartOffset(newStartOffset)
                locatorTracker.setCurrentAudioHref(targetAudioHref, expectedTrackIndex)
            } else if (positionToSeek != null && positionToSeek > 0) {
                // Same track but different position (e.g., double-tap on a sentence)
                exoPlayer.seekTo(positionToSeek)
            }
            currentAudioHref = targetAudioHref
        }

        // If clips were updated (new chapter), ensure audio href filter is set
        // This handles the case where we reused the playlist and same track
        if (clipsWereUpdated) {
            val trackIdx = audioHrefToTrackIndex[targetAudioHref] ?: 0
            locatorTracker.setCurrentAudioHref(targetAudioHref, trackIdx)
        }

        // Set playWhenReady AFTER all seeking/preparation is done.
        // This ensures playback starts from the correct position without flickering.
        exoPlayer.playWhenReady = true

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
    ) {
        if (audioHrefs.isEmpty()) return

        // Build MediaItems for all audio files
        val mediaItems = audioHrefs.map { audioHref ->
            val audioUrl = publication.baseUrl?.resolve(audioHref)?.toString()
                ?: audioHref.toString()

            MediaItem.Builder()
                .setUri(audioUrl)
                .setMediaMetadata(mediaPlaybackController.buildCurrentMetadata())
                .build()
        }

        // Build media sources from items
        val mediaSources = mediaItems.map { mediaItem ->
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Set playlist and seek to initial position
        exoPlayer.setMediaSources(mediaSources, initialTrackIndex, initialPositionMs)
        exoPlayer.prepare()

        // Update tracking
        playlistAudioHrefs = audioHrefs
        audioHrefToTrackIndex = audioHrefs.mapIndexed { index, href -> href to index }.toMap()
        currentAudioHref = audioHrefs.getOrNull(initialTrackIndex)

        // Update locator tracker's clip filter for the new audio file
        currentAudioHref?.let { locatorTracker.setCurrentAudioHref(it, initialTrackIndex) }
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
            exoPlayer.seekTo(positionMs)
            return
        }

        val trackIndex = audioHrefToTrackIndex[audioHref]
        if (trackIndex != null) {
            // Audio file is in current playlist - use seekTo for seamless switch
            currentAudioHref = audioHref

            // Update duration, start offset, and audio href filter for the new audio file
            val durationMs = audioDurations[audioHref]
            if (durationMs != null && durationMs > 0) {
                playbackStateTracker.setTotalDuration(durationMs)
            }
            val startOffset = audioStartOffsets[audioHref] ?: 0L
            locatorTracker.setChapterStartOffset(startOffset)
            locatorTracker.setCurrentAudioHref(audioHref, trackIndex)

            // Seamless track switch using ExoPlayer's playlist capability
            exoPlayer.seekTo(trackIndex, positionMs)
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
