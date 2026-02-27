package com.retro99.reader.ui.playback.auto

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.analytics.api.Analytics
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.HeadlessMediaOverlayPlayer
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.publication.EpubPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.r2.shared.util.Url

private const val TAG = "HeadlessPlaybackSession"

/**
 * Manages headless audio playback for Android Auto.
 *
 * This session handles:
 * - Audio playback without UI (no text highlighting)
 * - Chapter auto-advance when current chapter completes
 * - Position persistence (periodic, on pause, on close)
 * - Media controls (play, pause, seek, skip)
 *
 * Unlike the full reader which uses ReaderSyncCoordinator for text-audio sync,
 * this session only manages audio because there's no UI to synchronize with.
 */
class HeadlessPlaybackSession(
    val serverId: String,
    val bookUuid: String,
    private val publication: EpubPublication,
    private val player: HeadlessMediaOverlayPlayer,
    private val smilLoadingManager: SmilLoadingManager,
    private val saveProgressUseCase: SaveReadingProgressUseCase,
    private val analytics: Analytics,
    private val exoPlayer: ExoPlayer,
    private val initialChapterHref: String?,
    private val initialPositionMs: Long?,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaveJob: Job? = null
    private var currentChapterHref: String? = initialChapterHref

    // Position save interval in milliseconds (30 seconds)
    private val positionSaveIntervalMs = 30_000L

    /**
     * Starts playback from the initial position.
     * If no initial chapter is set, finds the first chapter with audio.
     */
    suspend fun play() {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "      ┌─── play() BEGIN ───")
        Log.d(TAG, "      │ bookUuid=$bookUuid")
        Log.d(TAG, "      │ initialChapter=$initialChapterHref, posMs=$initialPositionMs")
        Log.d(TAG, "      │ ExoPlayer state before play:")
        Log.d(TAG, "      │   - isPlaying: ${exoPlayer.isPlaying}")
        Log.d(TAG, "      │   - playbackState: ${exoPlayer.playbackState}")
        Log.d(TAG, "      │   - mediaItemCount: ${exoPlayer.mediaItemCount}")

        Log.d(TAG, "      │ [+0ms] Finding chapter with audio...")
        val chapterHref = currentChapterHref
            ?: smilLoadingManager.findChapterWithAudio(publication.tableOfContents.firstOrNull()?.href ?: "")
            ?: run {
                Log.e(TAG, "      │ ERROR: No chapter with audio found!")
                Log.d(TAG, "      └─── play() END (FAILED) ───")
                return
            }
        Log.d(TAG, "      │ [+${System.currentTimeMillis() - startTime}ms] Found chapter: $chapterHref")

        currentChapterHref = chapterHref

        Log.d(TAG, "      │ [+${System.currentTimeMillis() - startTime}ms] Calling player.play()...")
        val playStart = System.currentTimeMillis()
        player.play(
            chapterHref = Url(chapterHref),
            initialPositionMs = initialPositionMs,
        )
        Log.d(TAG, "      │ [+${System.currentTimeMillis() - startTime}ms] player.play() done (${System.currentTimeMillis() - playStart}ms)")

        Log.d(TAG, "      │ ExoPlayer state after play:")
        Log.d(TAG, "      │   - isPlaying: ${exoPlayer.isPlaying}")
        Log.d(TAG, "      │   - playbackState: ${exoPlayer.playbackState}")
        Log.d(TAG, "      │   - mediaItemCount: ${exoPlayer.mediaItemCount}")

        startPositionSaving()
        observeChapterCompletion()

        Log.d(TAG, "      └─── play() END total=${System.currentTimeMillis() - startTime}ms ───")
    }

    /**
     * Pauses playback and saves current position.
     */
    fun pause() {
        player.pause()
        scope.launch { saveCurrentPosition() }
    }

    /**
     * Resumes playback from current position.
     */
    fun resume() {
        player.resume()
        startPositionSaving()
    }

    /**
     * Seeks forward by 10 seconds.
     */
    fun seekForward() {
        player.seekForward()
    }

    /**
     * Seeks backward by 10 seconds.
     */
    fun seekBackward() {
        player.seekBackward()
    }

    /**
     * Skips to the next chapter with audio.
     */
    suspend fun skipToNextChapter() {
        val current = currentChapterHref ?: return
        val nextChapter = smilLoadingManager.findNextChapterWithAudio(current) ?: return

        currentChapterHref = nextChapter
        player.play(chapterHref = Url(nextChapter))
    }

    /**
     * Skips to the previous chapter with audio.
     */
    suspend fun skipToPreviousChapter() {
        // For now, restart current chapter
        // TODO: Implement proper previous chapter navigation
        val current = currentChapterHref ?: return
        player.play(chapterHref = Url(current))
    }

    override fun close() {
        // Save final position before closing
        scope.launch {
            saveCurrentPosition()
            player.release()
            scope.cancel()
        }
    }

    private fun startPositionSaving() {
        positionSaveJob?.cancel()
        positionSaveJob = scope.launch {
            while (isActive) {
                delay(positionSaveIntervalMs)
                if (exoPlayer.isPlaying) {
                    saveCurrentPosition()
                }
            }
        }
    }

    private fun observeChapterCompletion() {
        scope.launch {
            // Observe chapter completion via playback state tracker
            // When chapter ends, auto-advance to next
            // This is handled by MediaOverlayPlayer's chapterAudioCompleted flow
            // which is emitted via PlaybackStateTracker
        }
    }

    private suspend fun saveCurrentPosition() {
        val chapterHref = currentChapterHref ?: return
        val positionMs = exoPlayer.currentPosition

        val position = PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = System.currentTimeMillis(),
            createdAt = null,
            updatedAt = null,
            locatorHref = chapterHref,
            locatorType = "application/xhtml+xml",
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = positionMs,
            chapterIndex = null,
            progression = null,
            totalChapters = null,
            totalDurationMs = null,
            totalProgression = null,
            position = null,
        )

        saveProgressUseCase(position)
    }
}

