package com.retro99.reader.ui.reader

import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.ui.model.PositionConflictUiModel

/** The single prompt the reader shows when it starts, if any (§1.3). */
internal data class ReaderStartupPrompt(
    val linkedResumeOffer: LinkedResumeOffer? = null,
    val positionConflict: PositionConflictUiModel? = null,
)

/**
 * A newer reading in a linked copy replaces the same-copy conflict. When book detail already
 * asked ([linkedResumeResolved]), the reader asks nothing and doesn't look again.
 */
internal suspend fun readerStartupPrompt(
    linkedResumeResolved: Boolean,
    positionConflict: PositionConflictUiModel?,
    findLinkedResume: suspend () -> LinkedResumeOffer?,
): ReaderStartupPrompt {
    if (linkedResumeResolved) return ReaderStartupPrompt()
    val offer = findLinkedResume()
    return if (offer != null) {
        ReaderStartupPrompt(linkedResumeOffer = offer)
    } else {
        ReaderStartupPrompt(positionConflict = positionConflict)
    }
}
