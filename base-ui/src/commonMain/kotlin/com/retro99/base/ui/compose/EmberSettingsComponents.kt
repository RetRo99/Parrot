package com.retro99.base.ui.compose

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SWITCH_WIDTH = 52.dp
private val SWITCH_HEIGHT = 32.dp
private const val DISABLED_ALPHA = 0.45f

/** Section label that sits above a settings card. */
@Composable
fun EmberSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 22.dp,
) {
    Text(
        text = text.uppercase(),
        style = Ember.type.eyebrow.copy(
            fontSize = if (Ember.style.isEink) 13.sp else 11.sp,
            letterSpacing = 1.5.sp,
        ),
        color = Ember.colors.accentText,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 8.dp),
    )
}

/** Card that groups settings rows. Add rows with [EmberSettingRow]. */
@Composable
fun EmberGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    EmberCard(
        modifier = modifier.padding(horizontal = 20.dp),
        contentPadding = 0.dp,
        content = content,
    )
}

/** Divider between rows inside an [EmberGroupCard], inset 16dp. */
@Composable
fun EmberRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = Ember.style.border,
        color = Ember.colors.line,
    )
}

/**
 * Settings row: icon tile, title and subtitle, and a trailing slot. Pass [onClick] for
 * navigation rows. For switches use [EmberSwitchRow].
 */
@Composable
fun EmberSettingRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val click = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    SettingRowLayout(
        title = title,
        subtitle = subtitle,
        icon = icon,
        isDestructive = isDestructive,
        indent = false,
        enabled = enabled,
        modifier = modifier.then(click),
        trailing = trailing,
    )
}

/** Row with an [EmberSwitch]; the whole row toggles. */
@Composable
fun EmberSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    indent: Boolean = false,
    enabled: Boolean = true,
) {
    SettingRowLayout(
        title = title,
        subtitle = subtitle,
        icon = icon,
        isDestructive = false,
        indent = indent,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        trailing = { EmberSwitch(checked = checked) },
    )
}

@Composable
private fun SettingRowLayout(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    isDestructive: Boolean,
    indent: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    trailing: @Composable () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style
    val titleColor = if (isDestructive) colors.destructive else colors.ink
    val iconColor = when {
        isDestructive -> colors.destructive
        style.isEink -> colors.ink
        else -> colors.accentText
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (style.isEink) Color.Transparent else colors.navActive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconColor,
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Ember.type.meta,
                    color = colors.ink2,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        trailing()
    }
}

/** Trailing chevron for navigation rows. */
@Composable
fun EmberChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = Ember.colors.ink2,
    )
}

/**
 * Switch drawn as a 52×32dp pill. It has no click handling of its own: put it in a row
 * that toggles (see [EmberSwitchRow]). The knob does not animate in E-ink mode.
 */
@Composable
fun EmberSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val knobSize = if (checked || style.isEink) 26.dp else 18.dp
    val padding = if (checked) 3.dp else 7.dp
    val target = if (checked) SWITCH_WIDTH - knobSize - 3.dp else padding
    val knobX: State<Dp> = if (style.animations) {
        animateDpAsState(targetValue = target, label = "emberSwitchKnob")
    } else {
        rememberUpdatedState(target)
    }
    val trackColor = when {
        checked -> colors.accent
        style.isEink -> colors.surface
        else -> colors.bg
    }
    val outline = if (checked) {
        Modifier
    } else {
        Modifier.border(if (style.isEink) 2.dp else 1.5.dp, colors.chipBorder, CircleShape)
    }
    val knobColor = when {
        checked -> colors.onAccent
        style.isEink -> colors.ink
        else -> colors.ink2
    }

    Box(
        modifier = modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .clip(CircleShape)
            .background(trackColor)
            .then(outline),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX.value)
                .size(knobSize)
                .background(knobColor, CircleShape),
        )
    }
}
