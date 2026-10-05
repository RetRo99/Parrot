package com.retro99.reader.ui.model

import com.retro99.reader.domain.model.ReadingProgressResult

/**
 * Represents a conflict between local and remote reading positions.
 * Used to display a dialog for the user to choose which position to use.
 */
data class PositionConflictUiModel(
    val localPosition: PositionUiModel,
    val remotePosition: PositionUiModel,
    /** Full immutable candidates, including the generation and cloud revision. */
    val candidates: ReadingProgressResult.Conflict,
)

/**
 * Result of mapping [ReadingProgressResult] to UI models.
 */
data class ProgressResultUiData(
    val position: PositionUiModel?,
    val conflict: PositionConflictUiModel?,
)

/**
 * Maps a [ReadingProgressResult] to UI models.
 * For conflicts, returns the local position as the initial position.
 */
fun ReadingProgressResult.toUiData(): ProgressResultUiData = when (this) {
    is ReadingProgressResult.Resolved -> ProgressResultUiData(
        position = position?.toUiModel(),
        conflict = null,
    )

    is ReadingProgressResult.Conflict -> ProgressResultUiData(
        position = localPosition.toUiModel(),
        conflict = PositionConflictUiModel(
            localPosition = localPosition.toUiModel(),
            remotePosition = remotePosition.toUiModel(),
            candidates = this,
        ),
    )
}
