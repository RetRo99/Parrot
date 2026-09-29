package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.model.BookListViewMode
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_action_view
import resources.translations.books_filters_chip
import resources.translations.books_filters_chip_description_active
import resources.translations.books_filters_chip_description_none
import resources.translations.books_shelf_count
import resources.translations.books_shelf_count_one
import resources.translations.books_shelf_title
import resources.translations.books_sort_chip_description
import resources.translations.books_view_grid
import resources.translations.books_view_list

/**
 * "On your shelf" heading. Row one has the title, the book count and the list/cover toggle;
 * row two has the sort chip (with its popover) and the Filters chip.
 */
@Composable
fun ShelfHeader(
    bookCount: Int,
    activeFilterCount: Int,
    onFiltersClicked: () -> Unit,
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    viewMode: BookListViewMode,
    onViewModeChanged: (BookListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(StringRes.books_shelf_title),
                style = type.section,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = if (bookCount == 1) {
                    stringResource(StringRes.books_shelf_count_one)
                } else {
                    stringResource(StringRes.books_shelf_count, bookCount)
                },
                style = type.meta,
                color = colors.ink2,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ViewModeSegments(
                viewMode = viewMode,
                onViewModeChanged = onViewModeChanged,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortChip(
                sortConfig = sortConfig,
                onSortChanged = onSortChanged,
                modifier = Modifier.weight(1f, fill = false),
            )
            FiltersChip(
                activeFilterCount = activeFilterCount,
                onClick = onFiltersClicked,
            )
        }
    }
}

@Composable
private fun SortChip(
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    var expanded by remember { mutableStateOf(false) }
    val fieldLabel = stringResource(sortConfig.option.labelRes)
    val directionLabel = stringResource(sortConfig.option.directionLabel(sortConfig.direction))
    val description = stringResource(
        StringRes.books_sort_chip_description,
        fieldLabel,
        directionLabel,
    )

    Box(modifier = modifier.minimumInteractiveComponentSize()) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(colors.chip)
                .border(style.border, colors.chipBorder, CircleShape)
                .clickable { expanded = true }
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.ink,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$fieldLabel · $directionLabel",
                style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.ink,
            )
        }

        if (expanded) {
            BookSortMenu(
                sortConfig = sortConfig,
                onSortChanged = onSortChanged,
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
private fun FiltersChip(
    activeFilterCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val description = if (activeFilterCount > 0) {
        stringResource(StringRes.books_filters_chip_description_active, activeFilterCount)
    } else {
        stringResource(StringRes.books_filters_chip_description_none)
    }

    Box(modifier = modifier.minimumInteractiveComponentSize()) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(colors.chip)
                .border(style.border, colors.chipBorder, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterList,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = colors.ink,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(StringRes.books_filters_chip),
                style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
            )
            if (activeFilterCount > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .heightIn(min = 20.dp)
                        .widthIn(min = 20.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = activeFilterCount.toString(),
                        style = Ember.type.label.copy(fontSize = 12.sp),
                        color = colors.onAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeSegments(
    viewMode: BookListViewMode,
    onViewModeChanged: (BookListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val viewModeGroupDescription = stringResource(StringRes.books_action_view)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.chip)
            .border(style.border, colors.chipBorder, CircleShape)
            .padding(2.dp)
            .semantics { contentDescription = viewModeGroupDescription },
    ) {
        SegmentButton(
            icon = Icons.AutoMirrored.Outlined.ViewList,
            label = stringResource(StringRes.books_view_list),
            selected = viewMode == BookListViewMode.LIST,
            onClick = { onViewModeChanged(BookListViewMode.LIST) },
        )
        SegmentButton(
            icon = Icons.Outlined.GridView,
            label = stringResource(StringRes.books_view_grid),
            selected = viewMode == BookListViewMode.GRID,
            onClick = { onViewModeChanged(BookListViewMode.GRID) },
        )
    }
}

@Composable
private fun SegmentButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors

    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 30.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = if (selected) colors.onAccent else colors.ink2,
        )
    }
}
