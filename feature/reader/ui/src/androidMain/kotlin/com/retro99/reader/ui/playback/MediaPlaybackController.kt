package com.retro99.reader.ui.playback

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.media.MediaOverlayClip
import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.PlaybackState
import com.retro99.reader.ui.tts.TtsChapterTimeline
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import org.readium.r2.shared.util.Url

private const val TAG = "MediaPlaybackController"

/**
 * Identifies a book currently playing for reconnection and mini-player display.
 */
data class PlayingBookInfo(
    val serverId: String,
    val bookUuid: String,
    val bookType: BookType,
    val bookTitle: String? = null,
    val coverUrl: String? = null,
)

/**
 * Holds now-playing metadata set before playback starts.
 * Applied when setCurrentPlayingBook() is called.
 */
private data class PendingNowPlayingInfo(
    val bookUuid: String,
    val bookTitle: String,
    val coverUrl: String?,
)

/**
 * Koin-managed controller for media playback state.
 *
 * This class acts as a bridge between [MediaPlaybackService] (which owns the ExoPlayer
 * and MediaLibrarySession) and UI components like [MediaOverlayPlayer].
 *
 * ## Architecture (Service-Centric)
 *
 * The [MediaPlaybackService] owns both ExoPlayer and MediaLibrarySession. When the service
 * is created, it passes both to this controller via [onServiceCreated]. UI components
 * access these via [currentPlayer] and [currentSession].
 *
 * This architecture allows playback to continue when the user leaves the reader screen:
 * - The ReaderScope is destroyed, but the service continues running with player/session
 * - When the user re-enters the same book, we detect this via [currentPlayingBook]
 *   and reconnect to the existing playback instead of starting fresh
 *
 * ## Thread Safety
 *
 * All public methods use synchronized blocks to ensure that compound operations
 * (read + write + side effect) are atomic.
 */
@Single
class MediaPlaybackController {

    private val lock = Any()

    // The ExoPlayer instance owned by MediaPlaybackService
    // Guarded by lock
    private var _player: ExoPlayer? = null

    // The MediaLibrarySession owned by MediaPlaybackService
    // Guarded by lock
    private var _session: MediaLibrarySession? = null

    // Guarded by lock
    private var _serviceInstance: MediaPlaybackService? = null

    // Track which book is currently playing for reconnection
    // Guarded by lock
    private var _currentPlayingBook: PlayingBookInfo? = null

    // Pending now-playing info set before playback starts
    // Applied when setCurrentPlayingBook() is called
    // Guarded by lock
    private var _pendingNowPlayingInfo: PendingNowPlayingInfo? = null

    // Deferred that completes when service is ready (onCreate called)
    // Guarded by lock
    private var _serviceReadyDeferred: CompletableDeferred<Unit>? = null

    // Scope for forwarding service flows to proxy flows
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var forwardingJob: Job? = null

    // Notified just before the service releases its player; not guarded by lock, since
    // the callbacks reach back into their own components.
    private val serviceDestroyedListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * The ExoPlayer instance owned by the service.
     * Returns null if the service is not running.
     */
    val currentPlayer: ExoPlayer?
        get() = synchronized(lock) { _player }

    /**
     * The MediaLibrarySession owned by the service.
     * Returns null if the service is not running.
     */
    val currentSession: MediaSession?
        get() = synchronized(lock) { _session }

    /**
     * The service instance, if running.
     */
    val serviceInstance: MediaPlaybackService?
        get() = synchronized(lock) { _serviceInstance }

    /**
     * Returns the currently playing book info, if any.
     * Used for reconnection when re-entering a book.
     */
    val currentPlayingBook: PlayingBookInfo?
        get() = synchronized(lock) { _currentPlayingBook }

    // ==================== Proxy State Flows ====================
    // These are persistent flows that UI components subscribe to.
    // Values are forwarded from the service when it's running.
    // This ensures subscribers always get the same flow instance, avoiding
    // the race condition where combine() captures a "dead" fallback flow.

    private val _isPlaying = MutableStateFlow(false)
    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    private val _totalDuration = MutableStateFlow<Long?>(null)
    private val _isPlayerReady = MutableStateFlow(false)
    private val _currentPosition = MutableStateFlow(0L)
    private val _normalizedPosition = MutableStateFlow(0L)
    private val _currentLocator = MutableStateFlow<AudioLocatorState?>(null)
    private val _chapterAudioCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _nowPlayingBook = MutableStateFlow<PlayingBookInfo?>(null)
    private val _nextChapterRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _previousChapterRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _nextTtsSentenceRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _previousTtsSentenceRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _ttsChapterPositionRequest = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    val totalDuration: StateFlow<Long?> = _totalDuration.asStateFlow()
    val isPlayerReady: StateFlow<Boolean> = _isPlayerReady.asStateFlow()
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    val normalizedPosition: StateFlow<Long> = _normalizedPosition.asStateFlow()
    val currentLocator: StateFlow<AudioLocatorState?> = _currentLocator.asStateFlow()
    val chapterAudioCompleted: Flow<Unit> = _chapterAudioCompleted
    val nowPlayingBook: StateFlow<PlayingBookInfo?> = _nowPlayingBook.asStateFlow()
    val nextChapterRequest: Flow<Unit> = _nextChapterRequest
    val previousChapterRequest: Flow<Unit> = _previousChapterRequest
    val nextTtsSentenceRequest: Flow<Unit> = _nextTtsSentenceRequest
    val previousTtsSentenceRequest: Flow<Unit> = _previousTtsSentenceRequest
    val ttsChapterPositionRequest: Flow<Long> = _ttsChapterPositionRequest

    // ==================== State Flow Setters (delegate to service) ====================

    fun setChapterClips(clips: List<MediaOverlayClip>) {
        synchronized(lock) { _serviceInstance?.setChapterClips(clips) }
    }

    fun prepareForTtsPlayback(
        bookTitle: String,
        chapterTitle: String?,
        coverArtwork: ByteArray?,
    ) {
        synchronized(lock) {
            _serviceInstance?.prepareForTtsPlayback(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                coverArtwork = coverArtwork,
            )
        }
    }

    fun prepareForMediaOverlayPlayback() {
        synchronized(lock) {
            _serviceInstance?.prepareForMediaOverlayPlayback()
        }
    }

    internal fun updateTtsChapterTimeline(
        chapterTimeline: TtsChapterTimeline,
        sentenceIndex: Int,
    ) {
        synchronized(lock) {
            _serviceInstance?.updateTtsChapterTimeline(
                chapterTimeline = chapterTimeline,
                sentenceIndex = sentenceIndex,
            )
        }
    }

    fun getChapterClips(): List<MediaOverlayClip> {
        return synchronized(lock) { _serviceInstance?.getChapterClips() ?: emptyList() }
    }

    fun setChapterStartOffset(offsetMs: Long) {
        synchronized(lock) { _serviceInstance?.setChapterStartOffset(offsetMs) }
    }

    fun setCurrentAudioHref(audioHref: Url) {
        synchronized(lock) { _serviceInstance?.setCurrentAudioHref(audioHref) }
    }

    fun normalizedToRawPosition(normalizedPositionMs: Long): Long {
        return synchronized(lock) {
            _serviceInstance?.normalizedToRawPosition(normalizedPositionMs) ?: normalizedPositionMs
        }
    }

    fun setTotalDuration(durationMs: Long) {
        // Always update the controller's state (for UI display)
        _totalDuration.value = durationMs
        // Also update the service if available
        synchronized(lock) { _serviceInstance?.setTotalDuration(durationMs) }
    }

    fun emitChapterCompleted() {
        synchronized(lock) { _serviceInstance?.emitChapterCompleted() }
    }

    fun setPlayingState(isPlaying: Boolean) {
        synchronized(lock) { _serviceInstance?.setPlayingState(isPlaying) }
    }

    fun setPlaybackState(state: PlaybackState) {
        synchronized(lock) { _serviceInstance?.setPlaybackState(state) }
    }

    fun findClipForFragment(fragmentId: String?): MediaOverlayClip? {
        return synchronized(lock) { _serviceInstance?.findClipForFragment(fragmentId) }
    }

    fun findPositionForFragment(fragmentId: String?): Long? {
        return synchronized(lock) { _serviceInstance?.findPositionForFragment(fragmentId) }
    }

    fun findPositionForProgression(progression: Double?): Long? {
        return synchronized(lock) { _serviceInstance?.findPositionForProgression(progression) }
    }

    fun updatePositionForFragment(fragmentId: String, skipSeek: Boolean = false): MediaOverlayClip? {
        return synchronized(lock) {
            _serviceInstance?.updatePositionForFragment(fragmentId, skipSeek)
        }
    }

    fun setInitialPosition(positionMs: Long) {
        Log.d(TAG, "setInitialPosition: positionMs=$positionMs")
        // Always update the controller's state (for UI display)
        // The saved audioTimestampMs is the normalized position (what the UI displays),
        // so we set both _currentPosition and _normalizedPosition to the same value.
        // startForwardingFlows() will protect these values from being overwritten by
        // the service's initial 0 emission.
        _currentPosition.value = positionMs
        _normalizedPosition.value = positionMs
        Log.d(TAG, "setInitialPosition: _currentPosition=${_currentPosition.value}, _normalizedPosition=${_normalizedPosition.value}")
        // Also update the service if available (will seek the player)
        synchronized(lock) { _serviceInstance?.setInitialPosition(positionMs) }
    }

    fun forceUpdatePosition() {
        synchronized(lock) { _serviceInstance?.forceUpdatePosition() }
    }

    /** Sets the callback for when chapter clips are exceeded. */
    fun setOnChapterClipsExceeded(callback: (() -> Unit)?) {
        synchronized(lock) { _serviceInstance?.onChapterClipsExceeded = callback }
    }

    /**
     * Returns true if the service is running with a valid player/session.
     */
    fun hasActiveSession(): Boolean {
        return synchronized(lock) { _player != null && _session != null }
    }

    /**
     * Checks if the given book is currently playing.
     * Used to determine if we should reconnect to existing playback.
     */
    fun isPlayingBook(bookUuid: String): Boolean {
        return synchronized(lock) {
            _currentPlayingBook?.bookUuid == bookUuid && _player?.isPlaying == true
        }
    }

    /**
     * Checks if the given book is loaded (even if paused).
     * Used to determine if we can resume existing playback.
     */
    fun isBookLoaded(bookUuid: String): Boolean {
        return synchronized(lock) {
            _currentPlayingBook?.bookUuid == bookUuid && _player?.mediaItemCount ?: 0 > 0
        }
    }

    /**
     * Sets the currently playing book info.
     * Called when playback starts for a book.
     *
     * If there's pending metadata from an earlier updateNowPlayingBookInfo() call,
     * it will be merged in (pending takes priority for title/coverUrl if the caller
     * didn't provide them).
     */
    fun setCurrentPlayingBook(
        serverId: String,
        bookUuid: String,
        bookType: BookType,
        bookTitle: String? = null,
        coverUrl: String? = null,
    ) {
        synchronized(lock) {
            // Check if there's pending metadata for this book
            val pending = _pendingNowPlayingInfo
            // Also check current info for the same book (preserves existing metadata)
            val current = _currentPlayingBook?.takeIf { it.bookUuid == bookUuid }

            // Priority: explicit param > pending > current existing value
            val finalTitle = bookTitle
                ?: pending?.takeIf { it.bookUuid == bookUuid }?.bookTitle
                ?: current?.bookTitle
            val finalCoverUrl = coverUrl
                ?: pending?.takeIf { it.bookUuid == bookUuid }?.coverUrl
                ?: current?.coverUrl

            val info = PlayingBookInfo(serverId, bookUuid, bookType, finalTitle, finalCoverUrl)
            _currentPlayingBook = info
            _nowPlayingBook.value = info

            // Clear pending info since we've applied it
            if (pending?.bookUuid == bookUuid) {
                _pendingNowPlayingInfo = null
            }

            // Also update the service metadata for deep links
            _serviceInstance?.updateMetadata(
                serverId = serverId,
                bookUuid = bookUuid,
                bookType = bookType,
            )
        }
    }

    /**
     * Clears the currently playing book info.
     * Called when playback is stopped.
     */
    fun clearCurrentPlayingBook() {
        synchronized(lock) {
            _currentPlayingBook = null
            _nowPlayingBook.value = null
        }
    }

    /**
     * Updates the now-playing book info with additional metadata.
     * Called when title and cover URL become available (e.g., from ReaderViewModel).
     *
     * If playback hasn't started yet (_currentPlayingBook is null), stores the info
     * as pending metadata to be applied when setCurrentPlayingBook() is called.
     * Otherwise, updates immediately if the bookUuid matches.
     */
    fun updateNowPlayingBookInfo(bookUuid: String, bookTitle: String, coverUrl: String?) {
        synchronized(lock) {
            val current = _currentPlayingBook
            if (current != null && current.bookUuid == bookUuid) {
                val updated = current.copy(bookTitle = bookTitle, coverUrl = coverUrl)
                _currentPlayingBook = updated
                _nowPlayingBook.value = updated
            } else {
                _pendingNowPlayingInfo = PendingNowPlayingInfo(bookUuid, bookTitle, coverUrl)
            }
        }
    }

    /**
     * Gets the current playback position in milliseconds.
     * Used for reconnection to restore UI state.
     */
    fun getCurrentPosition(): Long? {
        return synchronized(lock) { _player?.currentPosition }
    }

    /**
     * Checks if there's active media loaded (even if paused or stopped).
     * Used to determine if we can reconnect to existing playback.
     */
    fun hasLoadedMedia(): Boolean {
        return synchronized(lock) {
            _player?.mediaItemCount?.let { it > 0 } ?: false
        }
    }

    // ==================== Playback Control ====================

    /**
     * Pauses playback.
     * Called from mini-player or other UI components.
     */
    fun pause() {
        synchronized(lock) {
            _player?.pause()
        }
    }

    /**
     * Resumes playback.
     * Called from mini-player or other UI components.
     */
    fun play() {
        synchronized(lock) {
            _player?.play()
        }
    }

    /**
     * Stops playback and releases resources.
     * This will trigger service destruction if nothing else is keeping it alive.
     */
    fun stop() {
        synchronized(lock) {
            _player?.stop()
            _player?.clearMediaItems()
            _serviceInstance?.stopSelf()
        }
    }

    // ==================== Chapter Navigation ====================

    /**
     * Requests navigation to the next chapter.
     * Emits to [nextChapterRequest] flow which MediaOverlayPlayer listens to.
     */
    fun requestNextChapter() {
        Log.d(TAG, "requestNextChapter()")
        _nextChapterRequest.tryEmit(Unit)
    }

    /**
     * Requests navigation to the previous chapter.
     * Emits to [previousChapterRequest] flow which MediaOverlayPlayer listens to.
     */
    fun requestPreviousChapter() {
        Log.d(TAG, "requestPreviousChapter()")
        _previousChapterRequest.tryEmit(Unit)
    }

    fun requestNextTtsSentence() {
        _nextTtsSentenceRequest.tryEmit(Unit)
    }

    fun requestPreviousTtsSentence() {
        _previousTtsSentenceRequest.tryEmit(Unit)
    }

    fun requestTtsChapterPosition(positionMs: Long) {
        _ttsChapterPositionRequest.tryEmit(positionMs)
    }

    // ==================== Clip Scheduling ====================

    /**
     * Schedules clip callbacks for a specific track in the playlist.
     * Delegates to [MediaPlaybackService.scheduleClipsForTrack].
     *
     * @param trackIndex The index of the track in the playlist
     * @param clips The clips to schedule for this track
     */
    fun scheduleClipsForTrack(trackIndex: Int, clips: List<SchedulableClip>) {
        synchronized(lock) {
            _serviceInstance?.scheduleClipsForTrack(trackIndex, clips)
        }
    }

    /**
     * Clears all scheduled clips.
     * Called when switching books or stopping playback.
     */
    fun clearScheduledClips() {
        synchronized(lock) {
            _serviceInstance?.clearScheduledClips()
        }
    }

    /**
     * Called by [MediaPlaybackService] when it's created.
     * The service passes its owned player and session.
     */
    fun onServiceCreated(
        service: MediaPlaybackService,
        player: ExoPlayer,
        session: MediaLibrarySession,
    ) {
        Log.d(TAG, "onServiceCreated() called")
        val deferredToComplete: CompletableDeferred<Unit>?
        synchronized(lock) {
            _serviceInstance = service
            _player = player
            _session = session
            deferredToComplete = _serviceReadyDeferred
            Log.d(TAG, "onServiceCreated: _serviceReadyDeferred=$deferredToComplete")
        }
        // Start forwarding service flows to proxy flows
        startForwardingFlows(service)
        // Complete outside the lock to avoid potential deadlocks
        if (deferredToComplete != null) {
            Log.d(TAG, "onServiceCreated: completing deferred")
            deferredToComplete.complete(Unit)
        } else {
            Log.w(TAG, "onServiceCreated: NO deferred to complete!")
        }
    }

    /**
     * Called before the service's player is released, so whoever is still using that
     * player can let go of it instead of calling into a dead one (TTS-F32).
     */
    fun addOnServiceDestroyedListener(listener: () -> Unit) {
        serviceDestroyedListeners.addIfAbsent(listener)
    }

    fun removeOnServiceDestroyedListener(listener: () -> Unit) {
        serviceDestroyedListeners.remove(listener)
    }

    /**
     * Called by [MediaPlaybackService] when it's destroyed.
     */
    fun onServiceDestroyed() {
        Log.d(TAG, "onServiceDestroyed() called")
        // Before the player reference goes, so users of it hear about it while they can
        // still identify which player went away.
        serviceDestroyedListeners.forEach { listener -> listener() }
        stopForwardingFlows()
        synchronized(lock) {
            _serviceInstance = null
            _player = null
            _session = null
            _currentPlayingBook = null
            _serviceReadyDeferred = null
        }
    }

    /**
     * Start forwarding values from service flows to proxy flows.
     * This ensures UI components always receive updates regardless of when they subscribe.
     *
     * Note: Position flows have special handling to avoid a race condition where the service
     * emits 0 before seeking to the saved position. We capture the saved position values
     * BEFORE starting to forward, and skip any 0 emissions if we have saved values.
     */
    private fun startForwardingFlows(service: MediaPlaybackService) {
        Log.d(TAG, "startForwardingFlows() - before: _currentPosition=${_currentPosition.value}, _normalizedPosition=${_normalizedPosition.value}")
        forwardingJob?.cancel()
        forwardingJob = Job()

        val scope = CoroutineScope(controllerScope.coroutineContext + forwardingJob!!)

        // Capture saved position values BEFORE forwarding starts.
        // These were set by setInitialPosition() when the book opened.
        // We use these to skip the service's initial 0 emission.
        val savedCurrentPosition = _currentPosition.value
        val savedNormalizedPosition = _normalizedPosition.value

        service.isPlaying.onEach { _isPlaying.value = it }.launchIn(scope)
        service.playbackState.onEach { _playbackState.value = it }.launchIn(scope)
        service.totalDuration.onEach { _totalDuration.value = it }.launchIn(scope)
        service.isPlayerReady.onEach { _isPlayerReady.value = it }.launchIn(scope)
        service.currentPosition.onEach {
            // Skip forwarding 0 if we have a saved non-zero position.
            // This prevents the race condition where service emits 0 before seeking.
            if (it == 0L && savedCurrentPosition > 0L) {
                Log.d(TAG, "startForwardingFlows: skipping currentPosition 0 (saved=$savedCurrentPosition)")
                return@onEach
            }
            Log.d(TAG, "startForwardingFlows: currentPosition forwarding $it")
            _currentPosition.value = it
        }.launchIn(scope)
        service.normalizedPosition.onEach {
            // Skip forwarding 0 if we have a saved non-zero position.
            // This prevents the race condition where service emits 0 before seeking.
            if (it == 0L && savedNormalizedPosition > 0L) {
                Log.d(TAG, "startForwardingFlows: skipping normalizedPosition 0 (saved=$savedNormalizedPosition)")
                return@onEach
            }
            Log.d(TAG, "startForwardingFlows: normalizedPosition forwarding $it")
            _normalizedPosition.value = it
        }.launchIn(scope)
        service.currentLocator.onEach { _currentLocator.value = it }.launchIn(scope)
        service.chapterAudioCompleted.onEach { _chapterAudioCompleted.tryEmit(it) }.launchIn(scope)
    }

    /**
     * Stop forwarding and reset proxy flows to default values.
     */
    private fun stopForwardingFlows() {
        Log.d(TAG, "stopForwardingFlows()")
        forwardingJob?.cancel()
        forwardingJob = null

        // Reset proxy flows to default values
        _isPlaying.value = false
        _playbackState.value = PlaybackState.STOPPED
        _totalDuration.value = null
        _isPlayerReady.value = false
        _currentPosition.value = 0L
        _normalizedPosition.value = 0L
        _currentLocator.value = null
        _nowPlayingBook.value = null
    }

    /**
     * Prepares a deferred that will complete when the service is created.
     * Must be called BEFORE starting the service. Returns immediately if
     * the service is already running.
     */
    fun prepareServiceReady(): CompletableDeferred<Unit> {
        return synchronized(lock) {
            // If service is already running, return an already-completed deferred
            if (_player != null) {
                Log.d(TAG, "prepareServiceReady: service already running, returning completed deferred")
                CompletableDeferred(Unit)
            } else {
                // Reuse the existing deferred if one is already in-flight (e.g. double-tap)
                val existing = _serviceReadyDeferred
                if (existing != null && !existing.isCompleted) {
                    Log.d(TAG, "prepareServiceReady: reusing existing in-flight deferred")
                    existing
                } else {
                    // Create a new deferred to wait on
                    Log.d(TAG, "prepareServiceReady: creating new deferred")
                    val deferred = CompletableDeferred<Unit>()
                    _serviceReadyDeferred = deferred
                    deferred
                }
            }
        }
    }

    /**
     * Suspends until the service is ready, with a timeout.
     * Call [prepareServiceReady] BEFORE starting the service, then await
     * the returned deferred after starting the service.
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The ExoPlayer if service started successfully, null if timeout
     */
    suspend fun awaitServiceReady(
        deferred: CompletableDeferred<Unit>,
        timeoutMs: Long = SERVICE_READY_TIMEOUT_MS,
    ): ExoPlayer? {
        Log.d(TAG, "awaitServiceReady: waiting for deferred (timeout=${timeoutMs}ms)")
        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        val player = if (result != null) currentPlayer else null
        Log.d(TAG, "awaitServiceReady: result=${result != null}, player=${player != null}")
        return player
    }

    companion object {
        private const val SERVICE_READY_TIMEOUT_MS = 5000L
    }
}
