package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.books.ui.model.BookSortConfig
import com.retro99.books.ui.model.BookSortOption
import com.retro99.books.ui.model.SortDirection
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_sort_a_to_z
import resources.translations.books_sort_author
import resources.translations.books_sort_by
import resources.translations.books_sort_date_added
import resources.translations.books_sort_date_published
import resources.translations.books_sort_highest
import resources.translations.books_sort_lowest
import resources.translations.books_sort_newest
import resources.translations.books_sort_oldest
import resources.translations.books_sort_order
import resources.translations.books_sort_rating
import resources.translations.books_sort_title
import resources.translations.books_sort_z_to_a

private val MENU_WIDTH = 262.dp
private val MENU_SCREEN_MARGIN = 12.dp
private val MENU_ANCHOR_GAP = 6.dp

/**
 * Sort popover. Place it inside the box that holds the sort chip: it opens below the chip,
 * left-aligned to it, and is shifted left when it would run off the screen. It has no enter
 * or exit animation, and stays open until dismissed.
 */
@Composable
fun BookSortMenu(
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(18.dp)
    val option = sortConfig.option
    val density = LocalDensity.current
    val provider = remember(density) {
        with(density) {
            BelowAnchorPositionProvider(
                gapPx = MENU_ANCHOR_GAP.roundToPx(),
                marginPx = MENU_SCREEN_MARGIN.roundToPx(),
            )
        }
    }
    val shadow = if (style.isEink) Modifier else Modifier.shadow(12.dp, shape)
    val outlineWidth = if (style.isEink) 2.dp else 1.dp

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(MENU_WIDTH)
                .then(shadow)
                .clip(shape)
                .background(colors.surface)
                .border(outlineWidth, colors.chipBorder, shape)
                .padding(vertical = 8.dp),
        ) {
            EmberSectionLabel(
                text = stringResource(StringRes.books_sort_by),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
            )
            BookSortOption.entries.forEach { sortOption ->
                SortOptionRow(
                    label = stringResource(sortOption.labelRes),
                    selected = sortOption == option,
                    onClick = {
                        if (sortOption != option) onSortChanged(sortConfig.copy(option = sortOption))
                    },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                thickness = style.border,
                color = colors.line,
            )

            EmberSectionLabel(
                text = stringResource(StringRes.books_sort_order),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            )
            OrderToggle(
                sortConfig = sortConfig,
                onSortChanged = onSortChanged,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SortOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val style = Ember.style
    val mark = if (style.isEink) colors.chipSelectedText else colors.accent
    val ring = if (selected) mark else colors.ink2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (selected) colors.navActive else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(mark, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = Ember.type.meta.copy(
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) colors.chipSelectedText else colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun OrderToggle(
    sortConfig: BookSortConfig,
    onSortChanged: (BookSortConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.bg)
            .border(style.border, colors.chipBorder, CircleShape)
            .padding(3.dp),
    ) {
        SortDirection.entries.forEach { direction ->
            val selected = direction == sortConfig.direction
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) onSortChanged(sortConfig.copy(direction = direction))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(sortConfig.option.directionLabel(direction)),
                    style = Ember.type.meta.copy(
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = if (selected) colors.onAccent else colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

private class BelowAnchorPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        return IntOffset(
            x = anchorBounds.left.coerceIn(marginPx, maxX),
            y = anchorBounds.bottom + gapPx,
        )
    }
}

internal val BookSortOption.labelRes
    get() = when (this) {
        BookSortOption.TITLE -> StringRes.books_sort_title
        BookSortOption.AUTHOR -> StringRes.books_sort_author
        BookSortOption.RATING -> StringRes.books_sort_rating
        BookSortOption.DATE_PUBLISHED -> StringRes.books_sort_date_published
        BookSortOption.DATE_ADDED -> StringRes.books_sort_date_added
    }

/** Direction wording depends on the field: A → Z, Lowest/Highest, Oldest/Newest. */
internal fun BookSortOption.directionLabel(direction: SortDirection) = when (this) {
    BookSortOption.TITLE, BookSortOption.AUTHOR -> if (direction == SortDirection.ASCENDING) {
        StringRes.books_sort_a_to_z
    } else {
        StringRes.books_sort_z_to_a
    }

    BookSortOption.RATING -> if (direction == SortDirection.ASCENDING) {
        StringRes.books_sort_lowest
    } else {
        StringRes.books_sort_highest
    }

    BookSortOption.DATE_PUBLISHED, BookSortOption.DATE_ADDED -> if (direction == SortDirection.ASCENDING) {
        StringRes.books_sort_oldest
    } else {
        StringRes.books_sort_newest
    }
}
