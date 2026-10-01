package com.retro99.server.storyteller.model

import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerPosition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Storyteller API model for reading position.
 * This matches the Storyteller server's /api/v2/books/{uuid}/positions endpoint.
 */
@Serializable
data class StorytellerPositionApiModel(
    @SerialName("locator")
    val locator: StorytellerLocatorApiModel? = null,

    @SerialName("timestamp")
    val timestamp: Long? = null,

    @SerialName("createdAt")
    val createdAt: String? = null,

    @SerialName("updatedAt")
    val updatedAt: String? = null,
)

@Serializable
data class StorytellerLocatorApiModel(
    @SerialName("href")
    val href: String? = null,

    @SerialName("type")
    val type: String? = null,

    @SerialName("title")
    val title: String? = null,

    @SerialName("target")
    val target: Int? = null,

    @SerialName("locations")
    val locations: StorytellerLocationsApiModel? = null,
)

@Serializable
data class StorytellerLocationsApiModel(
    @SerialName("audioTimestampMs")
    val audioTimestampMs: Long? = null,

    @SerialName("chapterIndex")
    val chapterIndex: Int? = null,

    @SerialName("progression")
    val progression: Double? = null,

    @SerialName("totalChapters")
    val totalChapters: Int? = null,

    @SerialName("totalDurationMs")
    val totalDurationMs: Long? = null,

    @SerialName("totalProgression")
    val totalProgression: Double? = null,

    @SerialName("position")
    val position: Int? = null,
)

/**
 * Converts Storyteller API model to server-agnostic ServerPosition.
 */
fun StorytellerPositionApiModel.toServerPosition(bookUuid: String, serverId: String): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locator?.href,
        locatorType = locator?.type,
        locatorTitle = locator?.title,
        locatorTarget = locator?.target,
        audioTimestampMs = locator?.locations?.audioTimestampMs,
        chapterIndex = locator?.locations?.chapterIndex,
        progression = locator?.locations?.progression,
        totalChapters = locator?.locations?.totalChapters,
        totalDurationMs = locator?.locations?.totalDurationMs,
        totalProgression = locator?.locations?.totalProgression,
        position = locator?.locations?.position,
        origin = PositionOrigin.Remote,
        // The timestamp is the reading time the writing client sent.
        observedAt = timestamp?.let { millis -> Instant.fromEpochMilliseconds(millis).toString() }
            ?: updatedAt
            ?: createdAt,
    )
}

/**
 * Converts server-agnostic ServerPosition to Storyteller API model for saving.
 * Note: createdAt and updatedAt are server-managed fields and should not be sent
 * when saving positions - the server will set these automatically.
 */
fun ServerPosition.toStorytellerApiModel(): StorytellerPositionApiModel {
    return StorytellerPositionApiModel(
        timestamp = timestamp,
        // Don't send createdAt/updatedAt - these are server-managed fields
        createdAt = null,
        updatedAt = null,
        locator = StorytellerLocatorApiModel(
            href = locatorHref,
            type = locatorType,
            title = locatorTitle,
            target = locatorTarget,
            locations = StorytellerLocationsApiModel(
                audioTimestampMs = audioTimestampMs,
                chapterIndex = chapterIndex,
                progression = progression,
                totalChapters = totalChapters,
                totalDurationMs = totalDurationMs,
                totalProgression = totalProgression,
                position = position,
            ),
        ),
    )
}

