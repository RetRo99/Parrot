package com.retro99.books.ui.links

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.link_suggestions_banner

/** The single text row above the library list while suggestions are waiting for review. */
@Composable
fun LinkSuggestionsBanner(
    suggestionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(StringRes.link_suggestions_banner, suggestionCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
