package com.retro99.books.ui.positions

import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.usecase.ApplyResult

data class PositionsViewState(
    val bookTitle: String = "",
    val deviceName: String = "This device",
    val serverNames: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val loadError: Boolean = false,
    val isUnlinked: Boolean = false,
    val isPreviewing: Boolean = false,
    val rows: List<CopyPositionRow> = emptyList(),
    /** Stable candidate identity, retained across updates; radio selection never toggles off. */
    val selectedKey: String? = null,
    /** Where applying would put the other copies; the apply sheet is open while set. */
    val previews: List<ApplyPreview>? = null,
    /** Ticked targets, by copy key. */
    val checkedKeys: Set<String> = emptySet(),
    val isApplying: Boolean = false,
    /** What the last apply did, per target. */
    val results: List<ApplyResult>? = null,
    val notice: PositionsNotice? = null,
    val showFailureDetails: Boolean = false,
    val retrySource: CopyPositionRow? = null,
    val retryTargets: List<ApplyPreview> = emptyList(),
)

data class PositionsNotice(val updated: Int, val failures: List<ApplyResult>)

/** Explicit outcome partition; data-class equality must not determine success. */
fun positionApplyNotice(results: List<ApplyResult>): PositionsNotice {
    val (saved, failed) = results.partition {
        it.result == com.retro99.reader.domain.write.CopyWriteResult.Written
    }
    return PositionsNotice(saved.size, failed)
}
