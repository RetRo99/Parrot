package com.retro99.statistics.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.EmberEmptyState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_retry
import resources.translations.statistics_load_failed
import resources.translations.statistics_load_failed_title

@Composable
internal fun StatisticsDetailLoadErrorContent(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmberEmptyState(
        title = stringResource(StringRes.statistics_load_failed_title),
        message = stringResource(StringRes.statistics_load_failed),
        icon = Icons.Outlined.Warning,
        actionLabel = stringResource(StringRes.general_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}
