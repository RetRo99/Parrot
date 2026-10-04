package com.retro99.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.EmberEmptyState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_retry
import resources.translations.reader_error_title

/**
 * Shared error display composable for the EPUB reader.
 * Used by both Android and iOS implementations to display error messages.
 */
@Composable
internal fun ReaderErrorView(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        EmberEmptyState(
            title = stringResource(StringRes.reader_error_title),
            message = message,
            icon = Icons.Outlined.Warning,
            actionLabel = if (onRetry != null) stringResource(StringRes.general_retry) else null,
            onAction = onRetry,
        )
    }
}
