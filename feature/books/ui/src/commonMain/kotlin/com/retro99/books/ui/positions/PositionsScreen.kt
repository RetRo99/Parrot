package com.retro99.books.ui.positions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*

@Composable
fun PositionsScreen(
    serverId: String,
    bookUuid: String,
    onBack: () -> Unit,
    onOpenVersion: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PositionsViewModel = koinViewModel { parametersOf(serverId, bookUuid, onBack, onOpenVersion) },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onIntent(PositionsIntent.OnResume)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BaseScreen(modifier = modifier, viewModel = viewModel) { state, dispatcher ->
        PositionsScreenContent(state, dispatcher)
    }
}

/** Public production renderer also hosted by the isolated Ember fixture profile. */
@Composable
fun PositionsScreenContent(
    viewState: PositionsViewState,
    intentDispatcher: IntentDispatcher<PositionsIntent>,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val dispatch by rememberUpdatedState(intentDispatcher)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val refresh = remember(list, threshold) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !list.canScrollBackward) {
                    pulled = (pulled + available.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled >= threshold) dispatch(PositionsIntent.OnRefresh)
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    val refreshLabel = stringResource(StringRes.positions_refresh)
    Column(modifier.fillMaxSize().background(Ember.colors.bg).statusBarsPadding()) {
        EmberTopBar(title = stringResource(StringRes.positions_action), onBack = { dispatch(PositionsIntent.OnBackClicked) })
        PositionsText(
            boldParts(stringResource(StringRes.positions_intro, viewState.bookTitle), viewState.bookTitle),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), fontSize = 14.sp,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                viewState.isLoading -> Column(Modifier.align(Alignment.TopCenter).padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    PositionsBusy()
                    PositionsText(stringResource(StringRes.positions_loading))
                }
                viewState.loadError -> PositionsEmptyState(
                    stringResource(StringRes.positions_load_error), stringResource(StringRes.positions_load_error_help),
                    stringResource(StringRes.positions_try_again), { dispatch(PositionsIntent.OnRefresh) },
                )
                viewState.isUnlinked -> PositionsEmptyState(stringResource(StringRes.positions_unlinked), "",
                    stringResource(StringRes.general_back), { dispatch(PositionsIntent.OnBackClicked) })
                else -> LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().nestedScroll(refresh).selectableGroup().semantics {
                        customActions = listOf(CustomAccessibilityAction(refreshLabel) {
                            dispatch(PositionsIntent.OnRefresh); true
                        })
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (viewState.isRefreshing) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            PositionsBusy(); PositionsText(stringResource(StringRes.positions_loading))
                        }
                    }
                    viewState.rows.forEachIndexed { index, row ->
                        item(key = row.candidateId) {
                            PositionCard(row, viewState, onClick = { dispatch(PositionsIntent.OnRowClicked(row.candidateId)) })
                        }
                        if (row.isConflict && viewState.rows.getOrNull(index + 1)?.copy?.key != row.copy.key) {
                            item(key = "pair:${row.copy.key.value}") {
                                Column(Modifier.padding(horizontal = 4.dp)) {
                                    PositionsText(stringResource(StringRes.positions_pair_note))
                                    PositionsLink(stringResource(StringRes.positions_open_version)) {
                                        dispatch(PositionsIntent.OnOpenVersion(row.copy.serverId, row.copy.uuid))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        viewState.notice?.let { notice -> PositionsSnackbar(notice, viewState, dispatch) }
        if (!viewState.isLoading && !viewState.loadError && !viewState.isUnlinked) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ember.colors.line))
            PositionsButton(
                stringResource(if (viewState.isPreviewing) StringRes.positions_loading else StringRes.positions_use_selected),
                onClick = { dispatch(PositionsIntent.OnUseThisPositionClicked) },
                enabled = viewState.selectedKey != null && !viewState.isPreviewing,
                modifier = Modifier.padding(20.dp).navigationBarsPadding().fillMaxWidth(),
            )
        }
    }
    viewState.previews?.let { previews ->
        EmberBottomSheet(onDismiss = { if (!viewState.isApplying) dispatch(PositionsIntent.OnSheetDismissed) }) {
            PositionsApplySheet(viewState, previews, dispatch)
        }
    }
    if (viewState.showFailureDetails) {
        EmberBottomSheet(onDismiss = { dispatch(PositionsIntent.OnFailureDetailsDismissed) }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PositionsText(stringResource(StringRes.positions_failure_title), title = true)
                viewState.notice?.failures.orEmpty().forEach { failure ->
                    PositionsText(copyLabel(failure.target, viewState) + " · " + writeFailureText(failure.result), error = true)
                }
                PositionsButton(stringResource(StringRes.positions_close), { dispatch(PositionsIntent.OnFailureDetailsDismissed) })
            }
        }
    }
}
