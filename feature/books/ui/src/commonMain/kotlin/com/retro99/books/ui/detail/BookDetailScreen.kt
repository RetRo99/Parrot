package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.CoilImage
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.SeriesUiModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.DownloadState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.books_delete_cache_confirm
import resources.translations.books_delete_cache_message
import resources.translations.books_delete_cache_title
import resources.translations.books_detail_add_favorite
import resources.translations.books_detail_description
import resources.translations.books_detail_remove_favorite
import resources.translations.books_detail_series
import resources.translations.books_detail_tags
import resources.translations.books_media_audio
import resources.translations.books_media_delete
import resources.translations.books_media_download
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.books_media_ready
import resources.translations.general_back
import resources.translations.general_cancel

@Composable
fun BookDetailScreen(
    bookUuid: String,
    onNavigateToReader: (bookUuid: String, bookType: BookType) -> Unit,
    onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = koinViewModel {
        parametersOf(bookUuid, onNavigateToReader, onNavigateToSeriesDetail, onBack)
    },
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        when {
            viewState.isLoading -> LoadingScreen()
            viewState.book != null -> BookDetailScreenContent(
                book = viewState.book,
                ebookDownloadState = viewState.ebookDownloadState,
                audiobookDownloadState = viewState.audiobookDownloadState,
                readaloudDownloadState = viewState.readaloudDownloadState,
                deleteConfirmationBookType = viewState.deleteConfirmationBookType,
                isFavorite = viewState.isFavorite,
                intentDispatcher = intentDispatcher,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailScreenContent(
    book: BookUiModel,
    ebookDownloadState: DownloadState,
    audiobookDownloadState: DownloadState,
    readaloudDownloadState: DownloadState,
    deleteConfirmationBookType: BookType?,
    isFavorite: Boolean,
    intentDispatcher: IntentDispatcher<BookDetailIntent>,
    modifier: Modifier = Modifier,
) {
    deleteConfirmationBookType?.let { bookType ->
        DeleteCacheConfirmationDialog(
            bookType = bookType,
            onConfirm = { intentDispatcher(BookDetailIntent.OnDeleteCacheConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnDeleteCacheDismissed) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { intentDispatcher(BookDetailIntent.OnBackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(StringRes.general_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { intentDispatcher(BookDetailIntent.OnFavoriteClicked) }) {
                        Icon(
                            imageVector = if (isFavorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (isFavorite) {
                                stringResource(StringRes.books_detail_remove_favorite)
                            } else {
                                stringResource(StringRes.books_detail_add_favorite)
                            },
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            BookHeader(book = book)

            Spacer(modifier = Modifier.height(24.dp))

            MediaActionButtons(
                book = book,
                ebookDownloadState = ebookDownloadState,
                audiobookDownloadState = audiobookDownloadState,
                readaloudDownloadState = readaloudDownloadState,
                intentDispatcher = intentDispatcher,
            )

            book.description?.let { description ->
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                DescriptionSection(description = description)
            }

            if (book.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                TagsSection(
                    tags = book.tags,
                    onTagClick = { intentDispatcher(BookDetailIntent.OnTagClicked(it)) },
                )
            }

            if (book.series.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SeriesSection(
                    series = book.series,
                    onSeriesClick = { intentDispatcher(BookDetailIntent.OnSeriesClicked(it)) },
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BookHeader(
    book: BookUiModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoilImage(
            data = book.coverUrl,
            cacheKey = "${book.uuid}_detail",
            modifier = Modifier
                .width(180.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
            contentDescription = book.title,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        book.subtitle?.let { subtitle ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (book.authors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = book.authors.joinToString(", "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        book.rating?.let { rating ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = rating.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MediaActionButtons(
    book: BookUiModel,
    ebookDownloadState: DownloadState,
    audiobookDownloadState: DownloadState,
    readaloudDownloadState: DownloadState,
    intentDispatcher: IntentDispatcher<BookDetailIntent>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        if (book.hasEbook) {
            MediaButton(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                label = stringResource(StringRes.books_media_ebook),
                downloadState = ebookDownloadState,
                onReadClick = { intentDispatcher(BookDetailIntent.OnReadEbookClicked) },
                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.EBOOK)) },
                onDeleteClick = {
                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.EBOOK))
                },
            )
        }
//        if (book.hasAudiobook) {
//            MediaButton(
//                icon = Icons.Outlined.Headphones,
//                label = stringResource(StringRes.books_media_audio),
//                downloadState = audiobookDownloadState,
//                onReadClick = { intentDispatcher(BookDetailIntent.OnPlayAudiobookClicked) },
//                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.AUDIOBOOK)) },
//                onDeleteClick = {
//                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.AUDIOBOOK))
//                },
//            )
//        }
        if (book.hasReadaloud) {
            MediaButton(
                icon = Icons.Outlined.RecordVoiceOver,
                label = stringResource(StringRes.books_media_readaloud),
                downloadState = readaloudDownloadState,
                onReadClick = { intentDispatcher(BookDetailIntent.OnReadReadaloudClicked) },
                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.READALOUD)) },
                onDeleteClick = {
                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.READALOUD))
                },
            )
        }
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    label: String,
    downloadState: DownloadState,
    onReadClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDownloading = downloadState is DownloadState.Downloading
    val isCached = downloadState is DownloadState.Cached
    downloadState is DownloadState.Idle ||
            downloadState is DownloadState.Failed
    val downloadProgress = (downloadState as? DownloadState.Downloading)?.progress

    ElevatedCard(
        onClick = {
            when {
                isCached -> onReadClick()
                else -> onDownloadClick() // Handles both idle (start) and downloading (cancel)
            }
        },
        modifier = modifier.width(100.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloading) {
                    // Show progress indicator around a close icon
                    Box(contentAlignment = Alignment.Center) {
                        if (downloadProgress != null) {
                            // Determinate progress indicator when progress is known
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                    alpha = 0.3f,
                                ),
                            )
                        } else {
                            // Indeterminate progress indicator when progress is unknown
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        // Close icon in the center to indicate tap to cancel
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(StringRes.general_cancel),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            when {
                isDownloading -> {
                    // Show progress percentage
                    if (downloadProgress != null) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    // Show cancel hint
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(StringRes.general_cancel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                isCached -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(StringRes.books_media_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(StringRes.books_media_delete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onDeleteClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                else -> {
                    // Idle or Failed - show download option
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(StringRes.books_media_download),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptionSection(
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.books_detail_description),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Markdown(
            content = description,
            colors = markdownColor(
                text = MaterialTheme.colorScheme.onSurfaceVariant,

                ),
            typography = markdownTypography(
                text = MaterialTheme.typography.bodyMedium,
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<String>,
    onTagClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.books_detail_tags),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                SuggestionChip(
                    onClick = { onTagClick(tag) },
                    label = { Text(text = tag) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SeriesSection(
    series: List<SeriesUiModel>,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(StringRes.books_detail_series),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        series.forEach { seriesItem ->
            SuggestionChip(
                onClick = { onSeriesClick(seriesItem.uuid) },
                label = {
                    Text(
                        text = buildString {
                            append(seriesItem.name)
                            seriesItem.position?.also { append(" #$it") }
                        },
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun DeleteCacheConfirmationDialog(
    bookType: BookType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mediaTypeName = when (bookType) {
        BookType.EBOOK, BookType.IMPORTED -> stringResource(StringRes.books_media_ebook)
        BookType.AUDIOBOOK -> stringResource(StringRes.books_media_audio)
        BookType.READALOUD -> stringResource(StringRes.books_media_readaloud)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(StringRes.books_delete_cache_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(StringRes.books_delete_cache_message, mediaTypeName),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(StringRes.books_delete_cache_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(StringRes.general_cancel))
            }
        },
    )
}
