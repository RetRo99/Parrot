package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.audio.trackPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong
import kotlin.time.Instant

@Serializable
data class AudiobookshelfMediaProgressApiModel(
    @SerialName("id")
    val id: String? = null,

    @SerialName("libraryItemId")
    val libraryItemId: String? = null,

    @SerialName("episodeId")
    val episodeId: String? = null,

    @SerialName("duration")
    val duration: Double? = null,

    @SerialName("progress")
    val progress: Double? = null,

    @SerialName("currentTime")
    val currentTime: Double? = null,

    @SerialName("isFinished")
    val isFinished: Boolean? = null,

    @SerialName("hideFromContinueListening")
    val hideFromContinueListening: Boolean? = null,

    @SerialName("lastUpdate")
    val lastUpdate: Long? = null,

    @SerialName("startedAt")
    val startedAt: Long? = null,

    @SerialName("finishedAt")
    val finishedAt: Long? = null,

    @SerialName("ebookLocation")
    val ebookLocation: String? = null,

    @SerialName("ebookProgress")
    val ebookProgress: Double? = null,
)

/**
 * Our audio fields for Audiobookshelf's book-level `currentTime`: the book time, plus the file
 * and the offset in it when each file's length is known (null otherwise, never a guess).
 */
internal data class AbsAudioPlace(
    val bookTimeMs: Long?,
    val trackIndex: Int?,
    val offsetMs: Long?,
) {
    /** Audiobookshelf reports `currentTime` 0 for items only read as ebooks. */
    val isListening: Boolean get() = (bookTimeMs ?: 0L) > 0L
}

internal fun absAudioPlace(
    currentTimeSeconds: Double?,
    trackDurationsMs: List<Long>?,
): AbsAudioPlace {
    val bookTime = currentTimeSeconds?.let { seconds -> (seconds * 1000).roundToLong() }
    val place = bookTime?.let { time -> trackPosition(trackDurationsMs.orEmpty(), time) }
    return AbsAudioPlace(
        bookTimeMs = bookTime,
        trackIndex = place?.first,
        offsetMs = place?.second,
    )
}

fun AudiobookshelfMediaProgressApiModel.toServerPosition(
    bookUuid: String,
    serverId: String,
    trackDurationsMs: List<Long>? = null,
): ServerPosition {
    val place = absAudioPlace(currentTime, trackDurationsMs)
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = lastUpdate,
        createdAt = startedAt?.toString(),
        updatedAt = lastUpdate?.toString(),
        locatorHref = ebookLocation,
        locatorType = null,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = place.offsetMs,
        chapterIndex = place.trackIndex,
        progression = progress,
        totalChapters = trackDurationsMs?.size,
        totalDurationMs = duration?.let { dur -> (dur * 1000).toLong() },
        totalProgression = if (place.isListening) progress else ebookProgress ?: progress,
        position = null,
        bookTimeMs = place.bookTimeMs,
        origin = PositionOrigin.Remote,
        observedAt = lastUpdate?.let { millis -> Instant.fromEpochMilliseconds(millis).toString() },
    )
}

fun ServerPosition.toAudiobookshelfMediaProgress(
    libraryItemId: String,
): AudiobookshelfMediaProgressApiModel {
    // Book-level values only: a file offset is the book time only for a single-file book.
    val isSingleFile = (totalChapters ?: 1) <= 1 && (chapterIndex ?: 0) == 0
    val isBookLevel = bookTimeMs != null || isSingleFile
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryItemId,
        duration = totalDurationMs?.takeIf { _ -> isBookLevel }?.let { ms -> ms / 1000.0 },
        progress = progression?.takeIf { _ -> isBookLevel },
        currentTime = (bookTimeMs ?: audioTimestampMs?.takeIf { _ -> isSingleFile })
            ?.let { ms -> ms / 1000.0 },
        isFinished = null,
        hideFromContinueListening = null,
        lastUpdate = timestamp,
        startedAt = null,
        finishedAt = null,
        ebookLocation = locatorHref,
        ebookProgress = totalProgression,
    )
}
