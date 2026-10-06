package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.reader.domain.linked.observedAtMillis
import com.retro99.reader.ui.model.PositionSettleUi
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import resources.translations.position_bar_go_back
import resources.translations.position_bar_kept_detail
import resources.translations.position_bar_kept_title
import resources.translations.position_bar_moved_detail
import resources.translations.position_bar_moved_title
import resources.translations.position_bar_use_other
import com.retro99.translations.StringRes
import kotlin.math.roundToInt

/** How long the settle bar waits before it disappears on its own. */
const val POSITION_SETTLE_BAR_MS = 6_000L

/**
 * The quiet bar after a self-settled position conflict (spec §2): "Kept your place · 86%"
 * or "Moved to 86%", one detail line and a single action that applies the other position
 * once, replacing the bar with the mirrored message. Same family as the saved bar:
 * above the progress strip / bottom panel, never overlapping them. Polite live region;
 * on e-ink a flat 2dp-outlined strip without animation that stays until the next page turn.
 */
@Composable
internal fun PositionSettleBar(
    model: PositionSettleUi,
    onSettle: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(18.dp)
    // TalkBack/VoiceOver must reach the action before the bar times out (§Accessibility).
    var accessibilityFocus by remember { mutableStateOf(false) }
    LaunchedEffect(model, accessibilityFocus, eink) {
        if (!eink && !accessibilityFocus) {
            delay(POSITION_SETTLE_BAR_MS)
            onTimeout()
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .onFocusEvent { accessibilityFocus = it.isFocused },
        shape = shape,
        color = colors.surface,
        shadowElevation = if (eink) 0.dp else 6.dp,
        border = if (eink) androidx.compose.foundation.BorderStroke(2.dp, colors.ink) else
            androidx.compose.foundation.BorderStroke(1.dp, colors.line),
    ) {
        Row(
            Modifier
                .heightIn(min = 56.dp)
                .padding(start = 18.dp, end = 6.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = settleTitle(model),
                    color = colors.ink,
                    style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = settleDetail(model),
                    color = colors.ink2,
                    style = Ember.type.meta.copy(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Text(
                text = settleActionLabel(model),
                color = if (eink) colors.ink else colors.accentText,
                style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button, onClick = onSettle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

internal fun PositionSettleUi.mirror(): PositionSettleUi = copy(movedToOther = !movedToOther)

private fun PositionSettleUi.currentPercent(): Int {
    val position = if (movedToOther) candidates.remotePosition else candidates.localPosition
    return displayPercent(position)
}

private fun PositionSettleUi.otherPercent(): Int {
    val position = if (movedToOther) candidates.localPosition else candidates.remotePosition
    return displayPercent(position)
}

private fun displayPercent(position: com.retro99.reader.domain.model.PositionDomainModel): Int =
    (position.totalProgression ?: 0.0).coerceIn(0.0, 1.0).let { (it * 100).roundToInt() }

private fun PositionSettleUi.otherObservedMillis(): Long? {
    val position = if (movedToOther) candidates.localPosition else candidates.remotePosition
    return position.observedAtMillis
}

@Composable
private fun settleTitle(model: PositionSettleUi): String {
    val percent = model.currentPercent()
    return if (model.movedToOther) {
        stringResource(StringRes.position_bar_moved_title, percent)
    } else {
        stringResource(StringRes.position_bar_kept_title, percent)
    }
}

@Composable
private fun settleDetail(model: PositionSettleUi): String {
    val percent = model.otherPercent()
    val whenText = model.otherObservedMillis()?.let { relativeTimeText(it) } ?: ""
    return if (model.movedToOther) {
        // "From Storyteller · This phone was at 78%, 3 hours ago"
        stringResource(
            StringRes.position_bar_moved_detail,
            model.remoteName,
            model.thisDeviceName.replaceFirstChar { it.lowercase() },
            percent,
        ).let { if (whenText.isNotEmpty()) "$it, $whenText" else it }
    } else {
        // "Storyteller was at 78%, 3 hours ago"
        stringResource(StringRes.position_bar_kept_detail, model.remoteName, percent, whenText)
    }
}

@Composable
private fun settleActionLabel(model: PositionSettleUi): String {
    val percent = model.otherPercent()
    return if (model.movedToOther) {
        stringResource(StringRes.position_bar_go_back, percent)
    } else {
        stringResource(StringRes.position_bar_use_other, percent)
    }
}
