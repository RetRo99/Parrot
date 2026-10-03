package com.retro99.books.ui.positions

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.trackUsageOperation
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.usecase.ApplyPositionUseCase
import com.retro99.reader.domain.usecase.ObserveCopyPositionsUseCase
import com.retro99.reader.domain.usecase.PreviewApplyPositionUseCase
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/** The positions panel (§1.3b): every copy's position, and applying one to the others. */
@KoinViewModel
class PositionsViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onBack: () -> Unit,
    @Provided private val observeCopyPositionsUseCase: ObserveCopyPositionsUseCase,
    @Provided private val previewApplyPositionUseCase: PreviewApplyPositionUseCase,
    @Provided private val applyPositionUseCase: ApplyPositionUseCase,
    @Provided private val analytics: Analytics,
) : BaseViewModel<PositionsViewState, PositionsIntent>(PositionsViewState()) {

    init {
        loadRows()
    }

    override fun onIntent(intent: PositionsIntent) {
        when (intent) {
            PositionsIntent.OnBackClicked -> onBack()

            is PositionsIntent.OnRowClicked -> updateState { state ->
                state.copy(
                    selectedKey = intent.copyKey.takeIf { key -> key != state.selectedKey },
                )
            }

            PositionsIntent.OnUseThisPositionClicked -> preview()

            is PositionsIntent.OnTargetToggled -> updateState { state ->
                val checked = if (intent.copyKey in state.checkedKeys) {
                    state.checkedKeys - intent.copyKey
                } else {
                    state.checkedKeys + intent.copyKey
                }
                state.copy(checkedKeys = checked)
            }

            PositionsIntent.OnApplyClicked -> apply()

            PositionsIntent.OnSheetDismissed -> updateState { state ->
                state.copy(previews = null, checkedKeys = emptySet(), results = null)
            }
        }
    }

    private fun loadRows() {
        viewModelScope.launch {
            val rows = observeCopyPositionsUseCase(serverId, bookUuid).orEmpty()
            updateState { state -> state.copy(isLoading = false, rows = rows) }
        }
    }

    private fun preview() {
        val state = viewState.value
        val source = state.rows.firstOrNull { row -> row.copy.key.value == state.selectedKey }
            ?: return
        viewModelScope.launch {
            val previews = previewApplyPositionUseCase(source, state.rows)
            updateState { state ->
                state.copy(
                    previews = previews,
                    checkedKeys = previews
                        .filter { preview -> preview.defaultChecked }
                        .mapTo(mutableSetOf()) { preview -> preview.target.key.value },
                    results = null,
                )
            }
        }
    }

    private fun apply() {
        val state = viewState.value
        if (state.isApplying) return
        val source = state.rows.firstOrNull { row -> row.copy.key.value == state.selectedKey }
            ?: return
        val ticked = state.previews.orEmpty()
            .filter { preview -> preview.enabled && preview.target.key.value in state.checkedKeys }
        if (ticked.isEmpty()) return
        updateState { current -> current.copy(isApplying = true) }
        viewModelScope.launch {
            val results = analytics.trackUsageOperation(
                UsageOperation.ApplyPosition, UsageAction.Apply, "positions", ticked.size,
                outcome = { results ->
                    val written = results.count { it.result == CopyWriteResult.Written }
                    when {
                        written == ticked.size -> ProductOutcome.Succeeded
                        written == 0 -> ProductOutcome.Failed
                        else -> ProductOutcome.Partial
                    }
                },
            ) { applyPositionUseCase(source, ticked) }
            val rows = observeCopyPositionsUseCase(serverId, bookUuid).orEmpty()
            updateState { current ->
                current.copy(isApplying = false, results = results, rows = rows)
            }
        }
    }
}
