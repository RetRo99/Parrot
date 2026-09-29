package com.retro99.base.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val PROGRESS_ANIMATION_MILLIS = 600

/**
 * Progress bar: thin and flat normally, thick and outlined in e-ink mode.
 * [progress] is clamped to 0f..1f.
 */
@Composable
fun EmberProgress(
    progress: Float,
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ember.colors.accent,
) {
    val colors = Ember.colors
    val style = Ember.style
    val target = progress.coerceIn(0f, 1f)
    val shown = if (style.animations) {
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = PROGRESS_ANIMATION_MILLIS),
            label = "emberProgress",
        )
        animated
    } else {
        target
    }
    val outline = if (style.progressOutlined) {
        Modifier.border(style.border, colors.line, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(colors.track)
            .then(outline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .background(color),
        )
    }
}

/**
 * Book cover frame. The caller sets the size through [modifier]; the frame adds the
 * theme's shadow (none in e-ink) and border, and clips the image. [content] draws overlays.
 */
@Composable
fun EmberCover(
    data: Any?,
    cacheKey: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(4.dp)
    val shadow = if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier
    val border = if (colors.coverBorder != Color.Transparent) {
        val width = if (style.progressOutlined) 2.dp else 1.dp
        Modifier.border(width, colors.coverBorder, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(shadow)
            .clip(shape)
            .then(border),
    ) {
        CoilImage(
            data = data,
            cacheKey = cacheKey,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}
