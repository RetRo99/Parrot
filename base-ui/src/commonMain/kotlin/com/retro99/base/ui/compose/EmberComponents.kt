package com.retro99.base.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import androidx.compose.ui.text.style.TextOverflow

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
    fallbackLabel: String? = contentDescription,
    fallback: (@Composable BoxScope.() -> Unit)? = null,
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
    var imageLoaded by remember(data, cacheKey) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .then(shadow)
            .clip(shape)
            .then(border),
    ) {
        if (data != null) {
            CoilImage(
                data = data,
                cacheKey = cacheKey,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onState = { state -> imageLoaded = state is AsyncImagePainter.State.Success },
            )
        }
        if (!imageLoaded) {
            if (fallback != null) {
                fallback()
            } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(if (style.isEink) colors.surface else colors.track),
                contentAlignment = Alignment.Center,
            ) {
                val initials = fallbackInitials(fallbackLabel)
                if (initials.isNotEmpty()) {
                    Text(
                        text = initials,
                        style = Ember.type.screenTitle.copy(fontSize = 18.sp, lineHeight = 20.sp),
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            }
        }
        content()
    }
}

private fun fallbackInitials(title: String?): String {
    val words = title.orEmpty().trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return when {
        words.size > 1 -> words.take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase()
        words.size == 1 -> words.first().take(2).uppercase()
        else -> ""
    }
}

/**
 * Selectable pill chip. A selected chip shows a check icon in addition to its fill,
 * so selection never relies on color alone. Use [role] [Role.RadioButton] for single
 * select groups and [Role.Checkbox] for multi select groups.
 */
@Composable
fun EmberChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Checkbox,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = CircleShape
    val background = if (selected) colors.navActive else Color.Transparent
    val outline = if (selected) colors.accent else colors.chipBorder
    val content = if (selected) colors.chipSelectedText else colors.ink

    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(background)
            .border(style.border, outline, shape)
            .selectable(selected = selected, role = role, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = content,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = Ember.type.meta.copy(
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = content,
            maxLines = 1,
        )
    }
}

/** Small caps section label used above chip groups and menu sections. */
@Composable
fun EmberSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = Ember.type.eyebrow.copy(
            fontSize = if (Ember.style.isEink) 13.sp else 11.sp,
            letterSpacing = 1.5.sp,
        ),
        color = Ember.colors.ink2,
        modifier = modifier,
    )
}

/**
 * Rounded surface card with the theme's outline in E-ink mode. Pass [onClick] to make the
 * whole card a button.
 */
@Composable
fun EmberCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(20.dp)
    val outline = if (style.isEink) Modifier.border(2.dp, colors.line, shape) else Modifier
    val click = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(outline)
            .clip(shape)
            .background(colors.surface)
            .then(click)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * One pattern for empty and error states: a 64dp soft icon tile, a Fraunces 22sp
 * title, a single 15sp sentence in `ink2`, and at most one filled button when the
 * user can do something about it. On e-ink the tile is white with a 2dp outline.
 */
@Composable
fun EmberEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = Ember.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            val tileShape = RoundedCornerShape(16.dp)
            val tileOutline = if (Ember.style.isEink) {
                Modifier.border(2.dp, colors.line, tileShape)
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(tileShape)
                    .background(if (Ember.style.isEink) colors.surface else colors.navActive)
                    .then(tileOutline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = if (Ember.style.isEink) colors.ink else colors.navActiveContent,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.height(52.dp),
                shape = CircleShape,
                elevation = null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.track,
                    disabledContentColor = colors.ink2,
                ),
            ) {
                Text(
                    text = actionLabel,
                    style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
