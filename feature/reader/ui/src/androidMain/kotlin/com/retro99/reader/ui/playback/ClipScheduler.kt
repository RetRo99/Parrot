package com.retro99.reader.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.reader.ui.media.MediaOverlayClip

/**
 * Clip information needed for scheduling PlayerMessage callbacks.
 *
 * This is a simplified version of MediaOverlayClip that can be passed
 * across component boundaries without Readium Url dependencies.
 */
data class SchedulableClip(
    val fragmentId: String?,
    val textHref: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/**
 * Schedules PlayerMessage callbacks at clip boundaries.
 *
 * When playback reaches a clip's start time, the callback is invoked
 * to notify connected controllers about the clip change. This enables
 * text highlighting to work even when the UI is disconnected and reconnects.
 *
 * Architecture notes:
 * - Lives in MediaPlaybackService (owns the player)
 * - Receives clip info from MediaOverlayPlayer via MediaPlaybackController
 * - Uses ExoPlayer's PlayerMessage API for precise timing
 * - Delivers on the application thread (setLooper), because the callback touches
 *   the MediaSession, which is main-thread only
 * - Messages are NOT deleted after delivery (setDeleteAfterDelivery(false))
 *   so they fire again on seek back
 */
@OptIn(UnstableApi::class)
class ClipScheduler(
    private val onClipStarted: (SchedulableClip) -> Unit,
) {
    // Track scheduled clips per track index to allow clearing
    private val scheduledClipsPerTrack = mutableMapOf<Int, List<SchedulableClip>>()

    /**
     * Schedules clip callbacks for a specific track in the playlist.
     *
     * This creates PlayerMessage objects that fire when playback reaches
     * each clip's start time. The messages are NOT deleted after delivery,
     * so seeking backward will re-trigger them.
     *
     * @param player The ExoPlayer instance
     * @param trackIndex The index of the track in the playlist
     * @param clips The clips to schedule for this track
     */
    fun scheduleClipsForTrack(
        player: ExoPlayer,
        trackIndex: Int,
        clips: List<SchedulableClip>,
    ) {
        // Store for tracking (useful for debugging/clearing)
        scheduledClipsPerTrack[trackIndex] = clips

        clips.forEach { clip ->
            player.createMessage { messageType, payload ->
                @Suppress("UNCHECKED_CAST")
                val clipPayload = payload as SchedulableClip
                onClipStarted(clipPayload)
            }.apply {
                // Deliver on the application thread, not the playback thread. The callback
                // reaches MediaSession, whose methods throw IllegalStateException
                // ("Player callback method is called from a wrong thread") off that thread
                // since media3 1.11.0 - and ExoPlayer turns a throw inside message delivery
                // into a fatal playback error, which killed narration before its first
                // sample. ExoPlayer.createMessage defaults to the playback thread.
                setLooper(player.applicationLooper)
                setPosition(trackIndex, clip.startTimeMs)
                setPayload(clip)
                setDeleteAfterDelivery(false)
                send()
            }
        }
    }

    /**
     * Clears all scheduled clips.
     * Call this when stopping playback or switching books.
     *
     * Note: ExoPlayer PlayerMessages are tied to the player and cleared
     * when the player is cleared/released. This method clears our tracking map.
     */
    fun clearScheduledClips() {
        scheduledClipsPerTrack.clear()
    }

    /**
     * Checks if clips are scheduled for a specific track.
     */
    fun hasClipsForTrack(trackIndex: Int): Boolean {
        return scheduledClipsPerTrack[trackIndex]?.isNotEmpty() == true
    }
}

/**
 * Extension function to convert MediaOverlayClip to SchedulableClip.
 */
fun MediaOverlayClip.toSchedulableClip(): SchedulableClip {
    return SchedulableClip(
        fragmentId = fragmentId,
        textHref = textHref.toString(),
        startTimeMs = (startTime * 1000).toLong(),
        endTimeMs = (endTime * 1000).toLong(),
    )
}

/**
 * Extension function to convert a list of MediaOverlayClips to SchedulableClips.
 */
fun List<MediaOverlayClip>.toSchedulableClips(): List<SchedulableClip> {
    return map { it.toSchedulableClip() }
}

