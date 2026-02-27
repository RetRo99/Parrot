package com.retro99.reader.ui.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.retro99.reader.ui.media.MediaOverlayClip
import org.readium.r2.shared.util.Url
import kotlin.math.roundToLong

private const val TAG = "ClipMessageScheduler"

/** Conversion factor from seconds to milliseconds */
private const val SECONDS_TO_MS = 1000.0

/**
 * Schedules ExoPlayer messages to fire at exact clip start times.
 *
 * This provides more precise clip change detection than polling (which can drift or miss
 * exact boundaries). ExoPlayer's message system fires callbacks at exact playback positions.
 *
 * ## How It Works
 *
 * When clips are scheduled:
 * 1. For each clip, a [PlayerMessage] is created with the clip as payload
 * 2. The message is scheduled at the clip's start time on the specified track
 * 3. When playback reaches that position, ExoPlayer invokes the callback
 * 4. The callback notifies the listener with the clip that just started
 *
 * ## Usage
 *
 * ```kotlin
 * val scheduler = ClipMessageScheduler { clip ->
 *     // Handle clip change - update UI highlighting, etc.
 * }
 *
 * // When chapter is prepared:
 * scheduler.scheduleClips(player, clips, trackIndex = 0)
 *
 * // When chapter changes:
 * scheduler.cancelAll()
 * scheduler.scheduleClips(player, newClips, trackIndex = 0)
 *
 * // When player is released:
 * scheduler.cancelAll()
 * ```
 *
 * @param onClipChanged Callback invoked when playback reaches a clip's start time
 */
@OptIn(UnstableApi::class)
class ClipMessageScheduler(
    private val onClipChanged: (MediaOverlayClip) -> Unit,
) {

    /** Active player messages (for cancellation on chapter change/release) */
    private val activeMessages = mutableListOf<PlayerMessage>()

    /**
     * Schedules messages for all clips in the given list.
     *
     * Each clip's start time becomes a trigger point. When playback reaches that position,
     * [onClipChanged] is invoked with the clip.
     *
     * @param player The ExoPlayer instance to schedule messages on
     * @param clips List of clips to schedule (should be sorted by startTime)
     * @param trackIndex The index of the media item in ExoPlayer's playlist (for seekTo position)
     * @param audioHref Optional: filter to only schedule clips for this audio file
     */
    fun scheduleClips(
        player: ExoPlayer,
        clips: List<MediaOverlayClip>,
        trackIndex: Int,
        audioHref: Url? = null,
    ) {
        // Filter clips if audioHref is specified
        val clipsToSchedule = if (audioHref != null) {
            clips.filter { it.audioHref == audioHref }
        } else {
            clips
        }

        Log.d(TAG, "Scheduling ${clipsToSchedule.size} clips for track $trackIndex")

        for (clip in clipsToSchedule) {
            try {
                val positionMs = (clip.startTime * SECONDS_TO_MS).roundToLong()

                val message = player.createMessage { messageType, payload ->
                    val triggeredClip = payload as? MediaOverlayClip
                    if (triggeredClip != null) {
                        onClipChanged(triggeredClip)
                    }
                }.apply {
                    setPosition(trackIndex, positionMs)
                    setPayload(clip)
                    // Don't delete after delivery - allows replaying if user seeks back
                    setDeleteAfterDelivery(false)
                    send()
                }

                activeMessages.add(message)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule message for clip: ${clip.fragmentId}", e)
            }
        }

        Log.d(TAG, "Scheduled ${activeMessages.size} messages")
    }

    /**
     * Cancels all scheduled messages.
     *
     * Call this when:
     * - Chapter changes (before scheduling new clips)
     * - Player is released
     * - Audio file switches within a chapter
     */
    fun cancelAll() {
        Log.d(TAG, "Cancelling ${activeMessages.size} scheduled messages")

        for (message in activeMessages) {
            try {
                message.cancel()
            } catch (e: Exception) {
                // Message may already be cancelled or player released
                Log.w(TAG, "Failed to cancel message", e)
            }
        }
        activeMessages.clear()
    }

    /**
     * Returns the number of currently scheduled messages.
     */
    fun scheduledCount(): Int = activeMessages.size
}

