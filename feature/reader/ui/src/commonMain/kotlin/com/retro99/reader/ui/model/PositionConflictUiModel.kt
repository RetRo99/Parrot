package com.retro99.reader.ui.model

import com.retro99.reader.domain.model.ConflictDecision
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
 * A self-settled conflict whose answer showed up as the quiet bar (spec §2). Keeps both
 * candidates so the bar's action can still apply the other side through the usual
 * resolve logic.
 */
data class PositionSettleUi(
    /** Full immutable candidates, including the generation and cloud revision. */
    val candidates: ReadingProgressResult.Conflict,
    /** The other copy's display name: "Storyteller", "Parrot Cloud", "Storytellr…". */
    val remoteName: String,
    /** What this device calls itself: "This phone", "This iPhone", "This tablet". */
    val thisDeviceName: String,
    /** True when the reader moved to the other position ("Moved to 86%"); false keeps. */
    val movedToOther: Boolean,
)

/**
 * Maps a [ReadingProgressResult] to UI models.
 * For conflicts, the initial position follows the decision: a self-settled
 * [ConflictDecision.MoveToOther] starts from the other position; everything
 * else keeps this device's position (the dialog answers later).
 */
fun ReadingProgressResult.toUiData(): ProgressResultUiData = when (this) {
    is ReadingProgressResult.Resolved -> ProgressResultUiData(
        position = position?.toUiModel(),
        conflict = null,
    )

    is ReadingProgressResult.Conflict -> ProgressResultUiData(
        position = when (decision) {
            is ConflictDecision.MoveToOther -> remotePosition.toUiModel()
            else -> localPosition.toUiModel()
        },
        conflict = PositionConflictUiModel(
            localPosition = localPosition.toUiModel(),
            remotePosition = remotePosition.toUiModel(),
            candidates = this,
        ),
    )
}
