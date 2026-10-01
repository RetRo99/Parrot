package com.retro99.reader.ui.model

import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.server.api.TextAnchor
import kotlinx.serialization.Serializable

@Serializable
data class PositionUiModel(
    val createdAt: String?,
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val chapterIndex: Int?,
    val totalChapters: Int?,
    val audioTimestampMs: Long? = null,
    val totalDurationMs: Long? = null,
    val cssSelector: String? = null,
    val textAnchor: TextAnchor? = null,
)

fun PositionDomainModel.toUiModel(): PositionUiModel {
    return PositionUiModel(
        createdAt = createdAt,
        href = locatorHref ?: "",
        type = locatorType ?: "",
        title = locatorTitle,
        progression = progression,
        position = position,
        totalProgression = totalProgression,
        chapterIndex = chapterIndex,
        totalChapters = totalChapters,
        audioTimestampMs = audioTimestampMs,
        totalDurationMs = totalDurationMs,
        cssSelector = cssSelector,
        textAnchor = textAnchor,
    )
}

/**
 * Creates a [PositionUiModel] from this [LocatorState].
 *
 * If [basePosition] is provided, fields not present in the locator (like chapterIndex,
 * totalChapters, audioTimestampMs, etc.) are preserved from the base position.
 * If [basePosition] is null, a new position is created with [createdAt] timestamp.
 *
 * @param basePosition Optional existing position to preserve non-locator fields from
 * @param createdAt Timestamp to use when creating a new position (ignored if basePosition is provided)
 */
fun LocatorState.toPositionUiModel(
    basePosition: PositionUiModel?,
    createdAt: String,
): PositionUiModel {
    return PositionUiModel(
        createdAt = basePosition?.createdAt ?: createdAt,
        href = href,
        type = type,
        title = title,
        progression = progression,
        position = position,
        totalProgression = totalProgression,
        chapterIndex = basePosition?.chapterIndex,
        totalChapters = basePosition?.totalChapters,
        audioTimestampMs = basePosition?.audioTimestampMs,
        totalDurationMs = basePosition?.totalDurationMs,
        cssSelector = cssSelector,
        textAnchor = textAnchor,
    )
}