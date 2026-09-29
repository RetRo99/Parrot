package com.retro99.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val SHEET_TOP_RADIUS = 26.dp
private const val SCRIM_ALPHA = 0.62f

/**
 * Ember bottom sheet. In E-ink mode it is a plain popup pinned to the bottom of the window
 * with a 2dp outline, no scrim and no animation; otherwise it is a modal bottom sheet with
 * the themed scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmberBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(topStart = SHEET_TOP_RADIUS, topEnd = SHEET_TOP_RADIUS)

    if (style.isEink) {
        Popup(
            popupPositionProvider = WindowBottomPositionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, clippingEnabled = false),
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surface)
                    .border(2.dp, colors.line, shape)
                    .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier),
            ) {
                EmberSheetHandle()
                content()
                footer?.invoke()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = modifier,
            shape = shape,
            containerColor = colors.surface,
            contentColor = colors.ink,
            scrimColor = colors.nav.copy(alpha = SCRIM_ALPHA),
            dragHandle = { EmberSheetHandle() },
            content = {
                content()
                footer?.invoke()
            },
        )
    }
}

@Composable
private fun EmberSheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .background(Ember.colors.ink2, CircleShape),
        )
    }
}

/** Pins a popup to the bottom edge of the window, full width. */
private object WindowBottomPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(x = 0, y = windowSize.height - popupContentSize.height)
}
