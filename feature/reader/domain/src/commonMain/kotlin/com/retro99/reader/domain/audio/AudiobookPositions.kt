package com.retro99.reader.domain.audio

import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.audio.bookTimeMs
import com.retro99.server.api.audio.totalDurationMs
import com.retro99.server.api.audio.trackPosition

/**
 * An audiobook position at [offsetMs] into file [trackIndex] of [trackCount].
 *
 * `audioTimestampMs` and `chapterIndex` stay the file offset and file index, which the player
 * resumes from. `bookTimeMs`, `totalDurationMs`, `totalProgression` and `progression` are
 * whole-book, and null when the files' lengths aren't known: a per-file value would be wrong.
 */
fun buildAudiobookPosition(
    bookUuid: String,
    serverId: String,
    trackIndex: Int,
    offsetMs: Long,
    trackCount: Int,
    trackDurationsMs: List<Long>?,
    timestamp: Long,
): PositionDomainModel {
    val durations = trackDurationsMs
        ?.takeIf { lengths -> lengths.size == trackCount }
        .orEmpty()
    val bookTime = bookTimeMs(durations, trackIndex, offsetMs)
    val total = totalDurationMs(durations)?.takeIf { length -> length > 0 }
    val totalProgression = if (bookTime != null && total != null) {
        (bookTime.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    } else {
        null
    }
    return PositionDomainModel(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
        createdAt = null,
        updatedAt = null,
        locatorHref = null,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = offsetMs,
        chapterIndex = trackIndex,
        progression = totalProgression,
        totalChapters = trackCount,
        totalDurationMs = total.takeIf { _ -> bookTime != null },
        totalProgression = totalProgression,
        position = null,
        bookTimeMs = bookTime,
    )
}

/**
 * The files' lengths in playlist order: the player's timeline when every file's length is known
 * (null entries are unknown), else the lengths cached from the server, else null. A list that
 * doesn't match the playlist is never used.
 */
fun chooseTrackDurations(
    timelineDurationsMs: List<Long?>,
    cachedDurationsMs: List<Long>?,
    trackCount: Int,
): List<Long>? {
    if (trackCount <= 0) return null
    val timeline = timelineDurationsMs.filterNotNull()
    if (timelineDurationsMs.size == trackCount && timeline.size == trackCount) return timeline
    return cachedDurationsMs?.takeIf { cached -> cached.size == trackCount }
}

/**
 * Where to resume a saved audiobook position: (file index, offset in it), or null to stay at
 * the start. The book time wins when the lengths are known (or for a single file); otherwise
 * the saved file and offset.
 */
fun audiobookResumeTarget(
    saved: PositionDomainModel,
    trackDurationsMs: List<Long>?,
    trackCount: Int,
): Pair<Int, Long>? {
    val bookTime = saved.bookTimeMs
    if (bookTime != null) {
        val durations = trackDurationsMs?.takeIf { lengths -> lengths.size == trackCount }
        if (durations != null) return trackPosition(durations, bookTime)
        if (trackCount == 1) return 0 to bookTime.coerceAtLeast(0L)
    }
    val offset = saved.audioTimestampMs ?: return null
    val trackIndex = saved.chapterIndex ?: 0
    if (trackIndex !in 0 until trackCount) return null
    return trackIndex to offset.coerceAtLeast(0L)
}

/**
 * Playlist order for downloaded audio files, which are named by their 1-based index
 * (`01`, `02`, … `100`): by that number, so file 100 comes after 99, then by name.
 */
val audioTrackFileOrder: Comparator<String> =
    compareBy<String, Long?>(nullsLast()) { name ->
        name.takeWhile { char -> char.isDigit() }.toLongOrNull()
    }.thenBy { name -> name }
