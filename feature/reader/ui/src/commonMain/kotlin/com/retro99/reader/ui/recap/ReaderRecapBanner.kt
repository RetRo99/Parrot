package com.retro99.reader.ui.recap

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.reader_recap_dismiss
import resources.translations.reader_recap_earlier_session
import resources.translations.reader_recap_hide
import resources.translations.reader_recap_last_session
import resources.translations.reader_recap_show

/** A small chip that expands into the stored recap of an earlier session. */
@Composable
internal fun ReaderRecapBannerHost(
    bookUuid: String,
    modifier: Modifier = Modifier,
    viewModel: ReaderRecapViewModel = koinViewModel(key = "reader_recap_$bookUuid") {
        parametersOf(bookUuid)
    },
) {
    val state by viewModel.viewState.collectAsState()
    val banner = state.banner ?: return
    ReaderRecapBanner(
        banner = banner,
        isExpanded = state.isExpanded,
        onToggle = { viewModel.onIntent(ReaderRecapIntent.ToggleExpanded) },
        onDismiss = { viewModel.onIntent(ReaderRecapIntent.Dismiss) },
        modifier = modifier,
    )
}

@Composable
internal fun ReaderRecapBanner(
    banner: ReaderRecapBanner,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val title = stringResource(
        if (banner.isLatestSession) StringRes.reader_recap_last_session
        else StringRes.reader_recap_earlier_session,
    )
    Surface(
        shape = RoundedCornerShape(if (isExpanded) 16.dp else 24.dp),
        color = colors.surface,
        border = BorderStroke(if (eink) 2.dp else 1.dp, colors.chipBorder),
        modifier = modifier
            .widthIn(max = 560.dp)
            .then(if (eink) Modifier else Modifier.animateContentSize()),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(start = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.accentText,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    color = colors.ink,
                    style = Ember.type.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (isExpanded) StringRes.reader_recap_hide else StringRes.reader_recap_show,
                    ),
                    tint = colors.ink2,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(StringRes.reader_recap_dismiss),
                        tint = colors.ink2,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (isExpanded) {
                Text(
                    text = banner.summary,
                    color = colors.ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}
