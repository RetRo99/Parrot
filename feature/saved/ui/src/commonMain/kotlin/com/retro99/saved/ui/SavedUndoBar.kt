package com.retro99.saved.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember

/**
 * The bar above the progress strip after saving or removing something: a message, an
 * optional "Add note" and "Undo". E-ink draws it as a flat bordered strip.
 */
@Composable
fun SavedUndoBar(
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    showBookmarkIcon: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = colors.surface,
        shadowElevation = if (eink) 0.dp else 8.dp,
        border = if (eink) androidx.compose.foundation.BorderStroke(2.dp, colors.ink) else
            androidx.compose.foundation.BorderStroke(1.dp, colors.line),
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBookmarkIcon) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = if (eink) colors.ink else colors.accentText,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                message,
                color = colors.ink,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                BarButton(actionLabel, onAction, if (eink) colors.ink else colors.accentText)
            }
            BarButton(undoLabel, onUndo, colors.ink)
        }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}
