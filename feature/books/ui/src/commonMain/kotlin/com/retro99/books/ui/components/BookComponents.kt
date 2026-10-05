package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.server.ServerType
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCover
import com.retro99.base.ui.compose.EmberProgress
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.isOnThisDevice
import com.retro99.books.ui.model.showDownloadedIcon
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_read_along
import resources.translations.books_action_favorite
import resources.translations.books_action_unfavorite
import resources.translations.books_cached_indicator
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_on_this_phone
import resources.translations.books_progress_local
import resources.translations.books_progress_remote
import resources.translations.books_search_clear
import resources.translations.books_search_placeholder
import resources.translations.cloud_backup_finishing
import resources.translations.cloud_backup_progress
import resources.translations.cloud_backup_queued
import resources.translations.library_home_this_device

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
 * @param showFavorite Whether the favorite button is shown (not when picking a book)
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
    showFavorite: Boolean = true,
    activeCloudUploads: List<BookFileTransfer> = emptyList(),
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
                fallbackLabel = book.title,
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (book.authors.isNotEmpty()) {
                    Text(
                        text = highlightedText(book.authors.joinToString(", "), highlightQuery),
                        style = type.meta,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                BookMetaLine(
                    book = book,
                    isOnThisPhone = book.isOnThisDevice(progressInfo),
                    showSource = showServerBadge,
                )

                CloudBackupTransferLine(activeCloudUploads)

                subtitleContent?.invoke()

                BookProgressLines(
                    progressInfo = progressInfo,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (showFavorite) {
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier.size(48.dp),
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
                        tint = if (isFavorite) colors.accent else colors.ink2,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/**
 * The quiet line under the title: "eBook · On this phone · Storyteller".
 * What the book is, whether it is downloaded, and — only when the user has more than one
 * source connected — where it comes from, as plain text.
 */
@Composable
private fun BookMetaLine(
    book: BookUiModel,
    isOnThisPhone: Boolean,
    showSource: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type

    val formats = BookFormat.entries
        .filter { format -> format.isAvailableFor(book) }
        .map { format -> stringResource(format.labelRes) }
    val downloaded = if (isOnThisPhone) {
        listOf(stringResource(StringRes.books_on_this_phone))
    } else {
        emptyList()
    }
    // "This device" as a source is what the downloaded part already says.
    val sources = if (showSource) {
        book.homes
            .filter { home -> home != BookHome.ThisDevice || !isOnThisPhone }
            .map { home -> homeLabel(home) }
    } else {
        emptyList()
    }
    val line = (formats + downloaded + sources).joinToString(" · ")
    if (line.isEmpty()) return

    Text(
        text = line,
        style = type.meta.copy(fontSize = 13.sp),
        color = colors.ink2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
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
    val labelRes: StringResource,
) {
    Ebook(StringRes.books_media_ebook),
    Audio(StringRes.books_media_audio),
    // The same words book details uses for this format.
    Readaloud(StringRes.book_detail_read_along),
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
    activeCloudUploads: List<BookFileTransfer> = emptyList(),
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
            fallbackLabel = book.title,
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
            if (book.showDownloadedIcon(progressInfo)) {
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
                // A linked book shows one badge per home it has a copy in.
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    book.homes.forEach { home -> HomeBadge(home = home) }
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
        CloudBackupTransferLine(activeCloudUploads)
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
private fun CloudBackupTransferLine(transfers: List<BookFileTransfer>) {
    if (transfers.isEmpty()) return
    val colors = Ember.colors
    val type = Ember.type
    val transferred = transfers.sumOf(BookFileTransfer::bytesTransferred)
    val total = transfers.sumOf(BookFileTransfer::totalBytes)
    val text = when {
        transfers.any { transfer -> transfer.state == "transferring" } -> {
            val percent = if (total > 0) (transferred * 100 / total).toInt().coerceIn(0, 100) else 0
            stringResource(StringRes.cloud_backup_progress, percent)
        }
        transfers.any { transfer -> transfer.state == "pending" } ->
            stringResource(StringRes.cloud_backup_queued)
        else -> stringResource(StringRes.cloud_backup_finishing)
    }
    Text(
        text = text,
        style = type.meta.copy(fontSize = 13.sp),
        color = colors.accentText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The plain-text name of where a book lives: "Storyteller", "Parrot Cloud", "This device". */
@Composable
fun homeLabel(home: BookHome): String = when (home) {
    BookHome.ThisDevice -> stringResource(StringRes.library_home_this_device)
    else -> home.serverType.displayName
}

private val BookHome.serverType: ServerType
    get() = when (this) {
        BookHome.ThisDevice -> ServerType.Local
        BookHome.ParrotCloud -> ServerType.ParrotCloud
        BookHome.Storyteller -> ServerType.Storyteller
        BookHome.Audiobookshelf -> ServerType.Audiobookshelf
    }

/** Where a book lives: an Ember pill with a home-tinted dot and a bold label. */
@Composable
fun HomeBadge(
    home: BookHome,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val serverType = home.serverType
    val label = homeLabel(home)
    val dotColor = when (serverType) {
        ServerType.Storyteller -> colors.accentText
        ServerType.Audiobookshelf -> colors.success
        ServerType.ParrotCloud -> colors.accent
        ServerType.Local -> colors.ink2
    }
    val shape = CircleShape
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.chip)
            .border(
                if (Ember.style.isEink) 2.dp else 1.dp,
                colors.chipBorder,
                shape,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
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
            text = label,
            style = Ember.type.meta.copy(
                fontSize = if (Ember.style.isEink) 13.sp else 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.ink,
            maxLines = 1,
        )
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
