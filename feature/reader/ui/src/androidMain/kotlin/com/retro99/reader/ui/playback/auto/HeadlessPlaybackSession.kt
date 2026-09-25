package com.retro99.reader.ui.playback.auto

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.base.nowMillis
import com.retro99.books.domain.LocalBookFileUsageLease
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.HeadlessMediaOverlayPlayer
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.reader.ui.publication.EpubPublication
import com.retro99.sync.domain.RoutineSyncScheduler
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.r2.shared.util.Url
import kotlin.time.Clock

private const val TAG = "čič123"

/**
 * Manages a headless playback session for a single book.
 *
 * This class handles:
 * - Starting/stopping playback
 * - Chapter navigation
 * - Periodic position saving
 * - Resource cleanup
 *
 * Created by [HeadlessSessionFactory], not registered in Koin.
 */
class HeadlessPlaybackSession(
    val serverId: String,
    val bookUuid: String,
    val bookTitle: String,
    val coverUrl: String?,
    private val publication: EpubPublication,
    private val player: HeadlessMediaOverlayPlayer,
    private val smilLoadingManager: SmilLoadingManager,
    private val saveProgressUseCase: SaveReadingProgressUseCase,
    private val syncNowUseCase: SyncNowUseCase,
    private val analytics: Analytics,
    private val exoPlayer: ExoPlayer,
    private val initialChapterHref: String?,
    private val initialPositionMs: Long?,
    private val fileUsageLease: LocalBookFileUsageLease,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val routineSyncScheduler = RoutineSyncScheduler(
        scope = scope,
        nowMillis = ::nowMillis,
        requestSync = { routineSchedule ->
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.ROUTINE_PROGRESS,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.ROUTINE,
                    routineSchedule = routineSchedule,
                ),
            )
        },
    )
    private var positionSaveJob: Job? = null
    private var currentChapterHref: String? = initialChapterHref

    // Position save interval in milliseconds
    private val positionSaveIntervalMs = 30_000L

    init {
        // Set up chapter completion callback for auto-play of next chapter
        player.onChapterCompleted = {
            Log.d(TAG, "HeadlessSession: Chapter completed, auto-playing next chapter")
            scope.launch {
                // Save position before moving to next chapter
                saveCurrentPosition()
                // Skip to next chapter with audio
                skipToNextChapter()
            }
        }
    }

    /**
     * Starts playback from the initial position.
     */
    suspend fun play() {
        Log.d(TAG, "HeadlessSession: play() bookUuid=$bookUuid")

        val chapterHref = currentChapterHref
            ?: smilLoadingManager.findChapterWithAudio(
                publication.publication.readingOrder.firstOrNull()?.href?.toString() ?: ""
            )
            ?: run {
                Log.e(TAG, "HeadlessSession: No chapter with audio found")
                return
            }

        currentChapterHref = chapterHref
        player.play(chapterHref = Url(chapterHref), initialPositionMs = initialPositionMs)

        // Start periodic position saving
        startPositionSaving()
    }

    fun pause() {
        Log.d(TAG, "HeadlessSession: pause()")
        player.pause()
        saveCurrentPositionAsync()
    }

    fun resume() {
        Log.d(TAG, "HeadlessSession: resume()")
        player.resume()
    }

    fun seekForward() {
        player.seekForward()
    }

    fun seekBackward() {
        player.seekBackward()
    }

    /**
     * Skips to the next chapter with audio.
     */
    suspend fun skipToNextChapter() {
        val current = currentChapterHref ?: return
        val next = smilLoadingManager.findNextChapterWithAudio(current)
        if (next != null) {
            Log.d(TAG, "HeadlessSession: skipToNextChapter() -> $next")
            currentChapterHref = next
            player.play(chapterHref = Url(next), initialPositionMs = null)
        } else {
            Log.d(TAG, "HeadlessSession: No next chapter with audio")
        }
    }

    /**
     * Skips to the previous chapter with audio.
     */
    suspend fun skipToPreviousChapter() {
        val current = currentChapterHref ?: return
        val readingOrder = publication.publication.readingOrder.map { it.href.toString() }
        val currentIndex = readingOrder.indexOf(current)

        if (currentIndex <= 0) {
            Log.d(TAG, "HeadlessSession: Already at first chapter")
            return
        }

        // Search backwards for a chapter with audio
        for (i in (currentIndex - 1) downTo 0) {
            val candidate = readingOrder[i]
            val hasAudio = smilLoadingManager.findChapterWithAudio(candidate) == candidate
            if (hasAudio) {
                Log.d(TAG, "HeadlessSession: skipToPreviousChapter() -> $candidate")
                currentChapterHref = candidate
                player.play(chapterHref = Url(candidate), initialPositionMs = null)
                return
            }
        }
        Log.d(TAG, "HeadlessSession: No previous chapter with audio")
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

    private fun saveCurrentPositionAsync() {
        scope.launch {
            saveCurrentPosition()
        }
    }

    private suspend fun saveCurrentPosition() {
        val chapterHref = currentChapterHref ?: return
        val audioPositionMs = exoPlayer.currentPosition

        savePosition(buildPosition(chapterHref, audioPositionMs))
    }

    private fun buildPosition(
        chapterHref: String,
        audioPositionMs: Long,
    ): PositionDomainModel {
        val now = Clock.System.now().toString()
        return PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = nowMillis(),
            createdAt = null,
            updatedAt = now,
            locatorHref = chapterHref,
            locatorType = "application/xhtml+xml",
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = audioPositionMs,
            chapterIndex = null,
            progression = null,
            totalChapters = null,
            totalDurationMs = exoPlayer.duration.takeIf { it > 0 },
            totalProgression = null,
            position = null,
        )
    }

    private suspend fun savePosition(position: PositionDomainModel) {
        try {
            saveProgressUseCase(position)
                .onSuccess { routineSyncScheduler.markDirty() }
            Log.d(TAG, "HeadlessSession: Saved position at ${position.audioTimestampMs} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logException(e, "HeadlessSession: Failed to save position")
        }
    }

    override fun close() {
        Log.d(TAG, "HeadlessSession: close()")
        val finalPosition = currentChapterHref?.let { chapterHref ->
            buildPosition(chapterHref, exoPlayer.currentPosition)
        }
        // Save final position and request an urgent application-scoped flush.
        scope.launch(NonCancellable) {
            finalPosition?.let { position -> savePosition(position) }
            routineSyncScheduler.close()
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.READER_CHECKPOINT,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.URGENT,
                ),
            )
            scope.cancel()
        }
        positionSaveJob?.cancel()
        player.release()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        fileUsageLease.release()
    }
}
