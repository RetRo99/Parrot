package com.retro99.reader.ui.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.reader.ui.di.InitialAudioPosition
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.media.MediaOverlayClip
import com.retro99.reader.ui.model.AudioLocatorState
import com.retro99.reader.ui.model.LocatorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

private const val TAG = "LocatorTracker"

/** Interval in milliseconds for position updates during playback */
private const val POSITION_UPDATE_INTERVAL_MS = 100L

/** Conversion factor from seconds to milliseconds */
private const val SECONDS_TO_MS = 1000.0

/**
 * Tracks the current playback position and maps it to text locators.
 *
 * This class is responsible for:
 * - Periodically updating the current position during playback
 * - Finding the current clip based on position
 * - Emitting locators for text highlighting
 *
 * @param mediaPlaybackController Controller to get the ExoPlayer from the service
 * @param initialAudioPosition Initial audio position from saved reading progress
 */
@OptIn(UnstableApi::class)
@Scope(ReaderScope::class)
@Scoped
class LocatorTracker(
    private val mediaPlaybackController: MediaPlaybackController,
    private val initialAudioPosition: InitialAudioPosition,
) {
    /** Gets the ExoPlayer from the service. Returns null if service not started. */
    private val player: ExoPlayer?
        get() = mediaPlaybackController.player
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Scheduler for precise clip change detection using ExoPlayer's message system.
     *
     * This complements the polling approach by firing callbacks at exact clip boundaries.
     * Polling is still needed for position updates (seek bar), but clip highlighting
     * uses message scheduling for precision.
     */
    @OptIn(UnstableApi::class)
    private val clipMessageScheduler = ClipMessageScheduler { clip ->
        onClipMessageReceived(clip)
    }

    /** Current track index in ExoPlayer's playlist (for message scheduling) */
    private var currentTrackIndex: Int = 0

    /**
     * Current position in milliseconds (raw ExoPlayer position).
     * This is kept in sync with ExoPlayer - any position change also seeks ExoPlayer.
     */
    private val _currentPosition = MutableStateFlow(initialAudioPosition.positionMs ?: 0L)

    /**
     * Raw position from ExoPlayer. For display purposes, use [normalizedPosition] instead.
     */
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    /**
     * Chapter start offset for normalization (as a StateFlow for reactivity).
     * This is the minimum start time of clips in the current audio file.
     * Subtracting this from raw position gives normalized position starting at 0.
     */
    private val _chapterStartOffset = MutableStateFlow(0L)

    /**
     * Normalized position for UI display, starting at 0:00 for the chapter.
     * Calculated as: rawPosition - chapterStartOffset
     * Uses combine to react to both position and offset changes.
     */
    val normalizedPosition: StateFlow<Long> = combine(
        _currentPosition,
        _chapterStartOffset
    ) { rawPosition, offset ->
        (rawPosition - offset).coerceAtLeast(0L)
    }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Whether the initial seek has been applied */
    private var initialSeekApplied = false

    /**
     * Applies the initial seek position if not already applied.
     * Called lazily when playback starts and player is available.
     */
    fun ensureInitialSeekApplied() {
        if (!initialSeekApplied) {
            initialAudioPosition.positionMs?.let {
                player?.seekTo(it)
            }
            initialSeekApplied = true
        }
    }

    /**
     * Sets the current position and seeks ExoPlayer to match.
     * Use this instead of directly setting _currentPosition to keep them in sync.
     */
    private fun setPosition(positionMs: Long) {
        _currentPosition.value = positionMs
        player?.seekTo(positionMs)
    }

    /**
     * Sets the initial audio position.
     * Used when the user resolves a position conflict by choosing the remote position.
     * Updates the current position and seeks ExoPlayer to match.
     *
     * @param positionMs The audio position in milliseconds
     */
    fun setInitialPosition(positionMs: Long) {
        setPosition(positionMs)
    }

    /**
     * Internal state holding both the locator and the current clip for duration calculation.
     */
    private data class LocatorWithClip(
        val locator: Locator,
        val clip: MediaOverlayClip,
    )

    private val _currentLocatorWithClip = MutableStateFlow<LocatorWithClip?>(null)

    /**
     * Flow of current audio locator with timing information.
     * Includes sentence duration for pre-emptive page turn calculations.
     */
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
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    private var positionUpdateJob: Job? = null

    // Current chapter's clips (set externally when chapter changes)
    private var currentChapterClips: List<MediaOverlayClip> = emptyList()

    // Current audio file href being played
    private var currentAudioHref: Url? = null

    // Clips filtered to only the current audio file (used for time-based lookups)
    // This prevents returning clips from the wrong audio file when times overlap
    private var currentAudioFileClips: List<MediaOverlayClip> = emptyList()

    // Callback for when chapter audio playback exceeds the clip range
    // This is used to detect chapter completion when audio file contains multiple chapters
    var onChapterClipsExceeded: (() -> Unit)? = null

    // Flag to prevent multiple completion callbacks for the same chapter
    private var hasNotifiedChapterExceeded = false

    /**
     * Updates the clips for the current chapter.
     * Called when a new chapter is prepared.
     *
     * Note: This stores ALL clips for the chapter, which may span multiple audio files.
     * The position is reset to 0 as a safe default. The correct position will be set
     * by updatePositionForFragment() once the visible sentence is determined.
     *
     * IMPORTANT: This clears the audio href filter. The caller must call setCurrentAudioHref()
     * after this to set up proper filtering for multi-audio-file chapters.
     */
    fun setChapterClips(clips: List<MediaOverlayClip>) {
        // Cancel old clip messages since clips are changing
        clipMessageScheduler.cancelAll()

        currentChapterClips = clips
        // Clear audio href and filtered clips - they'll be set by setCurrentAudioHref()
        // This prevents stale href from previous chapter causing issues
        currentAudioHref = null
        currentAudioFileClips = emptyList()
        currentTrackIndex = 0
        // Reset completion flag for the new chapter
        hasNotifiedChapterExceeded = false
    }

    /**
     * Gets the current chapter clips.
     */
    fun getChapterClips(): List<MediaOverlayClip> = currentChapterClips

    /**
     * Sets the chapter start offset for position normalization.
     * The offset is the minimum start time of clips in the current audio file.
     * This allows position display to start at 0:00 for the chapter content.
     *
     * @param offsetMs The offset in milliseconds to subtract from raw position
     */
    fun setChapterStartOffset(offsetMs: Long) {
        _chapterStartOffset.value = offsetMs
    }

    /**
     * Sets the current audio file href being played.
     * This filters the chapter clips to only those belonging to this audio file,
     * which is essential for correct clip lookup in multi-audio-file chapters.
     *
     * When a chapter has multiple audio files, clips from different files may have
     * overlapping time ranges. This filter ensures findClipAtTime returns the correct
     * clip for the currently playing audio file.
     *
     * Also schedules ExoPlayer messages for precise clip change detection.
     *
     * @param audioHref The href of the audio file currently being played
     * @param trackIndex The index of this audio file in ExoPlayer's playlist (default 0)
     */
    fun setCurrentAudioHref(audioHref: Url, trackIndex: Int = 0) {
        if (audioHref != currentAudioHref || trackIndex != currentTrackIndex) {
            currentAudioHref = audioHref
            currentTrackIndex = trackIndex
            currentAudioFileClips = currentChapterClips.filter { it.audioHref == audioHref }

            // Schedule clip messages for precise clip change detection
            scheduleClipMessages()
        }
    }

    /**
     * Schedules ExoPlayer messages for all clips in the current audio file.
     * Messages fire at exact clip start times for precise text highlighting.
     *
     * This is called automatically when the audio file changes.
     * Can also be called manually if clips are updated after setting audio href.
     */
    private fun scheduleClipMessages() {
        // Cancel any previously scheduled messages
        clipMessageScheduler.cancelAll()

        // Schedule new messages for current audio file's clips
        val p = player
        if (p != null && currentAudioFileClips.isNotEmpty()) {
            Log.d(TAG, "Scheduling ${currentAudioFileClips.size} clip messages for track $currentTrackIndex")
            clipMessageScheduler.scheduleClips(
                player = p,
                clips = currentAudioFileClips,
                trackIndex = currentTrackIndex,
            )
        }
    }

    /**
     * Called when a scheduled clip message fires.
     * Updates the current locator for text highlighting.
     */
    private fun onClipMessageReceived(clip: MediaOverlayClip) {
        if (clip.fragmentId == null) return

        // Create a locator for the clip
        val locator = Locator(
            href = clip.textHref,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                fragments = listOf(clip.fragmentId),
            ),
        )

        // Only update if this is a different fragment
        val currentFragments = _currentLocatorWithClip.value?.locator?.locations?.fragments
        if (currentFragments != locator.locations.fragments) {
            Log.d(TAG, "Clip message: ${clip.fragmentId} at ${clip.startTime}s")
            _currentLocatorWithClip.value = LocatorWithClip(locator, clip)
        }
    }

    /**
     * Converts a normalized position (displayed to user) back to raw ExoPlayer position.
     * Used when the user seeks on the seek bar.
     *
     * @param normalizedPositionMs The position as shown to the user (starting from 0:00)
     * @return The raw ExoPlayer position with offset added
     */
    fun normalizedToRawPosition(normalizedPositionMs: Long): Long {
        return normalizedPositionMs + _chapterStartOffset.value
    }

    /**
     * Starts periodic position updates.
     * Should be called when playback starts.
     */
    fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            while (isActive) {
                player?.let { p -> _currentPosition.update { p.currentPosition } }
                updateCurrentLocator()
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops periodic position updates.
     * Should be called when playback pauses or stops.
     */
    fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Finds the clip for a given text fragment ID.
     *
     * @param fragmentId The fragment ID to find (e.g., "chapter44.xhtml-sentence50")
     * @return The matching clip, or null if not found
     */
    fun findClipForFragment(fragmentId: String?): MediaOverlayClip? {
        val result = fragmentId?.let {
            currentChapterClips.find { clip -> clip.fragmentId == it }
        }
        return result
    }

    /**
     * Finds the audio position in milliseconds for a given text fragment ID.
     *
     * @param fragmentId The fragment ID to find (e.g., "chapter44.xhtml-sentence50")
     * @return The start time in milliseconds, or null if not found
     */
    fun findPositionForFragment(fragmentId: String?): Long? {
        return findClipForFragment(fragmentId)
            ?.let { clip -> (clip.startTime * SECONDS_TO_MS).toLong() }
    }

    /**
     * Updates the current position to match a given text fragment ID.
     *
     * This is used when the user navigates while audio is not playing, so the seek bar
     * reflects where playback would start. Also seeks ExoPlayer so playback starts
     * from the correct position.
     *
     * @param fragmentId The fragment ID of the sentence (e.g., "chapter44.xhtml-sentence50")
     * @param skipSeek If true, only updates the internal position state without seeking ExoPlayer.
     *                 Used when the caller will switch audio files and seek there instead.
     * @return The matching clip if found (so caller can check if audio file switch is needed)
     */
    fun updatePositionForFragment(fragmentId: String, skipSeek: Boolean = false): MediaOverlayClip? {
        val matchingClip = findClipForFragment(fragmentId)
        val positionMs = matchingClip?.let { (it.startTime * SECONDS_TO_MS).toLong() }
        if (positionMs != null) {
            if (skipSeek) {
                // Only update internal state, don't seek ExoPlayer
                // This is used when audio file will be switched by caller
                _currentPosition.value = positionMs
            } else {
                setPosition(positionMs)
            }
        }
        return matchingClip
    }

    /**
     * Finds the audio position in milliseconds for a given text progression.
     * Uses the progression to estimate which clip corresponds to that position in the text.
     *
     * @param progression The text progression (0.0 to 1.0) through the chapter
     * @return The start time in milliseconds, or null if clips are empty
     */
    fun findPositionForProgression(progression: Double?): Long? {
        if (progression == null || progression <= 0.0 || currentChapterClips.isEmpty()) return null

        // Estimate which clip corresponds to this progression
        val clipIndex = (progression * currentChapterClips.size).toInt()
            .coerceIn(0, currentChapterClips.size - 1)

        val clip = currentChapterClips[clipIndex]
        return (clip.startTime * SECONDS_TO_MS).toLong()
    }

    /**
     * Updates the current locator based on the current playback position.
     * This is used to highlight the currently spoken text.
     *
     * Also detects when playback exceeds the chapter's clip range, which happens
     * when a single audio file contains multiple chapters. In this case, we need
     * to trigger chapter completion manually since ExoPlayer won't fire STATE_ENDED.
     */
    private fun updateCurrentLocator() {
        val currentTimeSeconds = (player?.currentPosition ?: 0L) / SECONDS_TO_MS

        // Find the clip that contains the current time using binary search
        // This is O(log n) instead of O(n), important since this runs every 100ms
        val currentClip = findClipAtTime(currentTimeSeconds)

        if (currentClip != null && currentClip.fragmentId != null) {
            // Create a locator for the current text fragment
            val locator = Locator(
                href = currentClip.textHref,
                mediaType = MediaType.XHTML,
                locations = Locator.Locations(
                    fragments = listOf(currentClip.fragmentId),
                ),
            )
            // Only update and notify when the locator changes
            val currentFragments = _currentLocatorWithClip.value?.locator?.locations?.fragments
            if (currentFragments != locator.locations.fragments) {
                _currentLocatorWithClip.value = LocatorWithClip(locator, currentClip)
            }
        } else {
            // No clip found - check if we've exceeded the chapter's clip range
            // This happens when a single audio file contains multiple chapters
            checkChapterExceeded(currentTimeSeconds)
        }
    }

    /**
     * Checks if playback has exceeded the current chapter's clip range.
     * If so, triggers the chapter completion callback (once per chapter).
     *
     * This handles the case where a single audio file contains audio for multiple
     * chapters. When the current chapter's clips end, ExoPlayer continues playing
     * into the next chapter's content. We detect this and trigger chapter completion.
     */
    private fun checkChapterExceeded(currentTimeSeconds: Double) {
        if (hasNotifiedChapterExceeded) return

        // Get the clips to check - prefer filtered clips for multi-audio chapters
        val clipsToCheck = currentAudioFileClips.ifEmpty { currentChapterClips }
        if (clipsToCheck.isEmpty()) return

        val lastClipEndTime = clipsToCheck.lastOrNull()?.endTime ?: return

        // If current position is past the last clip's end time, chapter is complete
        // Use a small threshold (200ms) to account for timing variations
        val thresholdSeconds = 0.2
        if (currentTimeSeconds > lastClipEndTime + thresholdSeconds) {
            hasNotifiedChapterExceeded = true
            onChapterClipsExceeded?.invoke()
        }
    }

    /**
     * Finds the clip containing the given time using binary search.
     *
     * Clips are assumed to be sorted by startTime (which they are from SMIL parsing).
     * Uses binary search to find the clip where startTime <= time < endTime.
     *
     * Important: This searches only within currentAudioFileClips (filtered by audio href).
     * In multi-audio-file chapters, clips from different audio files may have overlapping
     * time ranges. Searching the full chapter clips would return incorrect results.
     *
     * @param timeSeconds The time in seconds to search for
     * @return The clip containing the time, or null if not found
     */
    private fun findClipAtTime(timeSeconds: Double): MediaOverlayClip? {
        // Use filtered clips for current audio file to avoid returning clips from wrong file
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

    /**
     * Forces an update of the current position and locator.
     * Called after seeking to ensure UI reflects the new position.
     */
    fun forceUpdatePosition() {
        player?.let { _currentPosition.value = it.currentPosition }
        updateCurrentLocator()
    }

    /**
     * Releases resources. Call when the player is released.
     */
    fun release() {
        stopPositionUpdates()
        clipMessageScheduler.cancelAll()
        currentChapterClips = emptyList()
        currentAudioFileClips = emptyList()
        currentAudioHref = null
        currentTrackIndex = 0
        onChapterClipsExceeded = null
        hasNotifiedChapterExceeded = false
    }

}

