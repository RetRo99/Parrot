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

/**
 * A position read from Audiobookshelf: book-level audio values (see [absAudioPlace]) and the
 * ebook location read by its shape (see [parseAbsEbookLocation]), keeping the raw value.
 */
suspend fun AudiobookshelfMediaProgressApiModel.toServerPosition(
    bookUuid: String,
    serverId: String,
    trackDurationsMs: List<Long>? = null,
    readingOrderHrefs: suspend () -> List<String>? = { null },
): ServerPosition {
    val place = absAudioPlace(currentTime, trackDurationsMs)
    val ebook = parseAbsEbookLocation(ebookLocation, ebookProgress, readingOrderHrefs)
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = lastUpdate,
        createdAt = startedAt?.toString(),
        updatedAt = lastUpdate?.toString(),
        locatorHref = ebook.href,
        locatorType = ebook.type,
        locatorTitle = null,
        locatorTarget = null,
        audioTimestampMs = place.offsetMs,
        chapterIndex = if (place.isListening) place.trackIndex else ebook.spineIndex,
        progression = if (place.isListening) progress else ebook.progression,
        totalChapters = trackDurationsMs?.size.takeIf { _ -> place.isListening },
        totalDurationMs = duration?.let { dur -> (dur * 1000).toLong() },
        totalProgression = if (place.isListening) progress else ebook.totalProgression ?: progress,
        position = null,
        cssSelector = ebook.cssSelector,
        bookTimeMs = place.bookTimeMs,
        ebookLocationRaw = ebookLocation,
        origin = PositionOrigin.Remote,
        observedAt = lastUpdate?.let { millis -> Instant.fromEpochMilliseconds(millis).toString() },
    )
}
