package com.retro99.reader.ui.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retro99.base.deeplink.DeepLinkUriBuilder
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.media.MediaOverlayClip
import com.retro99.reader.ui.media.smil.SmilClipRepository
import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.model.PlaybackState
import com.retro99.reader.ui.playback.auto.AutoMediaBrowser
import com.retro99.reader.ui.playback.auto.AutoMediaIds
import com.retro99.reader.ui.playback.auto.HeadlessPlaybackSession
import com.retro99.reader.ui.playback.auto.HeadlessSessionFactory
import com.retro99.reader.ui.tts.TtsChapterTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

private const val TAG = "čič123"

/**
 * Foreground service for audio playback with MediaLibrarySession support.
 *
 * This service owns:
 * - ExoPlayer instance (survives ReaderScope destruction)
 * - MediaLibrarySession (for Android Auto browsing support)
 * - Clip scheduling and CLIP_CHANGED broadcasts
 *
 * The service is started when audio playback begins and stopped when playback ends.
 * Media3's MediaLibraryService automatically handles notification creation and updates.
 *
 * Architecture: Service-centric model following Storyteller's pattern.
 * The service is completely self-sufficient and doesn't depend on ReaderScope components.
 */
@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaLibraryService() {

    private enum class PlaybackContentType {
        MEDIA_OVERLAY,
        TTS,
    }

    private val controller: MediaPlaybackController by inject()
    private val clipRepository: SmilClipRepository by inject()
    private val autoMediaBrowser: AutoMediaBrowser by inject()
    private val headlessSessionFactory: HeadlessSessionFactory by inject()

    // Service owns these - they survive ReaderScope destruction
    private var player: ExoPlayer? = null
    private var sessionPlayer: TtsSessionPlayer? = null
    private var mediaSession: MediaLibrarySession? = null

    // Active headless playback session (for Android Auto playback without phone app)
    private var activeHeadlessSession: HeadlessPlaybackSession? = null

    // Clip scheduling for text highlighting
    private val clipScheduler = ClipScheduler { clip ->
        notifyClipChanged(clip.fragmentId, clip.textHref, clip.startTimeMs / 1000.0, clip.endTimeMs / 1000.0)
    }

    private val handler = Handler(Looper.getMainLooper())

    // Metadata for notification display
    private var bookTitle: String = "Reading Aloud"
    private var chapterTitle: String? = null
    private var coverArtwork: ByteArray? = null
    private var playbackContentType = PlaybackContentType.MEDIA_OVERLAY
    private var ttsSessionTimeline: TtsSessionTimeline? = null

    // Book identification for deep link navigation
    private var serverId: String? = null
    private var bookUuid: String? = null
    private var bookType: BookType? = null

    // Track connected Android Auto controllers for URI permission granting
    private val automotiveControllers = mutableSetOf<String>()

    // ==================== Service-Owned State Flows ====================
    // These flows are the source of truth for playback state, surviving ReaderScope destruction

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _totalDuration = MutableStateFlow<Long?>(null)
    val totalDuration: StateFlow<Long?> = _totalDuration.asStateFlow()

    private val _isPlayerReady = MutableStateFlow(false)
    val isPlayerReady: StateFlow<Boolean> = _isPlayerReady.asStateFlow()

    private val _chapterAudioCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val chapterAudioCompleted: Flow<Unit> = _chapterAudioCompleted.asSharedFlow()

    // ==================== Position Tracking (from LocatorTracker) ====================

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _chapterStartOffset = MutableStateFlow(0L)

    val normalizedPosition: StateFlow<Long> = combine(
        _currentPosition,
        _chapterStartOffset
    ) { rawPosition, offset ->
        (rawPosition - offset).coerceAtLeast(0L)
    }.stateIn(serviceScope, SharingStarted.Eagerly, 0L)

    // Current chapter's clips
    private var currentChapterClips: List<MediaOverlayClip> = emptyList()
    private var currentAudioHref: Url? = null
    private var currentAudioFileClips: List<MediaOverlayClip> = emptyList()
    private var hasNotifiedChapterExceeded = false

    // Position update job
    private var positionUpdateJob: Job? = null

    // ==================== Locator Tracking ====================

    private data class LocatorWithClip(
        val locator: Locator,
        val clip: MediaOverlayClip,
    )

    private val _currentLocatorWithClip = MutableStateFlow<LocatorWithClip?>(null)

    val currentLocator: StateFlow<AudioLocatorState?> = _currentLocatorWithClip.asStateFlow()
        .map { locatorWithClip ->
            if (locatorWithClip == null) return@map null
            val locator = locatorWithClip.locator
            val clip = locatorWithClip.clip
            val durationMs = ((clip.endTime - clip.startTime) * SECONDS_TO_MS).toLong()
            AudioLocatorState(
                locator = LocatorState(
                    href = locator.href.toString(),
                    type = locator.mediaType.toString(),
                    title = locator.title,
                    progression = locator.locations.progression,
                    position = locator.locations.position,
                    totalProgression = locator.locations.totalProgression,
                    fragments = locator.locations.fragments,
                ),
                sentenceDurationMs = durationMs,
            )
        }.stateIn(
            scope = serviceScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    // Callback for when chapter audio playback exceeds the clip range
    var onChapterClipsExceeded: (() -> Unit)? = null

    // ==================== Internal State ====================

    private var wasPlaying: Boolean = false
    private val stateLock = Any()

    private val taskRemovedTimeoutMs = 30 * 60 * 1000L // 30 minutes

    private val stopSelfRunnable = Runnable {
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.isPlaying) {
            stopSelf()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                playbackContentType = if (mediaItem.mediaId.startsWith(TTS_MEDIA_ID_PREFIX)) {
                    PlaybackContentType.TTS
                } else {
                    ttsSessionTimeline = null
                    PlaybackContentType.MEDIA_OVERLAY
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "SERVICE onIsPlayingChanged: isPlaying=$isPlaying, clipsCount=${currentChapterClips.size}")
            _isPlaying.value = isPlaying
            synchronized(stateLock) {
                wasPlaying = isPlaying
            }
            if (isPlaying) {
                handler.removeCallbacks(stopSelfRunnable)
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }
        }

        override fun onPlaybackStateChanged(playerState: Int) {
            val stateName = when (playerState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playerState)"
            }
            Log.d(TAG, "SERVICE onPlaybackStateChanged: state=$stateName, clipsCount=${currentChapterClips.size}")

            val p = player ?: return
            updatePlaybackStateInternal(playerState, p.isPlaying)
            _isPlayerReady.value = playerState == Player.STATE_READY

            when (playerState) {
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    if (playbackContentType == PlaybackContentType.MEDIA_OVERLAY) {
                        _chapterAudioCompleted.tryEmit(Unit)
                    }
                }
                Player.STATE_READY -> {
                    val duration = p.duration
                    if (playbackContentType == PlaybackContentType.TTS) {
                        ttsSessionTimeline?.let { timeline ->
                            _totalDuration.value = timeline.chapterTimeline.durationMs
                        }
                    } else if (duration > 0 && _totalDuration.value == null) {
                        _totalDuration.value = duration
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "SERVICE onPlayerError: ${error.errorCodeName}", error)
            _playbackState.value = PlaybackState.ERROR
            _isPlaying.value = false
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() called")

        // Create ExoPlayer - this service owns it
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val playbackPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = playbackPlayer

        playbackPlayer.addListener(playerListener)
        Log.d(TAG, "ExoPlayer created: ${player != null}")

        val notificationPlayer = TtsSessionPlayer(
            player = playbackPlayer,
            timelineProvider = {
                if (playbackContentType == PlaybackContentType.TTS) {
                    ttsSessionTimeline
                } else {
                    null
                }
            },
            seekRequester = { positionMs ->
                controller.requestTtsChapterPosition(positionMs)
            },
        )
        sessionPlayer = notificationPlayer

        // Create MediaLibrarySession - for Android Auto support
        val sessionActivityIntent = createSessionActivityIntent()

        mediaSession = MediaLibrarySession.Builder(this, notificationPlayer, LibraryCallback())
            .setSessionActivity(sessionActivityIntent)
            .setMediaButtonPreferences(createMediaButtonPreferences())
            .build()
        Log.d(TAG, "MediaLibrarySession created: ${mediaSession != null}")

        // Register the session with the service - REQUIRED for notification to appear
        // Without this, the service doesn't know which session to use for the notification
        addSession(mediaSession!!)
        Log.d(TAG, "Session added to service")

        // Set up notification provider - REQUIRED for foreground service notification
        // Without this, startForeground() is never called and the service gets killed
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )
        Log.d(TAG, "MediaNotificationProvider set")

        // Notify controller that service is ready
        Log.d(TAG, "Calling controller.onServiceCreated()")
        controller.onServiceCreated(this, playbackPlayer, mediaSession!!)
        Log.d(TAG, "onCreate() completed")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        Log.d(TAG, "onTaskRemoved: player=${currentPlayer != null}, mediaItemCount=${currentPlayer?.mediaItemCount}, isPlaying=${currentPlayer?.isPlaying}")
        if (currentPlayer == null || currentPlayer.mediaItemCount == 0) {
            Log.d(TAG, "onTaskRemoved: stopping self (no player or no media)")
            stopSelf()
            return
        }

        // If paused with content loaded, schedule a timeout to stop the service
        if (!currentPlayer.isPlaying) {
            Log.d(TAG, "onTaskRemoved: scheduling stop in $taskRemovedTimeoutMs ms (paused)")
            handler.postDelayed(stopSelfRunnable, taskRemovedTimeoutMs)
        } else {
            Log.d(TAG, "onTaskRemoved: keeping service running (playing)")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called", Exception("stack trace"))
        handler.removeCallbacks(stopSelfRunnable)
        stopPositionUpdates()

        // Close any active headless session first (before canceling scope)
        closeHeadlessSession()

        serviceScope.cancel()
        controller.onServiceDestroyed()

        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null

        player?.removeListener(playerListener)
        player?.release()
        player = null

        // Reset state
        playbackContentType = PlaybackContentType.MEDIA_OVERLAY
        currentChapterClips = emptyList()
        currentAudioFileClips = emptyList()
        currentAudioHref = null
        ttsSessionTimeline = null
        onChapterClipsExceeded = null

        super.onDestroy()
    }

    // ==================== Metadata Updates ====================

    /**
     * Updates metadata for notifications and lockscreen.
     * Called by components when book/chapter info changes.
     */
    fun updateMetadata(
        bookTitle: String? = null,
        chapterTitle: String? = null,
        coverArtwork: ByteArray? = null,
        serverId: String? = null,
        bookUuid: String? = null,
        bookType: BookType? = null,
    ) {
        if (bookTitle != null) this.bookTitle = bookTitle
        if (chapterTitle != null) this.chapterTitle = chapterTitle
        if (coverArtwork != null) this.coverArtwork = coverArtwork
        if (serverId != null) this.serverId = serverId
        if (bookUuid != null) this.bookUuid = bookUuid
        if (bookType != null) this.bookType = bookType

        // Update session activity for deep link
        updateSessionActivity()
    }

    /**
     * Builds MediaMetadata for the current book/chapter.
     */
    fun buildCurrentMetadata(): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle(chapterTitle ?: bookTitle)
            .setArtist(if (chapterTitle != null) bookTitle else "Parrot")
            .setDisplayTitle(chapterTitle ?: bookTitle)
            .apply {
                setArtworkDataIfSmall(coverArtwork, TAG)
            }
            .build()
    }

    fun prepareForTtsPlayback(
        bookTitle: String,
        chapterTitle: String?,
        coverArtwork: ByteArray?,
    ) {
        playbackContentType = PlaybackContentType.TTS
        ttsSessionTimeline = null
        clearScheduledClips()
        currentChapterClips = emptyList()
        currentAudioHref = null
        currentAudioFileClips = emptyList()
        hasNotifiedChapterExceeded = false
        onChapterClipsExceeded = null
        _chapterStartOffset.value = 0L
        _totalDuration.value = null
        _currentPosition.value = 0L
        _currentLocatorWithClip.value = null
        this.bookTitle = bookTitle
        this.chapterTitle = chapterTitle
        this.coverArtwork = coverArtwork
        updateSessionActivity()
        mediaSession?.setMediaButtonPreferences(createMediaButtonPreferences())
    }

    fun prepareForMediaOverlayPlayback() {
        playbackContentType = PlaybackContentType.MEDIA_OVERLAY
        ttsSessionTimeline = null
        mediaSession?.setMediaButtonPreferences(createMediaButtonPreferences())
    }

    internal fun updateTtsChapterTimeline(
        chapterTimeline: TtsChapterTimeline,
        sentenceIndex: Int,
    ) {
        ttsSessionTimeline = TtsSessionTimeline(
            chapterTimeline = chapterTimeline,
            sentenceIndex = sentenceIndex,
        )
        _totalDuration.value = chapterTimeline.durationMs
    }

    private fun createMediaButtonPreferences(): ImmutableList<CommandButton> {
        val navigationTarget = if (playbackContentType == PlaybackContentType.TTS) {
            "sentence"
        } else {
            "chapter"
        }
        return ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                .setDisplayName("Seek forward 10 seconds")
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                .setDisplayName("Seek back 10 seconds")
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("Previous $navigationTarget")
                .setSessionCommand(SessionCommand(COMMAND_PREVIOUS_CHAPTER, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Next $navigationTarget")
                .setSessionCommand(SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun createSessionActivityIntent(): PendingIntent {
        val intent = if (serverId != null && bookUuid != null && bookType != null) {
            val deepLinkUri = DeepLinkUriBuilder.buildReaderUri(serverId!!, bookUuid!!, bookType!!.value)
            Intent(Intent.ACTION_VIEW, deepLinkUri.toUri()).apply {
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().apply {
                    setPackage(packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
        }

        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateSessionActivity() {
        mediaSession?.sessionActivity = createSessionActivityIntent()
    }

    // ==================== Android Auto Support ====================

    /**
     * Grants URI permission for artwork to Android Auto controllers.
     * This is necessary for Android Auto to display cover art when using content:// URIs.
     */
    private fun grantArtworkUriPermissions(artworkUri: android.net.Uri) {
        if (automotiveControllers.isEmpty()) return

        // Only grant for content:// URIs (http/https URIs don't need permission grants)
        if (artworkUri.scheme != "content") return

        for (packageName in automotiveControllers) {
            try {
                grantUriPermission(
                    packageName,
                    artworkUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Log.d(TAG, "Granted artwork URI permission to $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to grant artwork URI permission to $packageName: ${e.message}")
            }
        }
    }

    // ==================== Clip Scheduling ====================

    /**
     * Schedules clip callbacks for a specific track in the playlist.
     *
     * When playback reaches a clip's start time, connected controllers are
     * notified via CLIP_CHANGED broadcast, enabling text highlighting even
     * when the UI was disconnected and reconnected.
     *
     * @param trackIndex The index of the track in the playlist
     * @param clips The clips to schedule for this track
     */
    fun scheduleClipsForTrack(trackIndex: Int, clips: List<SchedulableClip>) {
        val currentPlayer = player ?: return
        clipScheduler.scheduleClipsForTrack(currentPlayer, trackIndex, clips)
    }

    /**
     * Clears all scheduled clips.
     * Called when switching books or stopping playback.
     */
    fun clearScheduledClips() {
        clipScheduler.clearScheduledClips()
    }

    // ==================== CLIP_CHANGED Broadcast ====================

    /**
     * Notifies all connected controllers about a clip change.
     * This is called when playback reaches a new clip boundary.
     */
    private fun notifyClipChanged(fragmentId: String?, textHref: String, startTime: Double, endTime: Double) {
        val session = mediaSession ?: return

        val bundle = Bundle().apply {
            putString(EXTRA_FRAGMENT_ID, fragmentId)
            putString(EXTRA_TEXT_HREF, textHref)
            putDouble(EXTRA_START_TIME, startTime)
            putDouble(EXTRA_END_TIME, endTime)
        }

        session.connectedControllers.forEach { controllerInfo ->
            session.sendCustomCommand(
                controllerInfo,
                SessionCommand(COMMAND_CLIP_CHANGED, Bundle.EMPTY),
                bundle,
            )
        }
    }

    // ==================== MediaLibrarySession.Callback ====================

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val defaultCommands = super.onConnect(session, controller)

            // Track Android Auto controllers for URI permission granting
            val librarySession = session as? MediaLibrarySession
            val isAutomotiveController = librarySession?.isAutomotiveController(controller) ?: false
            val isAutoCompanionController = librarySession?.isAutoCompanionController(controller) ?: false

            if (isAutomotiveController || isAutoCompanionController) {
                automotiveControllers.add(controller.packageName)
                Log.d(TAG, "Android Auto controller connected: ${controller.packageName}")

                // Grant artwork permissions for currently playing item
                player?.currentMediaItem?.mediaMetadata?.artworkUri?.let { artworkUri ->
                    grantArtworkUriPermissions(artworkUri)
                }
            }

            // Add seek commands for Bluetooth headset buttons
            val playerCommands = Player.Commands.Builder()
                .addAll(defaultCommands.availablePlayerCommands)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            // Add custom commands for CLIP_CHANGED and chapter navigation
            val sessionCommands = defaultCommands.availableSessionCommands.buildUpon()
                .add(SessionCommand(COMMAND_CLIP_CHANGED, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_PREVIOUS_CHAPTER, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY))
                .build()

            val resultBuilder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)

            // Create buttons for seek (primary) and chapter navigation (overflow)
            // Following the same pattern as official Storyteller app
            val allButtons = createMediaButtonPreferences()

            // Set button preferences for notification controller
            if (session.isMediaNotificationController(controller)) {
                Log.d(TAG, "Configuring buttons for media notification controller")
                resultBuilder.setMediaButtonPreferences(allButtons)
            }

            // Set button preferences for Android Auto controllers
            if (isAutomotiveController || isAutoCompanionController) {
                Log.d(TAG, "Configuring buttons for Android Auto controller")
                resultBuilder.setMediaButtonPreferences(allButtons)
            }

            return resultBuilder.build()
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            // Clean up automotive controller tracking
            if (automotiveControllers.remove(controller.packageName)) {
                Log.d(TAG, "Android Auto controller disconnected: ${controller.packageName}")
            }
            super.onDisconnected(session, controller)
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            // Intercept next/previous commands for chapter navigation
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> {
                    serviceScope.launch { navigateToNextChapter() }
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> {
                    serviceScope.launch { navigateToPreviousChapter() }
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            Log.d(TAG, "onCustomCommand: ${customCommand.customAction}")
            when (customCommand.customAction) {
                COMMAND_PREVIOUS_CHAPTER -> {
                    serviceScope.launch { navigateToPreviousChapter() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_NEXT_CHAPTER -> {
                    serviceScope.launch { navigateToNextChapter() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    // Next/Previous -> Chapter navigation
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        serviceScope.launch { navigateToNextChapter() }
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        serviceScope.launch { navigateToPreviousChapter() }
                        return true
                    }
                    // Fast-forward/Rewind -> 10 second seek
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        seekForward()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        seekBackward()
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        // ==================== Android Auto Browsing ====================

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot: browser=${browser.packageName}")
            val rootItem = autoMediaBrowser.getRootItem()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize")

            // Use SettableFuture to bridge coroutines and ListenableFuture
            val future = com.google.common.util.concurrent.SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                try {
                    val children = autoMediaBrowser.getChildren(parentId)
                    val result = if (children != null) {
                        Log.d(TAG, "onGetChildren: returning ${children.size} children")
                        LibraryResult.ofItemList(ImmutableList.copyOf(children), null)
                    } else {
                        Log.d(TAG, "onGetChildren: parentId not recognized")
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                    future.set(result)
                } catch (e: Exception) {
                    Log.e(TAG, "onGetChildren error", e)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }

            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items, first=${mediaItems.firstOrNull()?.mediaId}")

            val mediaId = mediaItems.firstOrNull()?.mediaId
            if (mediaId == null) {
                Log.w(TAG, "onAddMediaItems: No mediaId in items")
                return Futures.immediateFuture(mutableListOf())
            }

            val parsed = AutoMediaIds.parseBookId(mediaId)
            if (parsed == null) {
                Log.w(TAG, "onAddMediaItems: Failed to parse mediaId: $mediaId")
                return Futures.immediateFuture(mutableListOf())
            }

            val (parsedServerId, parsedBookUuid) = parsed
            Log.d(TAG, "onAddMediaItems: Starting headless playback for server=$parsedServerId, book=$parsedBookUuid")

            // Start headless playback asynchronously
            serviceScope.launch {
                startHeadlessPlayback(parsedServerId, parsedBookUuid)
            }

            // Return EMPTY list - the incoming mediaItems have no URI and would crash Media3.
            // Headless playback sets up the player with proper media items internally.
            return Futures.immediateFuture(mutableListOf())
        }
    }

    // ==================== Headless Playback (Android Auto) ====================

    /**
     * Starts headless playback for a book selected in Android Auto.
     * This allows the user to play audio from their car without opening the phone app.
     */
    private suspend fun startHeadlessPlayback(serverId: String, bookUuid: String) {
        Log.d(TAG, "startHeadlessPlayback: serverId=$serverId, bookUuid=$bookUuid")

        // Android Auto uses the shared service, which may still be configured for TTS.
        prepareForMediaOverlayPlayback()

        // Close any existing headless session
        activeHeadlessSession?.let { session ->
            Log.d(TAG, "startHeadlessPlayback: Closing existing session")
            session.close()
            activeHeadlessSession = null
        }

        val exoPlayer = player
        if (exoPlayer == null) {
            Log.e(TAG, "startHeadlessPlayback: ExoPlayer not initialized")
            return
        }

        // Create new headless session via factory
        val session = headlessSessionFactory.createSession(
            serverId = serverId,
            bookUuid = bookUuid,
            exoPlayer = exoPlayer,
        )

        if (session == null) {
            Log.e(TAG, "startHeadlessPlayback: Failed to create session")
            return
        }

        activeHeadlessSession = session
        Log.d(TAG, "startHeadlessPlayback: Session created, starting playback")

        // Register this book as currently playing for NowPlayingProvider
        controller.setCurrentPlayingBook(
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.READALOUD,
            bookTitle = session.bookTitle,
            coverUrl = session.coverUrl,
        )

        // Start playback
        session.play()
    }

    /**
     * Closes the active headless session if one exists.
     */
    private fun closeHeadlessSession() {
        activeHeadlessSession?.let { session ->
            Log.d(TAG, "closeHeadlessSession: Closing active session")
            session.close()
            activeHeadlessSession = null
            // Clear now playing info since headless playback stopped
            controller.clearCurrentPlayingBook()
        }
    }

    private fun seekForward() {
        if (playbackContentType == PlaybackContentType.TTS) {
            sessionPlayer?.seekForward()
            return
        }
        val currentPlayer = player ?: return
        val newPosition = (currentPlayer.currentPosition + SEEK_INCREMENT_MS)
            .coerceAtMost(currentPlayer.duration.coerceAtLeast(0L))
        currentPlayer.seekTo(newPosition)
    }

    private fun seekBackward() {
        if (playbackContentType == PlaybackContentType.TTS) {
            sessionPlayer?.seekBack()
            return
        }
        val currentPlayer = player ?: return
        val newPosition = (currentPlayer.currentPosition - SEEK_INCREMENT_MS)
            .coerceAtLeast(0L)
        currentPlayer.seekTo(newPosition)
    }

    // ==================== Chapter Navigation Methods ====================

    /**
     * Navigates to the next chapter.
     * For headless playback (Android Auto), uses the headless session.
     * For normal playback, signals the controller to navigate.
     */
    private suspend fun navigateToNextChapter() {
        Log.d(TAG, "navigateToNextChapter()")
        if (playbackContentType == PlaybackContentType.TTS) {
            controller.requestNextTtsSentence()
            return
        }
        val headless = activeHeadlessSession
        if (headless != null) {
            headless.skipToNextChapter()
        } else {
            controller.requestNextChapter()
        }
    }

    /**
     * Navigates to the previous chapter.
     * For headless playback (Android Auto), uses the headless session.
     * For normal playback, signals the controller to navigate.
     */
    private suspend fun navigateToPreviousChapter() {
        Log.d(TAG, "navigateToPreviousChapter()")
        if (playbackContentType == PlaybackContentType.TTS) {
            controller.requestPreviousTtsSentence()
            return
        }
        val headless = activeHeadlessSession
        if (headless != null) {
            headless.skipToPreviousChapter()
        } else {
            controller.requestPreviousChapter()
        }
    }

    // ==================== Position Tracking Methods ====================

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = serviceScope.launch {
            while (isActive) {
                player?.let { p ->
                    _currentPosition.update { currentChapterPosition(p) }
                    updateCurrentLocator()
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun updatePlaybackStateInternal(playerState: Int, isPlaying: Boolean) {
        _playbackState.value = when {
            isPlaying -> PlaybackState.PLAYING
            playerState == Player.STATE_BUFFERING -> PlaybackState.BUFFERING
            playerState == Player.STATE_ENDED -> PlaybackState.STOPPED
            playerState == Player.STATE_IDLE -> PlaybackState.STOPPED
            else -> PlaybackState.PAUSED
        }
    }

    private fun updateCurrentLocator() {
        val currentPosition = player?.currentPosition ?: return
        val currentTimeSeconds = currentPosition / SECONDS_TO_MS

        val currentClip = findClipAtTime(currentTimeSeconds)

        if (currentClip != null && currentClip.fragmentId != null) {
            val locator = Locator(
                href = currentClip.textHref,
                mediaType = MediaType.XHTML,
                locations = Locator.Locations(
                    fragments = listOf(currentClip.fragmentId),
                ),
            )
            val currentFragments = _currentLocatorWithClip.value?.locator?.locations?.fragments
            if (currentFragments != locator.locations.fragments) {
                Log.d(TAG, "SERVICE updateLocator: fragment=${currentClip.fragmentId}")
                _currentLocatorWithClip.value = LocatorWithClip(locator, currentClip)
            }
        } else {
            checkChapterExceeded(currentTimeSeconds)
        }
    }

    private fun checkChapterExceeded(currentTimeSeconds: Double) {
        if (hasNotifiedChapterExceeded) return

        val clipsToCheck = currentAudioFileClips.ifEmpty { currentChapterClips }
        if (clipsToCheck.isEmpty()) return

        val lastClipEndTime = clipsToCheck.lastOrNull()?.endTime ?: return

        val thresholdSeconds = 0.2
        if (currentTimeSeconds > lastClipEndTime + thresholdSeconds) {
            hasNotifiedChapterExceeded = true
            player?.pause()
            _chapterAudioCompleted.tryEmit(Unit)
            onChapterClipsExceeded?.invoke()
        }
    }

    private fun findClipAtTime(timeSeconds: Double): MediaOverlayClip? {
        val clipsToSearch = currentAudioFileClips.ifEmpty { currentChapterClips }
        if (clipsToSearch.isEmpty()) return null

        var low = 0
        var high = clipsToSearch.size - 1

        while (low <= high) {
            val mid = (low + high) / 2
            val clip = clipsToSearch[mid]

            when {
                timeSeconds < clip.startTime -> high = mid - 1
                timeSeconds >= clip.endTime -> low = mid + 1
                else -> return clip
            }
        }

        return null
    }

    // ==================== Public State Management Methods ====================

    /** Sets the chapter clips for position tracking. */
    fun setChapterClips(clips: List<MediaOverlayClip>) {
        Log.d(TAG, "SERVICE setChapterClips: count=${clips.size}")
        currentChapterClips = clips
        currentAudioHref = null
        currentAudioFileClips = emptyList()
        hasNotifiedChapterExceeded = false
    }

    /** Gets the current chapter clips. */
    fun getChapterClips(): List<MediaOverlayClip> = currentChapterClips

    /** Sets the chapter start offset for position normalization. */
    fun setChapterStartOffset(offsetMs: Long) {
        _chapterStartOffset.value = offsetMs
    }

    /** Sets the current audio file href for clip filtering. */
    fun setCurrentAudioHref(audioHref: Url) {
        if (audioHref != currentAudioHref) {
            currentAudioHref = audioHref
            currentAudioFileClips = currentChapterClips.filter { it.audioHref == audioHref }
        }
    }

    /** Converts a normalized position back to raw ExoPlayer position. */
    fun normalizedToRawPosition(normalizedPositionMs: Long): Long {
        return normalizedPositionMs + _chapterStartOffset.value
    }

    /** Sets the total duration (from SMIL clips). */
    fun setTotalDuration(durationMs: Long) {
        _totalDuration.value = durationMs
    }

    /** Emits a chapter completion event. */
    fun emitChapterCompleted() {
        _chapterAudioCompleted.tryEmit(Unit)
    }

    /** Sets the playing state immediately (for optimistic updates). */
    fun setPlayingState(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    /** Sets the playback state immediately. */
    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
    }

    /** Finds the clip for a given text fragment ID. */
    fun findClipForFragment(fragmentId: String?): MediaOverlayClip? {
        return fragmentId?.let {
            currentChapterClips.find { clip -> clip.fragmentId == it }
        }
    }

    /** Finds the audio position for a given fragment ID. */
    fun findPositionForFragment(fragmentId: String?): Long? {
        return findClipForFragment(fragmentId)
            ?.let { clip -> (clip.startTime * SECONDS_TO_MS).toLong() }
    }

    /** Finds the audio position for a given text progression. */
    fun findPositionForProgression(progression: Double?): Long? {
        if (progression == null || progression <= 0.0 || currentChapterClips.isEmpty()) return null
        val clipIndex = (progression * currentChapterClips.size).toInt()
            .coerceIn(0, currentChapterClips.size - 1)
        val clip = currentChapterClips[clipIndex]
        return (clip.startTime * SECONDS_TO_MS).toLong()
    }

    /** Updates position for a fragment and optionally seeks. */
    fun updatePositionForFragment(fragmentId: String, skipSeek: Boolean = false): MediaOverlayClip? {
        val matchingClip = findClipForFragment(fragmentId)
        val positionMs = matchingClip?.let { (it.startTime * SECONDS_TO_MS).toLong() }
        if (positionMs != null) {
            if (skipSeek) {
                _currentPosition.value = positionMs
            } else {
                _currentPosition.value = positionMs
                player?.seekTo(positionMs)
            }
        }
        return matchingClip
    }

    /** Sets the initial position. */
    fun setInitialPosition(positionMs: Long) {
        _currentPosition.value = positionMs
        player?.seekTo(positionMs)
    }

    /** Forces an update of the current position and locator. */
    fun forceUpdatePosition() {
        player?.let { p ->
            _currentPosition.value = currentChapterPosition(p)
            updateCurrentLocator()
        }
    }

    private fun currentChapterPosition(playbackPlayer: ExoPlayer): Long {
        if (playbackContentType != PlaybackContentType.TTS) {
            return playbackPlayer.currentPosition
        }

        val timeline = ttsSessionTimeline ?: return playbackPlayer.currentPosition
        return timeline.chapterTimeline.chapterPositionMs(
            sentenceIndex = timeline.sentenceIndex,
            sentencePositionMs = playbackPlayer.currentPosition,
            sentenceDurationMs = playbackPlayer.duration,
        )
    }

    companion object {
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val POSITION_UPDATE_INTERVAL_MS = 100L
        private const val SECONDS_TO_MS = 1000.0
        private const val TTS_MEDIA_ID_PREFIX = "tts:"

        // Custom session command for clip changes
        const val COMMAND_CLIP_CHANGED = "com.retro99.CLIP_CHANGED"
        const val EXTRA_FRAGMENT_ID = "fragmentId"
        const val EXTRA_TEXT_HREF = "textHref"
        const val EXTRA_START_TIME = "startTime"
        const val EXTRA_END_TIME = "endTime"

        // Custom session commands for chapter navigation
        const val COMMAND_PREVIOUS_CHAPTER = "com.retro99.PREVIOUS_CHAPTER"
        const val COMMAND_NEXT_CHAPTER = "com.retro99.NEXT_CHAPTER"
    }
}
