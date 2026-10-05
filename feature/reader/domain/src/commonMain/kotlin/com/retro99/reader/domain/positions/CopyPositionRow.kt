package com.retro99.reader.domain.positions

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.translate.TranslatedPosition
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor

/** Where a row's position came from, as the panel words it (§1.3b). */
sealed interface PositionSource {
    /** `user`: read on this device. */
    data object ThisDevice : PositionSource

    /** `remote`: pulled from the copy's server. */
    data object Server : PositionSource

    /** `manual` or `linked_copy`: set from another copy, when the write log names it. */
    data class SetFrom(val copy: LinkedCopy?) : PositionSource

    data object Restored : PositionSource

    /** No attributable source. */
    data object Unknown : PositionSource
}

/** One copy's current position in the positions panel. */
data class CopyPositionRow(
    val copy: LinkedCopy,
    val position: PositionDomainModel?,
    val observedAt: String?,
    val origin: PositionOrigin?,
    val sourceLabel: PositionSource,
    /** The newest real reading (P1). An echo or a written position never is. */
    val isLatest: Boolean,
    /** The server couldn't be reached; this is the last known position. */
    val isStale: Boolean,
    /** A few words around the position, when they're known. */
    val excerpt: TextAnchor?,
    /** Separate local/server candidates stay selectable without duplicating write targets. */
    val candidateId: String = copy.key.value,
    val isConflict: Boolean = false,
    val isLocalCandidate: Boolean = true,
)

enum class ApplyWarning {
    /** Guard 7: would move the copy to the start of the book. */
    CollapseToStart,

    /** Guard 8: would mark the copy as almost finished. */
    CollapseToEnd,
}

enum class ApplyDisabledReason {
    /** Guard 11: the copy's server can't take this kind of position yet. */
    NotSupported,

    /** No translation: the copy's file isn't here and there's no percentage to go on. */
    NoTranslation,
}

/** Where applying a position would put one other copy, and whether it's ticked. */
data class ApplyPreview(
    val target: LinkedCopy,
    val translated: TranslatedPosition?,
    val defaultChecked: Boolean,
    val enabled: Boolean,
    val warning: ApplyWarning?,
    val disabledReason: ApplyDisabledReason?,
)
