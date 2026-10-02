package com.retro99.reader.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_find_back
import resources.translations.reader_find_back_no_percent
import kotlin.math.roundToInt

/**
 * "↶ Back to where I was · 34%" — one shared pill for every temporary return origin:
 * in-book search results and chapter/bookmark jumps from the Contents sheet.
 * The percentage is the book percentage of the origin; without one the pill omits it.
 */
@Composable
internal fun BackToOriginPill(
    origin: PositionUiModel?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (origin == null) return
    val colors = Ember.colors
    val eink = Ember.style.isEink
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surface),
        border = BorderStroke(if (eink) 2.dp else 1.dp, colors.chipBorder),
        modifier = modifier,
    ) {
        BackToOriginContent(origin)
    }
}

@Composable
internal fun RowScope.BackToOriginContent(origin: PositionUiModel) {
    val colors = Ember.colors
    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = colors.ink, modifier = Modifier.size(18.dp))
    Text(
        text = origin.totalProgression?.let { stringResource(StringRes.reader_find_back, (it * 100).roundToInt()) }
            ?: stringResource(StringRes.reader_find_back_no_percent),
        color = colors.ink,
        style = Ember.type.label,
        modifier = Modifier.padding(start = 8.dp),
    )
}
