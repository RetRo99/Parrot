package com.retro99.reader.ui.reader

import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.ConflictDecision
import com.retro99.reader.ui.model.PositionConflictUiModel
import com.retro99.reader.ui.model.PositionSettleUi

/** The single prompt the reader shows when it starts, if any (§1.3). */
internal data class ReaderStartupPrompt(
    val linkedResumeOffer: LinkedResumeOffer? = null,
    val positionConflict: PositionConflictUiModel? = null,
    /** A self-settled conflict, announced by the quiet bar (spec §§1–2). */
    val positionSettleBar: PositionSettleUi? = null,
    val settleDecision: ConflictDecision = ConflictDecision.Ask,
)

/**
 * A newer reading in a linked copy replaces the same-copy conflict. When book detail already
 * settled either opening prompt ([linkedResumeResolved]), the reader asks nothing and doesn't
 * look again.
 *
 * Same-copy conflicts follow spec §1: only the genuinely ambiguous ones (mixed newer/further,
 * or a missing timestamp, or a missing percent) open the dialog; obvious ones settle
 * themselves with the quiet bar and differences under one percent say nothing at all.
 */
internal suspend fun readerStartupPrompt(
    linkedResumeResolved: Boolean,
    conflict: PositionConflictUiModel?,
    remoteName: String = "",
    thisDeviceName: String = "",
    findLinkedResume: suspend () -> LinkedResumeOffer?,
): ReaderStartupPrompt {
    if (linkedResumeResolved) return ReaderStartupPrompt()
    val offer = findLinkedResume()
    if (offer != null) return ReaderStartupPrompt(linkedResumeOffer = offer)
    val undecided = conflict ?: return ReaderStartupPrompt()
    return when (val decision = undecided.candidates.decision) {
        is ConflictDecision.KeepThis -> ReaderStartupPrompt(
            positionSettleBar = PositionSettleUi(
                candidates = undecided.candidates,
                remoteName = remoteName,
                thisDeviceName = thisDeviceName,
                movedToOther = false,
            ),
            settleDecision = decision,
        )
        is ConflictDecision.MoveToOther -> ReaderStartupPrompt(
            positionSettleBar = PositionSettleUi(
                candidates = undecided.candidates,
                remoteName = remoteName,
                thisDeviceName = thisDeviceName,
                movedToOther = true,
            ),
            settleDecision = decision,
        )
        // Under one percent the answer is not owed at all (spec §1).
        ConflictDecision.Silence -> ReaderStartupPrompt()
        ConflictDecision.Ask -> ReaderStartupPrompt(positionConflict = conflict)
    }
}
