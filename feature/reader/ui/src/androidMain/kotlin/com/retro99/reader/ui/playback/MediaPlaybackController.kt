package com.retro99.reader.ui.playback

import android.app.PendingIntent
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.retro99.books.domain.model.BookType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

/**
 * Koin-managed controller for media playback state.
 *
 * This class acts as a bridge between [MediaPlaybackService] (created by Android)
 * and other components like [MediaOverlayPlayer].
 *
 * With the consolidated architecture, this controller:
 * - Provides access to the service's ExoPlayer (shared across all playback)
 * - Forwards metadata updates to the service's MediaLibrarySession
 * - Manages book info for notification deep links
 *
 * ## Thread Safety
 *
 * All public methods use synchronized blocks to ensure that compound operations
 * (read + write + side effect) are atomic.
 */
@Single
class MediaPlaybackController {

    private val lock = Any()

    // Flow to signal when the service is ready
    private val _serviceReady = MutableStateFlow(false)

    /**
     * Flow that emits true when the service is running and player is available.
     */
    val serviceReady: StateFlow<Boolean> = _serviceReady.asStateFlow()

    // Service instance - guarded by lock
    private var _serviceInstance: MediaPlaybackService? = null

    // Player owned by service - guarded by lock
    private var _player: ExoPlayer? = null

    // Session owned by service - guarded by lock
    private var _session: MediaLibrarySession? = null

    // Metadata state - guarded by lock
    private var _bookTitle: String = "Reading Aloud"
    private var _chapterTitle: String? = null
    private var _coverArtwork: ByteArray? = null

    // Book identification for deep link navigation
    private var _serverId: String? = null
    private var _bookUuid: String? = null
    private var _bookType: BookType? = null

    /**
     * Returns the ExoPlayer owned by the service.
     * Returns null if the service hasn't been created yet.
     */
    val player: ExoPlayer?
        get() = synchronized(lock) { _player }

    /**
     * Returns the MediaLibrarySession owned by the service.
     */
    val session: MediaLibrarySession?
        get() = synchronized(lock) { _session }

    /**
     * Returns true if the service is running and player is available.
     */
    fun isServiceRunning(): Boolean {
        return synchronized(lock) { _serviceInstance != null && _player != null }
    }

    /**
     * Called by [MediaPlaybackService] when it's created.
     * The service passes its player and session for other components to use.
     */
    fun onServiceCreated(
        service: MediaPlaybackService,
        player: ExoPlayer,
        session: MediaLibrarySession,
    ) {
        synchronized(lock) {
            _serviceInstance = service
            _player = player
            _session = session
        }
        _serviceReady.value = true
    }

    /**
     * Called by [MediaPlaybackService] when it's destroyed.
     */
    fun onServiceDestroyed() {
        synchronized(lock) {
            _serviceInstance = null
            _player = null
            _session = null
        }
        _serviceReady.value = false
    }

    /**
     * Suspends until the service is ready and player is available.
     * Use this after calling startService() to wait for the async service start to complete.
     */
    suspend fun awaitServiceReady() {
        _serviceReady.first { it }
    }

    /**
     * Sets the book identification for deep link navigation.
     *
     * This information is used to create a deep link URI when the notification is tapped,
     * allowing the app to navigate directly to the reader screen for this book.
     *
     * @param serverId The ID of the server the book belongs to
     * @param bookUuid The unique identifier of the book
     * @param bookType The type of book (EBOOK, AUDIOBOOK, or READALOUD)
     */
    fun setBookInfo(serverId: String, bookUuid: String, bookType: BookType) {
        synchronized(lock) {
            _serverId = serverId
            _bookUuid = bookUuid
            _bookType = bookType
            // Update the session activity if the session is already initialized
            _serviceInstance?.updateSessionActivity(serverId, bookUuid, bookType)
        }
    }

    /**
     * Updates the stored metadata for notifications and lockscreen.
     *
     * @param bookTitle The title of the book
     * @param chapterTitle Optional chapter title
     * @param coverArtwork Optional cover image as PNG byte array. Pass null to keep existing.
     */
    fun updateMetadata(
        bookTitle: String,
        chapterTitle: String? = null,
        coverArtwork: ByteArray? = null,
    ) {
        synchronized(lock) {
            _bookTitle = bookTitle
            _chapterTitle = chapterTitle
            if (coverArtwork != null) {
                _coverArtwork = coverArtwork
            }
        }
    }

    /**
     * Builds the current MediaMetadata based on stored book/chapter titles and cover.
     * Use this when creating a new MediaItem to ensure proper notification display.
     */
    fun buildCurrentMetadata(): MediaMetadata {
        synchronized(lock) {
            return MediaMetadata.Builder()
                .setTitle(_chapterTitle ?: _bookTitle)
                .setArtist(if (_chapterTitle != null) _bookTitle else "Parrot")
                .setDisplayTitle(_chapterTitle ?: _bookTitle)
                .apply {
                    _coverArtwork?.let { artwork ->
                        setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                }
                .build()
        }
    }

    /**
     * Gets the current book info for session activity creation.
     * Returns null if book info hasn't been set.
     */
    internal fun getBookInfo(): Triple<String, String, BookType>? {
        return synchronized(lock) {
            val server = _serverId
            val uuid = _bookUuid
            val type = _bookType
            if (server != null && uuid != null && type != null) {
                Triple(server, uuid, type)
            } else {
                null
            }
        }
    }
}

