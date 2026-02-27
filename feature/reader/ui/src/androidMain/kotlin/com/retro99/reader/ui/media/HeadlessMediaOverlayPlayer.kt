package com.retro99.reader.ui.media

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.media.smil.SmilClip
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.playback.auto.BookMetadata
import com.retro99.reader.ui.publication.EpubPublication
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

private const val TAG = "HeadlessMediaOverlay"

/** Conversion factor from seconds to milliseconds */
private const val SECONDS_TO_MS = 1000.0

/**
 * Simplified media overlay player for headless (Android Auto) playback.
 *
 * This player handles audio playback without UI dependencies:
 * - No notification permission handler (service handles notifications)
 * - No media session manager (service manages media session)
 * - No foreground service controller (service is already foreground)
 * - No locator tracker (no UI to highlight text)
 * - No playback state tracker (no UI to update)
 *
 * Core functionality:
 * - SMIL-based audio playback using ExoPlayer
 * - Chapter preparation and playlist management
 * - Seek forward/backward
 */
@OptIn(UnstableApi::class)
class HeadlessMediaOverlayPlayer(
    private val epubPublication: EpubPublication,
    private val analytics: Analytics,
    private val smilLoadingManager: SmilLoadingManager,
    private val exoPlayer: ExoPlayer,
    private val bookMetadata: BookMetadata?,
) {
    private val publication: Publication = epubPublication.publication

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Custom DataSource.Factory that reads audio from the EPUB container
    private val dataSourceFactory = PublicationDataSource.Factory(publication)

    // Current audio file being played (tracked by href)
    private var currentAudioHref: Url? = null

    // Playlist tracking: maps audio href to track index in ExoPlayer playlist
    private var audioHrefToTrackIndex: Map<Url, Int> = emptyMap()

    // Ordered list of audio hrefs in the current playlist
    private var playlistAudioHrefs: List<Url> = emptyList()

    // Mutex to prevent concurrent play calls
    private val playMutex = Mutex()

    /**
     * Initializes the player with lazy SMIL loading.
     *
     * @param initialChapterHref The initial chapter href to optimize index building for
     */
    suspend fun initialize(initialChapterHref: String? = null) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "        ┌─── initialize() BEGIN ───")
        Log.d(TAG, "        │ chapter=$initialChapterHref")

        Log.d(TAG, "        │ [+0ms] Initializing SMIL manager...")
        smilLoadingManager.initialize(playerScope)

        val chapterHref = initialChapterHref
            ?: publication.readingOrder.firstOrNull()?.href?.toString()
            ?: run {
                Log.w(TAG, "        │ WARNING: No chapter href found")
                Log.d(TAG, "        └─── initialize() END (no chapter) ───")
                return
            }

        Log.d(TAG, "        │ [+${System.currentTimeMillis() - startTime}ms] Building index for: $chapterHref")
        val indexStart = System.currentTimeMillis()
        smilLoadingManager.buildInitialIndex(chapterHref)
        Log.d(TAG, "        └─── initialize() END total=${System.currentTimeMillis() - startTime}ms indexBuild=${System.currentTimeMillis() - indexStart}ms ───")
    }

    /**
     * Starts playback for a chapter.
     * This is a suspending function that completes when playback is ready to start.
     *
     * @param chapterHref The href of the chapter to play
     * @param initialPositionMs Optional initial position in milliseconds
     */
    suspend fun play(
        chapterHref: Url? = null,
        initialPositionMs: Long? = null,
    ) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "        ┌─── play() BEGIN ───")
        Log.d(TAG, "        │ chapter=$chapterHref posMs=$initialPositionMs")

        playMutex.lock()
        try {
            playInternal(chapterHref, initialPositionMs, startTime)
        } finally {
            playMutex.unlock()
        }

        Log.d(TAG, "        └─── play() END total=${System.currentTimeMillis() - startTime}ms ───")
    }

    private suspend fun playInternal(
        chapterHref: Url?,
        initialPositionMs: Long?,
        parentStartTime: Long,
    ) {
        val chapterToPlay = if (chapterHref != null) {
            Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Finding chapter with audio...")
            val findStart = System.currentTimeMillis()
            val chapterWithAudio = smilLoadingManager.findChapterWithAudio(
                chapterHref.removeFragment().toString(),
            )
            Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] findChapterWithAudio done (${System.currentTimeMillis() - findStart}ms) result=$chapterWithAudio")

            if (chapterWithAudio == null) {
                Log.e(TAG, "        │ ERROR: No chapters with audio!")
                analytics.logException(
                    IllegalStateException("No chapters with audio found"),
                    "HeadlessPlayer: No audio content available starting from $chapterHref",
                )
                return
            }
            Url(chapterWithAudio)
        } else {
            Log.d(TAG, "        │ No chapter href, using current state")
            null
        }

        if (chapterToPlay != null) {
            Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Preparing chapter...")
            prepareChapter(chapterToPlay, initialPositionMs, parentStartTime)
        } else {
            Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Resuming at pos=$initialPositionMs")
            if (initialPositionMs != null && initialPositionMs > 0) {
                exoPlayer.seekTo(initialPositionMs)
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    fun pause() {
        exoPlayer.pause()
    }

    fun resume() {
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    fun seekForward() {
        exoPlayer.seekForward()
    }

    fun seekBackward() {
        exoPlayer.seekBack()
    }

    fun release() {
        playerScope.cancel()
        // DON'T call exoPlayer.release() - the ExoPlayer is owned by MediaPlaybackService,
        // not by this session. Releasing it would destroy the shared player used across
        // all book switches and break the MediaLibrarySession connection.
        smilLoadingManager.release()
    }

    private suspend fun prepareChapter(
        chapterHref: Url,
        initialPositionMs: Long?,
        parentStartTime: Long,
    ) {
        try {
            val success = prepareChapterAsync(chapterHref, initialPositionMs, parentStartTime)
            if (!success) {
                Log.e(TAG, "        │ ERROR: No playable content!")
                analytics.logException(
                    IllegalStateException("Chapter preparation returned no content"),
                    "HeadlessPlayer: No playable content for $chapterHref",
                )
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "        │ WARNING: Cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "        │ ERROR: Exception", e)
            analytics.logException(e, "HeadlessPlayer: Failed to prepare chapter: $chapterHref")
        }
    }

    private suspend fun prepareChapterAsync(
        chapterHref: Url,
        initialPositionMs: Long?,
        parentStartTime: Long,
    ): Boolean {
        val normalizedHref = chapterHref.removeFragment().toString()

        // Load clips for this chapter
        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Loading SMIL clips...")
        val clipsStart = System.currentTimeMillis()
        val smilClips = smilLoadingManager.getClipsForChapter(normalizedHref)
        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Got ${smilClips.size} clips (${System.currentTimeMillis() - clipsStart}ms)")

        if (smilClips.isEmpty()) {
            Log.w(TAG, "        │ WARNING: No SMIL clips!")
            return false
        }

        // Extract unique audio files in order and calculate clip ranges per audio file
        // This is needed to find which audio file a saved position belongs to
        data class AudioFileRange(var minStartMs: Long = Long.MAX_VALUE, var maxEndMs: Long = 0L)
        val audioFileRanges = mutableMapOf<Url, AudioFileRange>()
        val audioFilesOrdered = mutableListOf<Url>()

        for (clip in smilClips) {
            val audioHref = Url(clip.audioSrc) ?: continue
            val startMs = (clip.clipBegin * SECONDS_TO_MS).toLong()
            val endMs = (clip.clipEnd * SECONDS_TO_MS).toLong()

            // Track audio files in order of first appearance
            if (audioHref !in audioFileRanges) {
                audioFilesOrdered.add(audioHref)
            }

            // Update min/max range for this audio file
            val range = audioFileRanges.getOrPut(audioHref) { AudioFileRange() }
            if (startMs < range.minStartMs) range.minStartMs = startMs
            if (endMs > range.maxEndMs) range.maxEndMs = endMs
        }

        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] ${audioFilesOrdered.size} unique audio files")

        if (audioFilesOrdered.isEmpty()) {
            Log.w(TAG, "        │ WARNING: No audio files!")
            return false
        }

        // Find which audio file the saved position belongs to
        // The saved position is relative to a specific audio file, so we need to find which one
        var targetTrackIndex = 0
        if (initialPositionMs != null && initialPositionMs > 0 && audioFilesOrdered.size > 1) {
            // Check which audio file's range contains this position
            for ((index, audioHref) in audioFilesOrdered.withIndex()) {
                val range = audioFileRanges[audioHref] ?: continue
                if (initialPositionMs >= range.minStartMs && initialPositionMs <= range.maxEndMs) {
                    targetTrackIndex = index
                    Log.d(TAG, "        │ Position $initialPositionMs ms belongs to track $index (range: ${range.minStartMs}-${range.maxEndMs})")
                    break
                }
            }
        }

        // Prepare playlist and seek to initial position in the correct track
        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Building playlist (track=$targetTrackIndex, pos=${initialPositionMs ?: 0L})...")
        val playlistStart = System.currentTimeMillis()
        preparePlaylist(audioFilesOrdered, targetTrackIndex, initialPositionMs ?: 0L)
        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] Playlist ready (${System.currentTimeMillis() - playlistStart}ms)")

        // Start playback
        exoPlayer.playWhenReady = true
        Log.d(TAG, "        │ [+${System.currentTimeMillis() - parentStartTime}ms] ExoPlayer state=${exoPlayer.playbackState} playWhenReady=true")

        // Prefetch next chapter
        smilLoadingManager.prefetchNextChapter(normalizedHref)
        return true
    }

    /** Pending position to seek to once player is ready */
    private var pendingSeekPositionMs: Long? = null

    /** Listener to handle seeking once player is ready */
    private val readySeekListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                pendingSeekPositionMs?.let { positionMs ->
                    if (positionMs > 0) {
                        Log.d(TAG, "STATE_READY: Seeking to pending position $positionMs ms")
                        exoPlayer.seekTo(positionMs)
                    }
                    pendingSeekPositionMs = null
                }
            }
        }
    }

    private var listenerRegistered = false

    private fun preparePlaylist(
        audioHrefs: List<Url>,
        initialTrackIndex: Int,
        initialPositionMs: Long,
    ) {
        if (audioHrefs.isEmpty()) return

        // Register listener for seek-on-ready if not already registered
        if (!listenerRegistered) {
            exoPlayer.addListener(readySeekListener)
            listenerRegistered = true
        }

        // Store pending seek position - ExoPlayer's setMediaSources doesn't reliably
        // seek when loading from custom data sources, so we seek once STATE_READY fires
        pendingSeekPositionMs = initialPositionMs

        // Build metadata for notifications and Android Auto
        val metadata = buildMediaMetadata()

        // Build MediaItems for all audio files with metadata
        // Include mediaId to help Android Auto identify the content
        val mediaItems = audioHrefs.mapIndexed { index, audioHref ->
            val audioUrl = publication.baseUrl?.resolve(audioHref)?.toString()
                ?: audioHref.toString()

            // Use book title + track index as mediaId for Android Auto identification
            val mediaId = "${bookMetadata?.title ?: publication.metadata.title}:$index"

            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(audioUrl)
                .setMediaMetadata(metadata)
                .build()
        }

        // Build media sources from items
        val mediaSources = mediaItems.map { mediaItem ->
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Set playlist and seek to initial position (track index is handled, position via listener)
        exoPlayer.setMediaSources(mediaSources, initialTrackIndex, initialPositionMs)
        exoPlayer.prepare()

        // TODO: Metadata update for Android Auto book switching
        // The issue is that setMediaSources() doesn't trigger onMediaMetadataChanged()
        // for MediaSession controllers. Need to find a way to force metadata update
        // without blocking playback. See: https://github.com/androidx/media/issues/951

        // Update tracking
        playlistAudioHrefs = audioHrefs
        audioHrefToTrackIndex = audioHrefs.mapIndexed { index, href -> href to index }.toMap()
        currentAudioHref = audioHrefs.getOrNull(initialTrackIndex)
    }

    /**
     * Builds MediaMetadata for the current book.
     * Used for notifications and Android Auto "Now Playing" display.
     */
    private fun buildMediaMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)

        if (bookMetadata != null) {
            builder.setTitle(bookMetadata.title)
            builder.setArtist(bookMetadata.author)

            if (bookMetadata.coverArtwork != null) {
                builder.setArtworkData(
                    bookMetadata.coverArtwork,
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }
        } else {
            // Fallback to publication metadata if book metadata not available
            builder.setTitle(publication.metadata.title)
            publication.metadata.authors.firstOrNull()?.name?.let {
                builder.setArtist(it)
            }
        }

        return builder.build()
    }
}

