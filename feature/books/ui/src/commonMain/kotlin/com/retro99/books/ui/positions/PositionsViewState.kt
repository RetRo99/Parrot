package com.retro99.books.ui.positions

import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.usecase.ApplyResult

data class PositionsViewState(
    val isLoading: Boolean = true,
    val rows: List<CopyPositionRow> = emptyList(),
    /** The row whose "Use this position" is shown. */
    val selectedKey: String? = null,
    /** Where applying would put the other copies; the apply sheet is open while set. */
    val previews: List<ApplyPreview>? = null,
    /** Ticked targets, by copy key. */
    val checkedKeys: Set<String> = emptySet(),
    val isApplying: Boolean = false,
    /** What the last apply did, per target. */
    val results: List<ApplyResult>? = null,
)
