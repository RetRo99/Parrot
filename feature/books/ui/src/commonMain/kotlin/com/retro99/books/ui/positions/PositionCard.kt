package com.retro99.books.ui.positions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

@Composable
internal fun PositionCard(row: CopyPositionRow, state: PositionsViewState, onClick: () -> Unit) {
    val selected = row.candidateId == state.selectedKey
    val available = row.position != null
    val shape = RoundedCornerShape(18.dp)
    val label = copyLabel(row.copy, state, row)
    val place = row.position?.let { positionText(it, row.copy) } ?: stringResource(StringRes.positions_not_started)
    val attribution = if (available) sourceText(row, state) else stringResource(StringRes.positions_no_reading)
    val latest = stringResource(StringRes.positions_latest)
    val unavailable = stringResource(StringRes.positions_unavailable)
    val description = listOfNotNull(label, place.replace("%", " percent"), attribution,
        latest.takeIf { row.isLatest }, unavailable.takeIf { !available }).joinToString(", ")
    Row(Modifier.fillMaxWidth().alpha(if (available) 1f else .55f).clip(shape)
        .background(if (selected && !Ember.style.isEink) Ember.colors.note else Ember.colors.surface)
        .border(if (selected || Ember.style.isEink) 2.dp else 1.dp,
            if (selected) Ember.colors.accent else Ember.colors.line, shape)
        .selectable(selected, enabled = available, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 2.dp)) { PositionRadio(selected) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(label, Modifier.weight(1f), style = Ember.type.meta.copy(fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, color = Ember.colors.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.isLatest) BasicText(latest, Modifier.background(Ember.colors.accent, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp), style = Ember.type.meta.copy(fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = Ember.colors.onAccent))
            }
            PositionsText(place, fontSize = 17.sp, bold = true)
            PositionsText(attribution)
            row.excerpt?.let { anchor ->
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(3.dp).height(30.dp).background(Ember.colors.chipBorder))
                    BasicText(positionSentence(anchor.before, anchor.after), style = Ember.type.bookTitle.copy(
                        fontSize = 14.sp, lineHeight = 19.sp, color = Ember.colors.ink2), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.isStale) PositionsText(stringResource(StringRes.positions_server_stale), error = true)
        }
    }
}

/** Preserve the sentence crossing the anchor; omit surrounding sentences and cursor markers. */
internal fun positionSentence(before: String, after: String): String {
    val left = before.split(Regex("(?<=[.!?])\\s+")).lastOrNull().orEmpty()
    val right = after.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
    return (left + right).trim()
}
