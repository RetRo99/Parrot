package com.retro99.books.ui.positions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

@Composable
internal fun PositionsApplySheet(state: PositionsViewState, previews: List<ApplyPreview>, dispatch: IntentDispatcher<PositionsIntent>) {
    val source = state.sheetSource
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PositionsText(stringResource(StringRes.positions_move_title), Modifier.weight(1f), title = true)
            val close = stringResource(StringRes.positions_close)
            Box(Modifier.semantics { contentDescription = close }) {
                PositionsLink("×") { if (!state.isApplying) dispatch(PositionsIntent.OnSheetDismissed) }
            }
        }
        source?.position?.let { position ->
            val label = copyLabel(source.copy, state, source)
            val place = positionText(position, source.copy)
            PositionsText(boldParts(stringResource(StringRes.positions_move_intro, label, place), label, place), fontSize = 14.sp)
        }
        previews.forEach { preview ->
            val enabled = preview.enabled && !state.isApplying
            val checked = preview.target.key.value in state.checkedKeys
            val shape = RoundedCornerShape(16.dp)
            val label = copyLabel(preview.target, state)
            val result = previewResultText(preview)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (preview.enabled) 1f else .5f)
                .clip(shape).background(if (Ember.style.isEink) Ember.colors.surface else Ember.colors.tile)
                .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { dispatch(PositionsIntent.OnTargetToggled(preview.target.key.value)) })
                .semantics(mergeDescendants = true) { contentDescription = "$label, $result" }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PositionCheckbox(checked, enabled)
                Column(Modifier.weight(1f)) {
                    PositionsText(label, fontSize = 15.sp, bold = true)
                    PositionsText(result, error = preview.warning != null ||
                        preview.translated?.confidence == TranslationConfidence.Approximate)
                }
            }
        }
        PositionsText(stringResource(StringRes.positions_move_footnote))
        val count = previews.count { it.enabled && it.target.key.value in state.checkedKeys }
        PositionsButton(
            if (state.isApplying) stringResource(StringRes.positions_updating)
            else stringResource(if (count == 1) StringRes.positions_update_one else StringRes.positions_update_many, count),
            { dispatch(PositionsIntent.OnApplyClicked) }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
            enabled = count > 0 && !state.isApplying, busy = state.isApplying,
        )
    }
}

/** Local-save wording lives here; a confirmed synced variant can be added later. */
@Composable
internal fun applyNoticeText(notice: PositionsNotice, state: PositionsViewState): String {
    if (notice.updated == 0) return stringResource(StringRes.positions_none_updated)
    val updated = stringResource(if (notice.updated == 1) StringRes.positions_updated_one else StringRes.positions_updated_many, notice.updated)
    return if (notice.failures.isEmpty()) stringResource(StringRes.positions_saved_syncing, updated,
        state.deviceName.replaceFirstChar { it.lowercase() })
    else stringResource(StringRes.positions_partial_update, updated,
        notice.failures.joinToString(", ") { state.serverNames[it.target.serverId] ?: it.target.home.name })
}

@Composable
internal fun PositionsSnackbar(notice: PositionsNotice, state: PositionsViewState, dispatch: IntentDispatcher<PositionsIntent>) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()
        .background(Ember.colors.surface, RoundedCornerShape(12.dp))
        .border(Ember.style.border, Ember.colors.line, RoundedCornerShape(12.dp))
        .semantics { liveRegion = LiveRegionMode.Polite }.padding(12.dp)) {
        PositionsText(applyNoticeText(notice, state))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (notice.updated == 0) PositionsLink(stringResource(StringRes.positions_try_again)) { dispatch(PositionsIntent.OnRetryApplyClicked) }
            else if (notice.failures.isNotEmpty()) PositionsLink(stringResource(StringRes.positions_failure_details)) { dispatch(PositionsIntent.OnFailureDetailsClicked) }
            PositionsLink(stringResource(StringRes.positions_close)) { dispatch(PositionsIntent.OnNoticeDismissed) }
        }
    }
}
