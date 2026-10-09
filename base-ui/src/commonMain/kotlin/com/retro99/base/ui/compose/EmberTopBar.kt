package com.retro99.base.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_back

/**
 * Ember screen top bar: 48dp back button, Fraunces title with an optional secondary
 * line, trailing actions. The bar owns the status-bar inset — hosts must not add their
 * own ([applyStatusBarInset] is off for overlay bars whose host already applies it).
 */
@Composable
fun EmberTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    titleStyle: TextStyle = Ember.type.screenTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
    containerColor: Color = Ember.colors.bg,
    applyStatusBarInset: Boolean = true,
    horizontalPadding: Dp = 20.dp,
    titleStartPadding: Dp = 8.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier)
                // Grows with large text instead of clipping the title and subtitle.
                .heightIn(min = 64.dp)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(StringRes.general_back),
                        tint = Ember.colors.ink,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) titleStartPadding else 0.dp),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = Ember.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = Ember.colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}
