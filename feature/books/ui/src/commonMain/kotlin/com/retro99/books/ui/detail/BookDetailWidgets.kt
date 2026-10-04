package com.retro99.books.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember as EmberTheme
import com.retro99.books.domain.model.BookType
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_media_ebook
import resources.translations.book_detail_read_along
import resources.translations.book_detail_audiobook
import resources.translations.book_detail_size_bytes
import resources.translations.book_detail_size_kb
import resources.translations.book_detail_size_mb
import resources.translations.book_detail_size_gb

@Composable
internal fun mediaLabel(type: BookType): String = stringResource(when (type) {
    BookType.EBOOK -> StringRes.books_media_ebook
    BookType.READALOUD -> StringRes.book_detail_read_along
    BookType.AUDIOBOOK -> StringRes.book_detail_audiobook
})

@Composable
internal fun byteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> stringResource(
        StringRes.book_detail_size_gb, gigabyteCount(bytes),
    )
    bytes >= 1024L * 1024L -> stringResource(
        StringRes.book_detail_size_mb, bytes / (1024L * 1024L),
    )
    bytes >= 1024L -> stringResource(StringRes.book_detail_size_kb, bytes / 1024L)
    else -> stringResource(StringRes.book_detail_size_bytes, bytes)
}

@Composable
internal fun DetailButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    secondary: Boolean = false,
    loading: Boolean = false,
    actionDescription: String? = null,
    neutral: Boolean = false,
) {
    val colors = EmberTheme.colors
    val buttonModifier = if (actionDescription == null) modifier else modifier.semantics {
        contentDescription = actionDescription
    }
    val content: @Composable () -> Unit = {
        if (loading && !EmberTheme.style.isEink) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                color = colors.ink2)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = EmberTheme.type.label)
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.track,
                disabledContentColor = colors.ink2,
            ),
            content = { content() },
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            border = BorderStroke(
                if (neutral) 1.dp else EmberTheme.style.detailBorder,
                when {
                    destructive -> colors.destructive
                    neutral -> colors.chipBorder
                    else -> colors.line
                },
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = when {
                    destructive -> colors.destructive
                    neutral -> colors.ink
                    else -> colors.accentText
                },
                containerColor = if (secondary && !EmberTheme.style.isEink) colors.navActive
                    else androidx.compose.ui.graphics.Color.Transparent,
            ),
            content = { content() },
        )
    }
}

/** Static even for indeterminate transfers: no infinite repaint on e-ink. */
@Composable
internal fun DetailProgressBar(progress: Float?, modifier: Modifier = Modifier, marker: Float? = null) {
    val colors = EmberTheme.colors
    if (marker != null) {
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
            DetailProgressBar(progress)
            Canvas(Modifier.matchParentSize()) {
                val width = 2.dp.toPx()
                val fraction = marker.coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
                val x = (size.width * fraction - width / 2).coerceIn(0f, size.width - width)
                drawRect(colors.ink, topLeft = Offset(x, 0f), size = Size(width, size.height))
            }
        }
        return
    }
    val shape = RoundedCornerShape(50)
    val fraction = progress?.coerceIn(0f, 1f) ?: 0f
    Box(
        modifier.fillMaxWidth().height(EmberTheme.style.detailProgressHeight)
            .clip(shape).background(colors.track)
            .then(if (EmberTheme.style.isEink) Modifier.border(1.dp, colors.line, shape)
                else Modifier)
            .semantics {
                progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
                    else ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(EmberTheme.style.detailProgressHeight)
            .background(colors.accent))
    }
}
