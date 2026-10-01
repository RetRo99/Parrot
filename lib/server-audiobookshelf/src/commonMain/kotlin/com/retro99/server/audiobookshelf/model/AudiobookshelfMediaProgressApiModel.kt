package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

fun AudiobookshelfMediaProgressApiModel.toServerPosition(
    bookUuid: String,
    serverId: String,
): ServerPosition {
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
        audioTimestampMs = currentTime?.let { time -> (time * 1000).toLong() },
        chapterIndex = null,
        progression = progress,
        totalChapters = null,
        totalDurationMs = duration?.let { dur -> (dur * 1000).toLong() },
        totalProgression = ebookProgress ?: progress,
        position = null,
        origin = PositionOrigin.Remote,
        observedAt = lastUpdate?.let { millis -> Instant.fromEpochMilliseconds(millis).toString() },
    )
}

fun ServerPosition.toAudiobookshelfMediaProgress(
    libraryItemId: String,
): AudiobookshelfMediaProgressApiModel {
    return AudiobookshelfMediaProgressApiModel(
        libraryItemId = libraryItemId,
        duration = totalDurationMs?.let { it / 1000.0 },
        progress = progression,
        currentTime = audioTimestampMs?.let { it / 1000.0 },
        isFinished = null,
        hideFromContinueListening = null,
        lastUpdate = timestamp,
        startedAt = null,
        finishedAt = null,
        ebookLocation = locatorHref,
        ebookProgress = totalProgression,
    )
}
