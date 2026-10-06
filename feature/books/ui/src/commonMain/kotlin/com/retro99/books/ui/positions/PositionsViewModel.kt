package com.retro99.books.ui.positions

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.trackUsageOperation
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.CopyPositionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

@KoinViewModel
class PositionsViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onBack: () -> Unit,
    @InjectedParam private val onOpenVersion: (String, String) -> Unit,
    @InjectedParam private val initialBookTitle: String,
    @Provided private val data: PositionsDataSource,
    @Provided private val analytics: Analytics,
) : BaseViewModel<PositionsViewState, PositionsIntent>(PositionsViewState(bookTitle = initialBookTitle)) {
    private var loadJob: Job? = null
    private var previewJob: Job? = null

    init {
        loadRows()
        viewModelScope.launch {
            data.changes().drop(1).collect {
                if (!viewState.value.isApplying) loadRows(refresh = true)
            }
        }
    }

    override fun onIntent(intent: PositionsIntent) {
        when (intent) {
            PositionsIntent.OnBackClicked -> onBack()
            is PositionsIntent.OnOpenVersion -> onOpenVersion(intent.serverId, intent.uuid)
            is PositionsIntent.OnRowClicked -> {
                val state = viewState.value
                if (state.isApplying || state.previews != null) return
                if (state.rows.none { it.candidateId == intent.copyKey && it.position != null }) return
                previewJob?.cancel()
                updateState { it.copy(selectedKey = intent.copyKey, isPreviewing = false) }
            }
            PositionsIntent.OnUseThisPositionClicked -> preview()
            is PositionsIntent.OnTargetToggled -> updateState { state ->
                if (state.isApplying || state.previews.orEmpty().none {
                        it.enabled && it.target.key.value == intent.copyKey
                    }) state
                else state.copy(checkedKeys = if (intent.copyKey in state.checkedKeys)
                    state.checkedKeys - intent.copyKey else state.checkedKeys + intent.copyKey)
            }
            PositionsIntent.OnApplyClicked -> {
                val state = viewState.value
                val source = state.sheetSource ?: return
                apply(source, state.previews.orEmpty().filter { it.enabled && it.target.key.value in state.checkedKeys })
            }
            PositionsIntent.OnSheetDismissed -> {
                if (viewState.value.isApplying) return
                previewJob?.cancel()
                updateState { it.copy(previews = null, sheetSource = null, checkedKeys = emptySet(), results = null, isPreviewing = false) }
            }
            PositionsIntent.OnRefresh, PositionsIntent.OnResume -> if (!viewState.value.isApplying) loadRows(refresh = true)
            PositionsIntent.OnNoticeDismissed -> updateState { it.copy(notice = null) }
            PositionsIntent.OnFailureDetailsClicked -> updateState { it.copy(showFailureDetails = true) }
            PositionsIntent.OnFailureDetailsDismissed -> updateState { it.copy(showFailureDetails = false) }
            PositionsIntent.OnRetryApplyClicked -> {
                val state = viewState.value
                state.retrySource?.let { apply(it, state.retryTargets) }
            }
        }
    }

    private fun loadRows(refresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            updateState { it.copy(isRefreshing = refresh, isLoading = it.rows.isEmpty(), loadError = false) }
            try {
                val metadata = data.metadata(serverId, bookUuid)
                updateState { it.copy(bookTitle = metadata.bookTitle.ifBlank { it.bookTitle }, deviceName = metadata.deviceName,
                    serverNames = metadata.serverNames) }
                val rows = data.load(serverId, bookUuid)
                updateState { state -> state.copy(
                    isLoading = false, isRefreshing = false, isUnlinked = rows == null,
                    rows = rows.orEmpty(),
                    bookTitle = metadata.bookTitle.ifBlank { rows?.firstOrNull()?.copy?.title ?: state.bookTitle },
                    selectedKey = refreshedSelection(state, rows.orEmpty()),
                    previews = state.previews.takeIf { rows != null },
                    sheetSource = state.sheetSource.takeIf { rows != null },
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                updateState { it.copy(isLoading = false, isRefreshing = false, loadError = true, isUnlinked = false) }
            }
        }
    }

    private fun refreshedSelection(state: PositionsViewState, rows: List<CopyPositionRow>): String? {
        val key = state.selectedKey ?: return null
        rows.firstOrNull { it.candidateId == key && it.position != null }?.let { return it.candidateId }
        val previous = state.rows.firstOrNull { it.candidateId == key } ?: return null
        // A disagreement resolving changes candidateId from key|local/server back to key.
        return rows.firstOrNull {
            it.copy.key == previous.copy.key && it.position != null &&
                (!it.isConflict || it.isLocalCandidate == previous.isLocalCandidate)
        }?.candidateId
    }

    private fun preview() {
        val state = viewState.value
        if (state.isApplying || state.isPreviewing || state.loadError || state.isUnlinked) return
        val source = state.rows.firstOrNull { it.candidateId == state.selectedKey && it.position != null } ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            updateState { it.copy(isPreviewing = true) }
            try {
                val previews = data.preview(source, state.rows)
                updateState { it.copy(previews = previews, sheetSource = source,
                    checkedKeys = previews.filter { it.enabled && it.defaultChecked }.mapTo(mutableSetOf()) { it.target.key.value },
                    results = null, isPreviewing = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                updateState { it.copy(isPreviewing = false, loadError = true) }
            }
        }
    }

    private fun apply(source: CopyPositionRow, targets: List<ApplyPreview>) {
        if (viewState.value.isApplying || targets.isEmpty()) return
        loadJob?.cancel()
        updateState { it.copy(isApplying = true, notice = null) }
        viewModelScope.launch {
            try {
                val results = analytics.trackUsageOperation(
                    UsageOperation.ApplyPosition, UsageAction.Apply, "positions", targets.size,
                    outcome = { results ->
                        val notice = positionApplyNotice(results)
                        when {
                            notice.updated == targets.size -> ProductOutcome.Succeeded
                            notice.updated == 0 -> ProductOutcome.Failed
                            else -> ProductOutcome.Partial
                        }
                    },
                ) { data.apply(source, targets) }
                updateState { it.copy(isApplying = false, previews = null, sheetSource = null, checkedKeys = emptySet(),
                    results = results, notice = positionApplyNotice(results), retrySource = source, retryTargets = targets) }
                loadRows(refresh = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                updateState { it.copy(isApplying = false, previews = null, sheetSource = null, checkedKeys = emptySet(),
                    notice = PositionsNotice(0, emptyList()), retrySource = source, retryTargets = targets) }
                loadRows(refresh = true)
            }
        }
    }
}
