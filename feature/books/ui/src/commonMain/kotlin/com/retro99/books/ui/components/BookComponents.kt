package com.retro99.books.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.server.ServerType
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_cached_indicator
import resources.translations.books_media_audio
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.books_progress_local
import resources.translations.books_progress_remote
import resources.translations.books_search_clear
import resources.translations.books_search_placeholder

/**
 * A reusable book item card component that displays book information in a card layout.
 *
 * @param book The book data to display
 * @param isFavorite Whether the book is marked as favorite
 * @param onClick Callback when the card is clicked
 * @param onFavoriteClick Callback when the favorite button is clicked
 * @param modifier Modifier for the card
 * @param progressInfo Optional progress and cache info for the book
 * @param headerContent Optional composable content to display above the title (e.g., series position)
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
    headerContent: @Composable (() -> Unit)? = null,
    subtitleContent: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Book cover with optional cache indicator
            Box(
                modifier = Modifier.size(width = 80.dp, height = 120.dp),
            ) {
                CoilImage(
                    data = book.coverUrl,
                    cacheKey = book.uuid,
                    modifier = Modifier
                        .matchParentSize()
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    contentDescription = book.title,
                )
                // Cache indicator badge
                if (progressInfo?.hasAnyCached == true) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DownloadDone,
                            contentDescription = stringResource(StringRes.books_cached_indicator),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(StringRes.books_cached_indicator),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                        )
                    }
                }
                if (showServerBadge) {
                    book.serverType?.let { serverType ->
                        ServerTypeBadge(
                            serverType = serverType,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                headerContent?.invoke()

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (book.authors.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = book.authors.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                subtitleContent?.invoke()

                // Progress bar(s)
                if (progressInfo != null && progressInfo.hasConflict) {
                    // Show both local and remote progress bars when there's a conflict
                    Spacer(modifier = Modifier.height(8.dp))
                    val animatedLocalProgress by animateFloatAsState(
                        targetValue = (progressInfo.localProgression ?: 0.0).toFloat(),
                        animationSpec = tween(durationMillis = 600),
                        label = "localProgress",
                    )
                    val animatedRemoteProgress by animateFloatAsState(
                        targetValue = (progressInfo.remoteProgression ?: 0.0).toFloat(),
                        animationSpec = tween(durationMillis = 600),
                        label = "remoteProgress",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Local progress
                        if (progressInfo.localProgression != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(StringRes.books_progress_local),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(48.dp),
                                )
                                LinearProgressIndicator(
                                    progress = { animatedLocalProgress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Text(
                                    text = "${progressInfo.localProgressPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // Remote progress
                        if (progressInfo.remoteProgression != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(StringRes.books_progress_remote),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.width(48.dp),
                                )
                                LinearProgressIndicator(
                                    progress = { animatedRemoteProgress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Text(
                                    text = "${progressInfo.remoteProgressPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                } else {
                    // Single progress bar when no conflict
                    progressInfo?.displayProgression?.let { progress ->
                        if (progress > 0.0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val animatedProgress by animateFloatAsState(
                                targetValue = progress.toFloat(),
                                animationSpec = tween(durationMillis = 600),
                                label = "listProgress",
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                                )
                                Text(
                                    text = "${progressInfo.progressPercent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                MediaTypeRow(book = book)
            }

            IconButton(onClick = onFavoriteClick) {
                AnimatedContent(
                    targetState = isFavorite,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.4f, animationSpec = tween(150)) + fadeIn(tween(150))) togetherWith
                            (scaleOut(targetScale = 0.4f, animationSpec = tween(150)) + fadeOut(tween(150)))
                    },
                    label = "favoriteIcon",
                ) { favorite ->
                    Icon(
                        imageVector = if (favorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = null,
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            CoilImage(
                data = book.coverUrl,
                cacheKey = book.uuid,
                modifier = Modifier
                    .matchParentSize()
                    .shadow(6.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = book.title,
            )
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                AnimatedContent(
                    targetState = isFavorite,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.4f, animationSpec = tween(150)) + fadeIn(tween(150))) togetherWith
                            (scaleOut(targetScale = 0.4f, animationSpec = tween(150)) + fadeOut(tween(150)))
                    },
                    label = "favoriteIconGrid",
                ) { favorite ->
                    Icon(
                        imageVector = if (favorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = null,
                        tint = if (favorite) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                }
            }
            if (progressInfo?.hasAnyCached == true) {
                Icon(
                    imageVector = Icons.Outlined.DownloadDone,
                    contentDescription = stringResource(StringRes.books_cached_indicator),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                            shape = RoundedCornerShape(topStart = 8.dp),
                        )
                        .padding(6.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (showServerBadge) {
                book.serverType?.let { serverType ->
                    ServerTypeBadge(
                        serverType = serverType,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.authors.isNotEmpty()) {
            Text(
                text = book.authors.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        progressInfo?.displayProgression?.let { progress ->
            if (progress > 0.0) {
                Spacer(modifier = Modifier.height(6.dp))
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.toFloat(),
                    animationSpec = tween(durationMillis = 600),
                    label = "gridProgress",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MediaTypeRow(
    book: BookUiModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        book.statusName?.let { status ->
            StatusChip(status = status)
        }

        if (book.hasEbook) {
            MediaTypeIndicator(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                label = stringResource(StringRes.books_media_ebook),
            )
        }

        if (book.hasAudiobook) {
            MediaTypeIndicator(
                icon = Icons.Outlined.Headphones,
                label = stringResource(StringRes.books_media_audio),
            )
        }

        if (book.hasReadaloud) {
            MediaTypeIndicator(
                icon = Icons.Outlined.RecordVoiceOver,
                label = stringResource(StringRes.books_media_readaloud),
            )
        }
    }
}

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun MediaTypeIndicator(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 * @param modifier Modifier for the search bar
 */
@Composable
fun BookSearchBar(
    searchFieldState: TextFieldState,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
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
        placeholder = { Text(stringResource(StringRes.books_search_placeholder)) },
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
