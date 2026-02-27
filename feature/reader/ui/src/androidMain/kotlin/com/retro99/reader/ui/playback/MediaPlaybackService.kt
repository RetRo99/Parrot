package com.retro99.reader.ui.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retro99.base.deeplink.DeepLinkUriBuilder
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.media.DynamicPublicationDataSourceFactory
import com.retro99.reader.ui.playback.auto.BooksMediaProvider
import com.retro99.reader.ui.playback.auto.HeadlessPlaybackSession
import com.retro99.reader.ui.playback.auto.HeadlessSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import org.koin.android.ext.android.inject

private const val TAG = "MediaPlaybackService"

/**
 * Foreground service for audio playback with MediaSession and Android Auto support.
 *
 * This service:
 * - Owns the single ExoPlayer used for ALL playback (in-app and Android Auto)
 * - Runs as a foreground service with a persistent notification
 * - Manages the MediaLibrarySession for system integration (lockscreen, Bluetooth, etc.)
 * - Provides content browsing for Android Auto via MediaLibrarySession
 * - Handles audio focus and system audio policy
 *
 * ## Architecture Notes
 *
 * This service owns ONE ExoPlayer and ONE MediaLibrarySession that handles:
 * - In-app playback (MediaOverlayPlayer gets player reference via MediaPlaybackController)
 * - Android Auto browsing (content hierarchy via LibrarySessionCallback)
 * - Android Auto playback (HeadlessPlaybackSession uses the same player)
 *
 * This consolidated architecture ensures Android Auto can observe the actual
 * playback state, since the session's player IS the playback player.
 */
@UnstableApi
class MediaPlaybackService : MediaLibraryService() {

    private val controller: MediaPlaybackController by inject()
    private val booksProvider: BooksMediaProvider by inject()
    private val sessionFactory: HeadlessSessionFactory by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Active headless playback session for Android Auto.
     * Only one book can be playing at a time via Android Auto.
     */
    private var activePlaybackSession: HeadlessPlaybackSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Dynamic DataSource.Factory that can be updated with the current Publication.
     * This is used by ExoPlayer to read audio from EPUB containers.
     * Must be set before playback of each book.
     */
    private val dynamicDataSourceFactory = DynamicPublicationDataSourceFactory()

    /**
     * The single ExoPlayer owned by this service.
     * This player is used for ALL playback - both in-app and Android Auto.
     */
    private var player: ExoPlayer? = null

    /**
     * The single MediaLibrarySession that handles both browsing AND playback.
     * Android Auto observes this session's player for playback state.
     */
    private var mediaSession: MediaLibrarySession? = null

    /**
     * Timeout duration for stopping the service after task removal when paused.
     * 30 minutes is a reasonable balance between allowing resume and saving battery.
     */
    private val taskRemovedTimeoutMs = 30 * 60 * 1000L // 30 minutes

    /**
     * Tracks the last known playing state for MediaSession callbacks.
     * Used to reliably determine if a PLAY_PAUSE command is pause or play.
     */
    private var wasPlaying: Boolean = false
    private val stateLock = Any()

    private val stopSelfRunnable = Runnable {
        // Only stop if still paused - if user resumed, the listener will have cancelled this
        val p = player
        if (p == null || !p.isPlaying) {
            stopSelf()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
            synchronized(stateLock) {
                wasPlaying = isPlaying
            }
            if (isPlaying) {
                // User resumed playback - cancel any pending stop
                handler.removeCallbacks(stopSelfRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            val p = player
            Log.d(TAG, "onPlaybackStateChanged: state=$stateStr, playWhenReady=${p?.playWhenReady}, volume=${p?.volume}, deviceVolume=${p?.deviceVolume}")
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}", error)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Create the single ExoPlayer for all playback
        createPlayerAndSession()
    }

    /**
     * Creates the single ExoPlayer and MediaLibrarySession.
     *
     * The ExoPlayer is used for ALL playback (in-app and Android Auto).
     * The MediaLibrarySession handles both browsing AND playback.
     */
    private fun createPlayerAndSession() {
        if (player != null) return

        // Audio attributes for speech content (audiobooks/read-aloud)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        Log.d(TAG, "Creating ExoPlayer with audio attributes: usage=${audioAttributes.usage}, contentType=${audioAttributes.contentType}")

        // Create MediaSourceFactory that uses our dynamic DataSource for EPUB audio
        // This allows MediaSession's setMediaItems() calls to use the correct Publication
        val mediaSourceFactory = DefaultMediaSourceFactory(dynamicDataSourceFactory)

        // Create the single ExoPlayer with proper audio attributes
        // Using handleAudioFocus=true lets ExoPlayer manage audio focus, which properly
        // activates the audio session and routes audio to the correct output
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(audioAttributes, true) // true = ExoPlayer handles audio focus
            .build()
            .also {
                it.addListener(playerListener)
                Log.d(TAG, "ExoPlayer created with dynamic MediaSourceFactory: volume=${it.volume}, deviceVolume=${it.deviceVolume}")
            }

        // Create seek backward button (10 seconds)
        val seekBackwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Seek back 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()

        // Create seek forward button (10 seconds)
        val seekForwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Seek forward 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        // Create session activity (notification tap intent)
        val sessionActivityIntent = createSessionActivityIntent()

        mediaSession = MediaLibrarySession.Builder(this, player!!, LibrarySessionCallback())
            .setMediaButtonPreferences(ImmutableList.of(seekBackwardButton, seekForwardButton))
            .setSessionActivity(sessionActivityIntent)
            .build()
            .also { addSession(it) }

        // Notify the controller that service is ready with player and session
        controller.onServiceCreated(this, player!!, mediaSession!!)
    }

    /**
     * Creates a PendingIntent that launches the app when the notification is tapped.
     * Uses book info from controller if available for deep linking.
     */
    private fun createSessionActivityIntent(): PendingIntent {
        val bookInfo = controller.getBookInfo()

        val intent = if (bookInfo != null) {
            val (serverId, bookUuid, bookType) = bookInfo
            val deepLinkUri = DeepLinkUriBuilder.buildReaderUri(serverId, bookUuid, bookType.value)
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

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Updates the session activity PendingIntent with new book information.
     * Called by MediaPlaybackController when book info changes.
     */
    fun updateSessionActivity(serverId: String, bookUuid: String, bookType: BookType) {
        mediaSession?.let { session ->
            val deepLinkUri = DeepLinkUriBuilder.buildReaderUri(serverId, bookUuid, bookType.value)
            val intent = Intent(Intent.ACTION_VIEW, deepLinkUri.toUri()).apply {
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            session.setSessionActivity(pendingIntent)
        }
    }

    companion object {
        /** Seek increment in milliseconds (10 seconds) */
        private const val SEEK_INCREMENT_MS = 10_000L
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // Return the single session that handles both browsing and playback
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the service alive if there's any media loaded, even if paused.
        // This allows users to resume playback from the notification after
        // swiping the app from recents - standard behavior for audiobook/podcast apps.
        //
        // Only stop if there's truly nothing to play (no media items loaded).
        val p = player
        if (p == null || p.mediaItemCount == 0) {
            stopSelf()
            return
        }

        // If paused with content loaded, schedule a timeout to stop the service
        // This prevents indefinite battery drain if user forgets about the notification
        if (!p.isPlaying) {
            handler.postDelayed(stopSelfRunnable, taskRemovedTimeoutMs)
        }
    }

    override fun onDestroy() {
        // Clean up handler and listener
        handler.removeCallbacks(stopSelfRunnable)
        player?.removeListener(playerListener)

        // Close active headless playback session (saves position)
        activePlaybackSession?.close()
        activePlaybackSession = null

        // Release session and player
        mediaSession?.release()
        player?.release()

        serviceScope.cancel()
        controller.onServiceDestroyed()
        super.onDestroy()
    }

    /**
     * Seeks forward by 10 seconds from the current position.
     */
    private fun seekForward() {
        player?.let { p ->
            val newPosition = (p.currentPosition + SEEK_INCREMENT_MS)
                .coerceAtMost(p.duration.coerceAtLeast(0L))
            p.seekTo(newPosition)
        }
    }

    /**
     * Seeks backward by 10 seconds from the current position.
     */
    private fun seekBackward() {
        player?.let { p ->
            val newPosition = (p.currentPosition - SEEK_INCREMENT_MS)
                .coerceAtLeast(0L)
            p.seekTo(newPosition)
        }
    }

    /**
     * Callback for handling Android Auto content browsing and media button events.
     *
     * This callback:
     * - Handles content browsing for Android Auto (onGetLibraryRoot, onGetChildren, etc.)
     * - Intercepts seek commands from Bluetooth headsets and converts next/previous to seek
     * - Handles media button key events
     */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        /**
         * Called when a controller connects to the session.
         * Adds COMMAND_SEEK_TO_NEXT and COMMAND_SEEK_TO_PREVIOUS to enable Bluetooth headset buttons.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val defaultCommands = super.onConnect(session, controller)

            val playerCommands = Player.Commands.Builder()
                .addAll(defaultCommands.availablePlayerCommands)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(defaultCommands.availableSessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        /**
         * Intercepts player commands to convert next/previous track to seek operations.
         */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            // Intercept next/previous track commands and convert to 10-second seek
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> {
                    seekForward()
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }

                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> {
                    seekBackward()
                    return SessionResult.RESULT_ERROR_NOT_SUPPORTED
                }
            }

            return SessionResult.RESULT_SUCCESS
        }

        /**
         * Handles raw media button key events from Bluetooth headsets.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    -> {
                        seekForward()
                        return true
                    }

                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    -> {
                        seekBackward()
                        return true
                    }
                }
            }

            return super.onMediaButtonEvent(session, controller, intent)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(BooksMediaProvider.ROOT_ID)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when (parentId) {
                    BooksMediaProvider.ROOT_ID -> booksProvider.getRootItems()
                    BooksMediaProvider.READALOUD_BOOKS_ID -> booksProvider.getReadaloudBooks()
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future {
                if (mediaId.startsWith("book:")) {
                    val books = booksProvider.getReadaloudBooks()
                    val item = books.find { it.mediaId == mediaId }
                    if (item != null) {
                        LibraryResult.ofItem(item, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            }
        }

        /**
         * Handle book selection from Android Auto.
         *
         * Android Auto calls this method (via MediaControllerCompat.prepareFromMediaId)
         * when the user selects a playable item. The MediaItem only contains the mediaId -
         * extras and other metadata are NOT preserved across the boundary.
         *
         * We parse the mediaId (format: "book:{serverId}:{bookUuid}") to extract
         * the book identifiers and start headless playback.
         *
         * This provides a hands-free experience appropriate for driving:
         * - Audio plays directly without requiring phone interaction
         * - Position is restored from where user left off
         * - Position is saved periodically during playback
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            Log.d(TAG, "▶▶▶ onAddMediaItems START at $startTime with ${mediaItems.size} items")

            return serviceScope.future {
                // Find the first book item and start playback
                mediaItems.firstOrNull { item ->
                    val mediaId = item.mediaId
                    Log.d(TAG, "  [+${System.currentTimeMillis() - startTime}ms] Processing mediaId: $mediaId")
                    if (mediaId.startsWith("book:")) {
                        val parts = mediaId.split(":")
                        if (parts.size >= 3) {
                            val serverId = parts[1]
                            val bookUuid = parts.drop(2).joinToString(":") // Handle UUIDs with colons
                            Log.d(TAG, "  [+${System.currentTimeMillis() - startTime}ms] → Starting headless playback")
                            startHeadlessPlayback(serverId, bookUuid)
                            true
                        } else {
                            Log.w(TAG, "  Invalid mediaId format: $mediaId")
                            false
                        }
                    } else {
                        false
                    }
                }

                Log.d(TAG, "◀◀◀ onAddMediaItems END total=${System.currentTimeMillis() - startTime}ms")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")

                // Return the MediaItems that ExoPlayer now has (with proper URIs from preparePlaylist).
                // We can't return the original mediaItems because they have no URI, causing NPE.
                // We can't return emptyList() because MediaSession will think nothing was added and end playback.
                val p = player
                if (p != null && p.mediaItemCount > 0) {
                    val items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
                    Log.d(TAG, "Returning ${items.size} MediaItems from ExoPlayer")
                    items
                } else {
                    Log.w(TAG, "No MediaItems in ExoPlayer, returning empty list")
                    emptyList()
                }
            }
        }

        private suspend fun startHeadlessPlayback(serverId: String, bookUuid: String) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "  ┌─── startHeadlessPlayback BEGIN ───")
            Log.d(TAG, "  │ server=$serverId, book=$bookUuid")

            // Log current state before switching
            val existingSession = activePlaybackSession
            val currentPlayer = player
            Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Current state:")
            Log.d(TAG, "  │   - existingSession: ${existingSession != null} (book=${existingSession?.bookUuid})")
            Log.d(TAG, "  │   - player: ${currentPlayer != null}")
            if (currentPlayer != null) {
                Log.d(TAG, "  │   - player.isPlaying: ${currentPlayer.isPlaying}")
                Log.d(TAG, "  │   - player.playbackState: ${currentPlayer.playbackState}")
                Log.d(TAG, "  │   - player.currentMediaItem: ${currentPlayer.currentMediaItem?.mediaId}")
            }

            // Close any existing session first
            if (existingSession != null) {
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Closing existing session for book=${existingSession.bookUuid}")
                existingSession.close()
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Existing session closed")
            }
            activePlaybackSession = null

            // Stop the player but DON'T clear media items
            // Clearing media items causes MediaSession to broadcast null metadata to Android Auto,
            // which then gets cached and shows the old book. setMediaSources() in preparePlaylist()
            // will atomically replace the content with the new book's audio.
            currentPlayer?.let { p ->
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Stopping player (keeping media items for metadata)")
                p.stop()
                // Don't call clearMediaItems() - let setMediaSources() replace content atomically
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Player stopped")
            }

            // Get the service's ExoPlayer
            val p = player
            if (p == null) {
                Log.e(TAG, "  │ ERROR: player is null!")
                Log.d(TAG, "  └─── startHeadlessPlayback END (FAILED) ───")
                return
            }

            // Create new headless playback session using the service's player
            // Pass the dynamic data source factory so it gets updated with this book's Publication
            Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Creating session...")
            val sessionCreateStart = System.currentTimeMillis()
            val session = sessionFactory.createSession(serverId, bookUuid, p, dynamicDataSourceFactory)
            val sessionCreateTime = System.currentTimeMillis() - sessionCreateStart
            Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Session created in ${sessionCreateTime}ms (success=${session != null})")

            if (session != null) {
                activePlaybackSession = session
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Calling session.play()...")
                val playStart = System.currentTimeMillis()
                session.play()
                val playTime = System.currentTimeMillis() - playStart
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] session.play() done in ${playTime}ms")

                // Log final player state after play
                Log.d(TAG, "  │ [+${System.currentTimeMillis() - startTime}ms] Final player state:")
                Log.d(TAG, "  │   - player.isPlaying: ${p.isPlaying}")
                Log.d(TAG, "  │   - player.playbackState: ${p.playbackState}")
                Log.d(TAG, "  │   - player.mediaItemCount: ${p.mediaItemCount}")
                Log.d(TAG, "  │   - player.currentMediaItem: ${p.currentMediaItem?.mediaId}")
                Log.d(TAG, "  │   - activeSession.bookUuid: ${activePlaybackSession?.bookUuid}")
            } else {
                Log.e(TAG, "  │ ERROR: Failed to create headless session")
            }

            Log.d(TAG, "  └─── startHeadlessPlayback END total=${System.currentTimeMillis() - startTime}ms ───")
        }
    }
}

