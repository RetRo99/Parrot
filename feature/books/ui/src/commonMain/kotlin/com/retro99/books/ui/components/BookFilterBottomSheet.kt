package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.server.ServerType
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.EmberChip
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.books.ui.model.BookFilterState
import com.retro99.books.ui.model.BookQuickFilter
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_filter_all
import resources.translations.books_filter_audiobook
import resources.translations.books_filter_cached
import resources.translations.books_filter_ebook
import resources.translations.books_filter_favorites
import resources.translations.books_filter_format
import resources.translations.books_filter_in_progress
import resources.translations.books_filter_in_series
import resources.translations.books_filter_on_this_device
import resources.translations.books_filter_readaloud
import resources.translations.books_filter_reset
import resources.translations.books_filter_sheet_title
import resources.translations.books_filter_show_all
import resources.translations.books_filter_show_count
import resources.translations.books_filter_show_count_one
import resources.translations.books_filter_show_only
import resources.translations.books_filter_source

private val SHOW_ONLY_FILTERS = listOf(
    BookQuickFilter.FAVORITES,
    BookQuickFilter.IN_PROGRESS,
    BookQuickFilter.CACHED,
    BookQuickFilter.IN_SERIES,
)

private val FORMAT_FILTERS = listOf(
    BookQuickFilter.HAS_EBOOK,
    BookQuickFilter.HAS_AUDIOBOOK,
    BookQuickFilter.HAS_READALOUD,
)

/**
 * Filters sheet. In E-ink mode it is a plain overlay with no scrim and no enter or exit
 * animation; otherwise it is a modal bottom sheet.
 *
 * @param availableServerTypes Library sources to offer next to "All".
 */
@Composable
fun BookFilterBottomSheet(
    filterState: BookFilterState,
    availableServerTypes: List<ServerType>,
    onFilterToggle: (BookQuickFilter) -> Unit,
    onServerTypeFilterChanged: (ServerType?) -> Unit,
    onClearAllFilters: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmberBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        FilterSheetContent(
            filterState = filterState,
            availableServerTypes = availableServerTypes,
            onFilterToggle = onFilterToggle,
            onServerTypeFilterChanged = onServerTypeFilterChanged,
            onClearAllFilters = onClearAllFilters,
            onDone = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    filterState: BookFilterState,
    availableServerTypes: List<ServerType>,
    onFilterToggle: (BookQuickFilter) -> Unit,
    onServerTypeFilterChanged: (ServerType?) -> Unit,
    onClearAllFilters: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = Ember.colors
    val type = Ember.type
    val filterCount = filterState.activeFilterCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(StringRes.books_filter_sheet_title),
                style = type.screenTitle.copy(fontSize = 26.sp),
                color = colors.ink,
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClearAllFilters)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(StringRes.books_filter_reset),
                    style = type.label.copy(fontSize = 15.sp),
                    color = colors.accentText,
                )
            }
        }

        FilterSection(title = stringResource(StringRes.books_filter_source)) {
            EmberChip(
                label = stringResource(StringRes.books_filter_all),
                selected = filterState.serverTypeFilter == null,
                onClick = { onServerTypeFilterChanged(null) },
                role = Role.RadioButton,
            )
            availableServerTypes.forEach { serverType ->
                EmberChip(
                    label = serverType.sourceLabel(),
                    selected = filterState.serverTypeFilter == serverType,
                    onClick = { onServerTypeFilterChanged(serverType) },
                    role = Role.RadioButton,
                )
            }
        }

        FilterSection(title = stringResource(StringRes.books_filter_show_only)) {
            SHOW_ONLY_FILTERS.forEach { filter ->
                EmberChip(
                    label = stringResource(filter.labelRes),
                    selected = filter in filterState.activeQuickFilters,
                    onClick = { onFilterToggle(filter) },
                )
            }
        }

        FilterSection(title = stringResource(StringRes.books_filter_format)) {
            FORMAT_FILTERS.forEach { filter ->
                EmberChip(
                    label = stringResource(filter.labelRes),
                    selected = filter in filterState.activeQuickFilters,
                    onClick = { onFilterToggle(filter) },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (filterCount) {
                    0 -> stringResource(StringRes.books_filter_show_all)
                    1 -> stringResource(StringRes.books_filter_show_count_one)
                    else -> stringResource(StringRes.books_filter_show_count, filterCount)
                },
                style = type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = colors.onAccent,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    EmberSectionLabel(
        text = title,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun ServerType.sourceLabel(): String = when (this) {
    ServerType.Local -> stringResource(StringRes.books_filter_on_this_device)
    else -> displayName
}

private val BookQuickFilter.labelRes: StringResource
    get() = when (this) {
        BookQuickFilter.FAVORITES -> StringRes.books_filter_favorites
        BookQuickFilter.IN_PROGRESS -> StringRes.books_filter_in_progress
        BookQuickFilter.CACHED -> StringRes.books_filter_cached
        BookQuickFilter.HAS_EBOOK -> StringRes.books_filter_ebook
        BookQuickFilter.HAS_AUDIOBOOK -> StringRes.books_filter_audiobook
        BookQuickFilter.HAS_READALOUD -> StringRes.books_filter_readaloud
        BookQuickFilter.IN_SERIES -> StringRes.books_filter_in_series
    }
