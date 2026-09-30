package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.server.ServerType
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCover
import com.retro99.base.ui.compose.EmberProgress
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_action_favorite
import resources.translations.books_action_unfavorite
import resources.translations.books_cached_indicator
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.books_progress_local
import resources.translations.books_progress_remote
import resources.translations.books_search_clear
import resources.translations.books_search_placeholder

/**
 * A book row in the Ember list style: cover, title, author and formats, progress and favorite.
 *
 * @param book The book data to display
 * @param isFavorite Whether the book is marked as favorite
 * @param onClick Callback when the row is clicked
 * @param onFavoriteClick Callback when the favorite button is clicked
 * @param modifier Modifier for the row
 * @param progressInfo Optional progress and cache info for the book
 * @param showServerBadge Whether to show the server the book comes from
 * @param showDivider Whether to draw a divider above the row (all but the first row of a list)
 * @param headerContent Optional composable content to display above the title
 * @param subtitleContent Optional composable content to display below the author (e.g., series info)
 */
@Composable
fun BookItemCard(
    book: BookUiModel,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressInfo: BookProgressInfoUiModel? = null,
    showServerBadge: Boolean = true,
    showDivider: Boolean = false,
    highlightQuery: String = "",
    headerContent: @Composable (() -> Unit)? = null,
    subtitleContent: @Composable (() -> Unit)? = null,
) {
    val colors = Ember.colors
    val style = Ember.style
    val type = Ember.type

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 14.dp),
    ) {
        if (showDivider) {
            HorizontalDivider(thickness = style.border, color = colors.line)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EmberCover(
                data = book.coverUrl,
                cacheKey = book.uuid,
                contentDescription = book.title,
                modifier = Modifier.size(width = 44.dp, height = 66.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                headerContent?.invoke()

                Text(
                    text = highlightedText(book.title, highlightQuery),
                    style = type.bookTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                BookMetaRow(
                    book = book,
                    isCached = progressInfo?.hasAnyCached == true,
                    showServerBadge = showServerBadge,
                    highlightQuery = highlightQuery,
                )

                subtitleContent?.invoke()

                BookProgressLines(
                    progressInfo = progressInfo,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (isFavorite) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                    contentDescription = stringResource(
                        if (isFavorite) {
                            StringRes.books_action_unfavorite
                        } else {
                            StringRes.books_action_favorite
                        },
                    ),
                    tint = if (isFavorite) colors.accentText else colors.ink2,
                )
            }
        }
    }
}

/** "Author · icon Format" line under a book title. */
@Composable
private fun BookMetaRow(
    book: BookUiModel,
    isCached: Boolean,
    showServerBadge: Boolean,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
) {
    val colors = Ember.colors
    val type = Ember.type

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (book.authors.isNotEmpty()) {
            Text(
                text = highlightedText(book.authors.joinToString(", "), highlightQuery),
                style = type.meta,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(text = "·", style = type.meta, color = colors.ink2)
        }
        BookFormat.entries.filter { format -> format.isAvailableFor(book) }.forEach { format ->
            Icon(
                imageVector = format.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.ink2,
            )
            Text(
                text = stringResource(format.labelRes),
                style = type.meta,
                color = colors.ink2,
                maxLines = 1,
            )
        }
        if (isCached) {
            Icon(
                imageVector = Icons.Outlined.DownloadDone,
                contentDescription = stringResource(StringRes.books_cached_indicator),
                modifier = Modifier.size(14.dp),
                tint = colors.ink2,
            )
        }
        if (showServerBadge) {
            book.serverType?.let { serverType -> ServerTypeBadge(serverType = serverType) }
        }
    }
}

/**
 * Progress bar and percentage. When the local and remote positions disagree both are
 * shown, labelled, so the conflict is visible before the book is opened.
 */
@Composable
private fun BookProgressLines(
    progressInfo: BookProgressInfoUiModel?,
    modifier: Modifier = Modifier,
) {
    val style = Ember.style
    if (progressInfo == null) return

    if (progressInfo.hasConflict) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            progressInfo.localProgression?.let { local ->
                ProgressLine(
                    progress = local.toFloat(),
                    percent = progressInfo.localProgressPercent ?: 0,
                    label = stringResource(StringRes.books_progress_local),
                    height = style.progressHeightSmall,
                )
            }
            progressInfo.remoteProgression?.let { remote ->
                ProgressLine(
                    progress = remote.toFloat(),
                    percent = progressInfo.remoteProgressPercent ?: 0,
                    label = stringResource(StringRes.books_progress_remote),
                    height = style.progressHeightSmall,
                )
            }
        }
    } else {
        val progress = progressInfo.displayProgression ?: 0.0
        if (progress > 0.0) {
            ProgressLine(
                progress = progress.toFloat(),
                percent = progressInfo.progressPercent,
                label = null,
                height = style.progressHeightSmall,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ProgressLine(
    progress: Float,
    percent: Int,
    label: String?,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(
                text = label,
                style = type.meta.copy(fontSize = 11.sp),
                color = colors.ink2,
                modifier = Modifier.width(48.dp),
            )
        }
        EmberProgress(progress = progress, height = height, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "$percent%",
            style = type.label.copy(fontSize = type.meta.fontSize),
            color = colors.accentText,
            modifier = Modifier.widthIn(min = 30.dp),
        )
    }
}

private enum class BookFormat(
    val icon: ImageVector,
    val labelRes: StringResource,
) {
    Ebook(Icons.AutoMirrored.Outlined.MenuBook, StringRes.books_media_ebook),
    Audio(Icons.Outlined.Headphones, StringRes.books_media_audio),
    Readaloud(Icons.Outlined.RecordVoiceOver, StringRes.books_media_readaloud),
    ;

    fun isAvailableFor(book: BookUiModel): Boolean = when (this) {
        Ebook -> book.hasEbook
        Audio -> book.hasAudiobook
        Readaloud -> book.hasReadaloud
    }
}

/** A book in the cover grid: cover with favorite overlay, title, author and progress. */
@Composable
fun BookGridCard(
    book: BookUiModel,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressInfo: BookProgressInfoUiModel? = null,
    showServerBadge: Boolean = true,
) {
    val colors = Ember.colors
    val style = Ember.style
    val type = Ember.type

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        EmberCover(
            data = book.coverUrl,
            cacheKey = book.uuid,
            contentDescription = book.title,
            elevation = style.coverElevation,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (isFavorite) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Outlined.FavoriteBorder
                    },
                    contentDescription = stringResource(
                        if (isFavorite) {
                            StringRes.books_action_unfavorite
                        } else {
                            StringRes.books_action_favorite
                        },
                    ),
                    tint = if (isFavorite) colors.accentText else colors.ink,
                    modifier = Modifier
                        .background(colors.bg.copy(alpha = 0.8f), CircleShape)
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
            if (progressInfo?.hasAnyCached == true) {
                Icon(
                    imageVector = Icons.Outlined.DownloadDone,
                    contentDescription = stringResource(StringRes.books_cached_indicator),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(colors.bg.copy(alpha = 0.85f), RoundedCornerShape(topStart = 8.dp))
                        .padding(6.dp)
                        .size(16.dp),
                    tint = colors.ink,
                )
            }
            if (showServerBadge) {
                book.serverType?.let { serverType ->
                    ServerTypeBadge(
                        serverType = serverType,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = book.title,
            style = type.bookTitle.copy(fontSize = 15.sp, lineHeight = 19.sp),
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.authors.isNotEmpty()) {
            Text(
                text = book.authors.joinToString(", "),
                style = type.meta,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        progressInfo?.displayProgression?.let { progress ->
            if (progress > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                EmberProgress(
                    progress = progress.toFloat(),
                    height = style.progressHeightSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun ServerTypeBadge(
    serverType: ServerType,
    modifier: Modifier = Modifier,
) {
    val containerColor = when (serverType) {
        ServerType.Storyteller -> MaterialTheme.colorScheme.primaryContainer
        ServerType.Audiobookshelf -> MaterialTheme.colorScheme.tertiaryContainer
        ServerType.ParrotCloud -> MaterialTheme.colorScheme.surfaceVariant
        ServerType.Local -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (serverType) {
        ServerType.Storyteller -> MaterialTheme.colorScheme.onPrimaryContainer
        ServerType.Audiobookshelf -> MaterialTheme.colorScheme.onTertiaryContainer
        ServerType.ParrotCloud -> MaterialTheme.colorScheme.onSurfaceVariant
        ServerType.Local -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val dotColor = when (serverType) {
        ServerType.Storyteller -> MaterialTheme.colorScheme.primary
        ServerType.Audiobookshelf -> MaterialTheme.colorScheme.tertiary
        ServerType.ParrotCloud -> MaterialTheme.colorScheme.primary
        ServerType.Local -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = serverType.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 6.sp,
                    maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                ),
            )
        }
    }
}

/**
 * A reusable search bar component for filtering books.
 *
 * @param searchFieldState The text field state for the search input
 * @param isVisible Whether the search bar is currently visible (used for focus management)
 * @param placeholderRes Placeholder string shown while the field is empty
 * @param modifier Modifier for the search bar
 */
@Composable
fun BookSearchBar(
    searchFieldState: TextFieldState,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    placeholderRes: StringResource = StringRes.books_search_placeholder,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            focusRequester.requestFocus()
        }
    }

    OutlinedTextField(
        state = searchFieldState,
        modifier = modifier.focusRequester(focusRequester),
        placeholder = { Text(stringResource(placeholderRes)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (searchFieldState.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        searchFieldState.edit { delete(0, length) }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(StringRes.books_search_clear),
                    )
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = RoundedCornerShape(12.dp),
    )
}
