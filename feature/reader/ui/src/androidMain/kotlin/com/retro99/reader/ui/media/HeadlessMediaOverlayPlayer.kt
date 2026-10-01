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
import com.retro99.epub.api.SmilClip
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.playback.setArtworkDataIfSmall
import com.retro99.reader.ui.publication.EpubPublication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

private const val TAG = "čič123"
private const val SECONDS_TO_MS = 1000.0

/**
 * Metadata for a book being played headlessly.
 */
data class HeadlessBookMetadata(
    val title: String,
    val author: String?,
    val coverArtwork: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HeadlessBookMetadata
        return title == other.title && author == other.author
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (author?.hashCode() ?: 0)
        return result
    }
}

/**
 * Headless audio player for EPUB media overlays.
 *
 * This is a simplified version of [MediaOverlayPlayer] designed for Android Auto
 * and other headless playback scenarios. It:
 * 1. Uses a shared ExoPlayer (owned by MediaPlaybackService)
 * 2. Loads SMIL clips and builds playlists
 * 3. Does NOT emit locators for text highlighting
 * 4. Does NOT interact with UI components
 *
 * The player is created by [HeadlessSessionFactory] and managed per-book.
 *
 * @param epubPublication The EpubPublication containing the EPUB
 * @param analytics Analytics for error logging
 * @param smilLoadingManager Manager for loading SMIL clips
 * @param exoPlayer The shared ExoPlayer from MediaPlaybackService
 * @param bookMetadata Optional metadata for notifications and Android Auto
 */
@OptIn(UnstableApi::class)
class HeadlessMediaOverlayPlayer(
    private val epubPublication: EpubPublication,
    private val analytics: Analytics,
    private val smilLoadingManager: SmilLoadingManager,
    private val exoPlayer: ExoPlayer,
    private val bookMetadata: HeadlessBookMetadata?,
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

    /** Pending position to seek to once player is ready */
    private var pendingSeekPositionMs: Long? = null

    /**
     * Callback invoked when the current chapter's audio playback completes.
     * Used by HeadlessPlaybackSession to trigger auto-play of the next chapter.
     */
    var onChapterCompleted: (() -> Unit)? = null

    /** Listener to handle seeking once player is ready and chapter completion detection */
    private val playerStateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    pendingSeekPositionMs?.let { positionMs ->
                        if (positionMs > 0) {
                            Log.d(TAG, "HeadlessPlayer: STATE_READY - seeking to $positionMs ms")
                            exoPlayer.seekTo(positionMs)
                        }
                        pendingSeekPositionMs = null
                    }
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "HeadlessPlayer: STATE_ENDED - chapter completed")
                    onChapterCompleted?.invoke()
                }
            }
        }
    }

    private var listenerRegistered = false

    /**
     * Initializes the player with lazy SMIL loading.
     *
     * @param initialChapterHref The initial chapter href to optimize index building for
     */
    suspend fun initialize(initialChapterHref: String? = null) {
        Log.d(TAG, "HeadlessPlayer: initialize() chapter=$initialChapterHref")

        smilLoadingManager.initialize(playerScope)

        val chapterHref = initialChapterHref
            ?: publication.readingOrder.firstOrNull()?.href?.toString()
            ?: run {
                Log.w(TAG, "HeadlessPlayer: No chapter href found")
                return
            }

        smilLoadingManager.buildInitialIndex(chapterHref)
        Log.d(TAG, "HeadlessPlayer: initialize() complete")
    }

    /**
     * Starts playback for a chapter.
     *
     * @param chapterHref The href of the chapter to play
     * @param initialPositionMs Optional initial position in milliseconds
     */
    suspend fun play(
        chapterHref: Url? = null,
        initialPositionMs: Long? = null,
    ) {
        Log.d(TAG, "HeadlessPlayer: play() chapter=$chapterHref posMs=$initialPositionMs")

        playMutex.lock()
        try {
            playInternal(chapterHref, initialPositionMs)
        } finally {
            playMutex.unlock()
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

    /**
     * Releases resources. Does NOT release the ExoPlayer (owned by service).
     */
    fun release() {
        Log.d(TAG, "HeadlessPlayer: release()")
        if (listenerRegistered) {
            exoPlayer.removeListener(playerStateListener)
            listenerRegistered = false
        }
        onChapterCompleted = null
        playerScope.cancel()
        smilLoadingManager.release()
    }

    private suspend fun playInternal(
        chapterHref: Url?,
        initialPositionMs: Long?,
    ) {
        val chapterToPlay = if (chapterHref != null) {
            val chapterWithAudio = smilLoadingManager.findChapterWithAudio(
                chapterHref.removeFragment().toString(),
            )
            if (chapterWithAudio == null) {
                analytics.logException(
                    IllegalStateException("No chapters with audio found"),
                    "HeadlessPlayer: No audio content starting from $chapterHref",
                )
                return
            }
            Url(chapterWithAudio)
        } else {
            null
        }

        if (chapterToPlay != null) {
            prepareChapter(chapterToPlay, initialPositionMs)
        } else {
            if (initialPositionMs != null && initialPositionMs > 0) {
                exoPlayer.seekTo(initialPositionMs)
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    private suspend fun prepareChapter(
        chapterHref: Url,
        initialPositionMs: Long?,
    ) {
        try {
            val success = prepareChapterAsync(chapterHref, initialPositionMs)
            if (!success) {
                analytics.logException(
                    IllegalStateException("Chapter preparation returned no content"),
                    "HeadlessPlayer: No playable content for $chapterHref",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "HeadlessPlayer: prepareChapter failed", e)
            analytics.logException(e, "HeadlessPlayer: Failed to prepare chapter: $chapterHref")
        }
    }

    private suspend fun prepareChapterAsync(
        chapterHref: Url,
        initialPositionMs: Long?,
    ): Boolean {
        val normalizedHref = chapterHref.removeFragment().toString()

        // Load clips for this chapter
        val smilClips = smilLoadingManager.getClipsForChapter(normalizedHref)
        Log.d(TAG, "HeadlessPlayer: Got ${smilClips.size} clips for $normalizedHref")

        if (smilClips.isEmpty()) {
            return false
        }

        // Extract unique audio files and calculate ranges
        val audioFilesOrdered = extractAudioFiles(smilClips)
        if (audioFilesOrdered.isEmpty()) {
            return false
        }

        // Find which audio file the saved position belongs to
        val targetTrackIndex = findTrackIndexForPosition(smilClips, audioFilesOrdered, initialPositionMs)

        // Get chapter title for metadata
        val chapterTitle = getChapterTitle(chapterHref)

        // Prepare playlist with updated chapter title
        preparePlaylist(audioFilesOrdered, targetTrackIndex, initialPositionMs ?: 0L, chapterTitle)

        // Start playback
        exoPlayer.playWhenReady = true

        // Prefetch next chapter
        smilLoadingManager.prefetchNextChapter(normalizedHref)
        return true
    }

    private fun extractAudioFiles(smilClips: List<SmilClip>): List<Url> {
        val audioFilesOrdered = mutableListOf<Url>()
        val seen = mutableSetOf<String>()

        for (clip in smilClips) {
            val audioHref = Url(clip.audioSrc) ?: continue
            if (clip.audioSrc !in seen) {
                seen.add(clip.audioSrc)
                audioFilesOrdered.add(audioHref)
            }
        }
        return audioFilesOrdered
    }

    private fun findTrackIndexForPosition(
        smilClips: List<SmilClip>,
        audioFilesOrdered: List<Url>,
        initialPositionMs: Long?,
    ): Int {
        if (initialPositionMs == null || initialPositionMs <= 0 || audioFilesOrdered.size <= 1) {
            return 0
        }

        // Build ranges for each audio file
        data class AudioFileRange(var minStartMs: Long = Long.MAX_VALUE, var maxEndMs: Long = 0L)
        val audioFileRanges = mutableMapOf<Url, AudioFileRange>()

        for (clip in smilClips) {
            val audioHref = Url(clip.audioSrc) ?: continue
            val startMs = (clip.clipBegin * SECONDS_TO_MS).toLong()
            val endMs = (clip.clipEnd * SECONDS_TO_MS).toLong()

            val range = audioFileRanges.getOrPut(audioHref) { AudioFileRange() }
            if (startMs < range.minStartMs) range.minStartMs = startMs
            if (endMs > range.maxEndMs) range.maxEndMs = endMs
        }

        // Find which audio file contains this position
        for ((index, audioHref) in audioFilesOrdered.withIndex()) {
            val range = audioFileRanges[audioHref] ?: continue
            if (initialPositionMs >= range.minStartMs && initialPositionMs <= range.maxEndMs) {
                Log.d(TAG, "HeadlessPlayer: Position $initialPositionMs ms in track $index")
                return index
            }
        }
        return 0
    }

    private fun preparePlaylist(
        audioHrefs: List<Url>,
        initialTrackIndex: Int,
        initialPositionMs: Long,
        chapterTitle: String? = null,
    ) {
        if (audioHrefs.isEmpty()) return

        // Register listener for seek-on-ready and chapter completion if not already registered
        if (!listenerRegistered) {
            exoPlayer.addListener(playerStateListener)
            listenerRegistered = true
        }

        pendingSeekPositionMs = initialPositionMs

        // Build metadata for notifications and Android Auto with chapter title
        val metadata = buildMediaMetadata(chapterTitle)

        // Build MediaItems
        val mediaItems = audioHrefs.mapIndexed { index, audioHref ->
            val audioUrl = publication.baseUrl?.resolve(audioHref)?.toString()
                ?: audioHref.toString()

            val mediaId = "${bookMetadata?.title ?: publication.metadata.title}:$index"

            MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(audioUrl)
                .setMediaMetadata(metadata)
                .build()
        }

        // Build media sources
        val mediaSources = mediaItems.map { mediaItem ->
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Set playlist
        exoPlayer.setMediaSources(mediaSources, initialTrackIndex, initialPositionMs)
        exoPlayer.prepare()

        // Update tracking
        playlistAudioHrefs = audioHrefs
        audioHrefToTrackIndex = audioHrefs.mapIndexed { index, href -> href to index }.toMap()
        currentAudioHref = audioHrefs.getOrNull(initialTrackIndex)
    }

    private fun buildMediaMetadata(chapterTitle: String? = null): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)

        val bookTitle = bookMetadata?.title ?: publication.metadata.title
        val author = bookMetadata?.author ?: publication.metadata.authors.firstOrNull()?.name

        // For audiobooks, show chapter as title and book as album
        // This makes notifications show: "Chapter Name" / "Book Title" / "Author"
        if (chapterTitle != null) {
            builder.setTitle(chapterTitle)
            builder.setAlbumTitle(bookTitle)
        } else {
            builder.setTitle(bookTitle)
        }

        if (author != null) {
            builder.setArtist(author)
        }

        builder.setArtworkDataIfSmall(bookMetadata?.coverArtwork, TAG)

        return builder.build()
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
        Log.d(TAG, "HeadlessPlayer: getChapterTitle normalizedHref=$normalizedHref")

        // Try reading order first (most common case)
        val readingOrderTitle = publication.readingOrder.find { link ->
            link.href.toString().substringBefore('#') == normalizedHref
        }?.title

        if (readingOrderTitle != null) {
            Log.d(TAG, "HeadlessPlayer: Found title in readingOrder: $readingOrderTitle")
            return readingOrderTitle
        }

        // Fall back to table of contents (may have more descriptive titles)
        val tocTitle = findTitleInToc(publication.tableOfContents, normalizedHref)
        Log.d(TAG, "HeadlessPlayer: TOC lookup result: $tocTitle")
        return tocTitle
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
