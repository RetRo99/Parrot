package com.retro99.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One outlined pill with equal, borderless segments; no per-option chrome. */
@Composable
fun <T> EmberSegmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Ember.colors
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(CircleShape)
            .border(if (Ember.style.isEink) 2.dp else 1.5.dp, colors.chipBorder, CircleShape)
            .padding(3.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val active = selected == option
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                    .then(if (active) Modifier.background(colors.accent) else Modifier)
                    .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (active) colors.onAccent else colors.ink,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp,
                        lineHeight = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
        }
    }
}
