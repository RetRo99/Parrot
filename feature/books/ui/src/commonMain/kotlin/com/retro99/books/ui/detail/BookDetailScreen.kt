package com.retro99.books.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.retro99.base.result.AppError
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.backdropColorScheme
import com.retro99.base.ui.compose.rememberDominantColorState
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.domain.BookFileTransfer
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
import resources.translations.books_delete_local_button
import resources.translations.books_delete_local_confirm
import resources.translations.books_delete_local_message
import resources.translations.books_delete_local_title
import resources.translations.books_detail_action_continue_listening
import resources.translations.books_detail_action_continue_reading
import resources.translations.books_detail_action_download_failed
import resources.translations.books_detail_action_download_to_listen
import resources.translations.books_detail_action_download_to_read
import resources.translations.books_detail_action_downloading
import resources.translations.books_detail_action_listen
import resources.translations.books_detail_action_read
import resources.translations.books_detail_description
import resources.translations.books_detail_publication_date
import resources.translations.books_detail_remove_download
import resources.translations.books_detail_secondary_formats
import resources.translations.books_detail_remove_favorite
import resources.translations.books_detail_series
import resources.translations.books_detail_show_less
import resources.translations.books_detail_show_more
import resources.translations.books_detail_tags
import resources.translations.books_media_audio
import resources.translations.books_media_delete
import resources.translations.books_media_download
import resources.translations.books_media_ebook
import resources.translations.books_media_readaloud
import resources.translations.books_media_ready
import resources.translations.books_progress_local
import resources.translations.books_progress_remote
import resources.translations.cloud_backup_attestation_checkbox
import resources.translations.cloud_backup_button
import resources.translations.cloud_backup_cancel
import resources.translations.cloud_backup_confirm
import resources.translations.cloud_backup_failed
import resources.translations.cloud_backup_progress
import resources.translations.cloud_backup_retry
import resources.translations.cloud_backup_title
import resources.translations.cloud_backup_queued
import resources.translations.cloud_backup_complete
import resources.translations.cloud_backup_cancelled
import resources.translations.cloud_backup_finishing
import resources.translations.cloud_backup_reason_attestation_required
import resources.translations.cloud_backup_reason_content_blocked
import resources.translations.cloud_backup_reason_file_exists
import resources.translations.cloud_backup_reason_generic
import resources.translations.cloud_backup_reason_quota_exceeded
import resources.translations.cloud_backup_reason_verify_failed
import resources.translations.cloud_backup_replace_button
import resources.translations.cloud_backup_replace_message
import resources.translations.cloud_backup_replace_title
import resources.translations.cloud_backup_replacing
import resources.translations.cloud_download_cancel
import resources.translations.cloud_download_cancelled
import resources.translations.cloud_download_complete
import resources.translations.cloud_download_failed
import resources.translations.cloud_download_finishing
import resources.translations.cloud_download_progress
import resources.translations.cloud_download_queued
import resources.translations.cloud_download_reason_generic
import resources.translations.cloud_download_reason_unavailable
import resources.translations.cloud_download_reason_verify_failed
import resources.translations.cloud_download_retry
import resources.translations.cloud_backup_unknown_state
import resources.translations.cloud_backup_delete_button
import resources.translations.cloud_backup_delete_title
import resources.translations.cloud_backup_delete_message
import resources.translations.cloud_backup_delete_confirm
import resources.translations.books_reading_progress
import resources.translations.general_back
import resources.translations.general_cancel
import resources.translations.reader_conflict_use_local
import resources.translations.reader_conflict_use_remote
import com.retro99.books.ui.components.PositionConflictDialog

private val CoverWidth = 120.dp
private val DescriptionCollapsedMaxHeight = 140.dp
private const val DescriptionExpandThreshold = 200

@Composable
fun BookDetailScreen(
    serverId: String,
    bookUuid: String,
    onNavigateToReader: (serverId: String, bookUuid: String, bookType: BookType, bookTitle: String) -> Unit,
    onNavigateToSeriesDetail: (seriesUuid: String, seriesName: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookDetailViewModel = koinViewModel {
        parametersOf(serverId, bookUuid, onNavigateToReader, onNavigateToSeriesDetail, onBack)
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
                showDeleteLocalBookConfirmation = viewState.showDeleteLocalBookConfirmation,
                isFavorite = viewState.isFavorite,
                progressInfo = viewState.progressInfo,
                isResolvingConflict = viewState.isResolvingConflict,
                conflictResolutionError = viewState.conflictResolutionError,
                pendingOpenBookType = viewState.pendingOpenBookType,
                supportsBookBackup = viewState.supportsBookBackup,
                supportsBookDeletion = viewState.supportsBookDeletion,
                cloudBackupDeleteConfirmationType = viewState.cloudBackupDeleteConfirmationType,
                showBackupConfirmation = viewState.showBackupConfirmation,
                backupRightsAttested = viewState.backupRightsAttested,
                bookFileTransfers = viewState.bookFileTransfers,
                bookFileTransferError = viewState.bookFileTransferError,
                replaceBackupConfirmationTransferId = viewState.replaceBackupConfirmationTransferId,
                replacingBackupTransferId = viewState.replacingBackupTransferId,
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
    showDeleteLocalBookConfirmation: Boolean,
    isFavorite: Boolean,
    progressInfo: BookProgressInfoUiModel?,
    isResolvingConflict: Boolean,
    conflictResolutionError: AppError?,
    pendingOpenBookType: BookType?,
    supportsBookBackup: Boolean,
    supportsBookDeletion: Boolean,
    cloudBackupDeleteConfirmationType: BookType?,
    showBackupConfirmation: Boolean,
    backupRightsAttested: Boolean,
    bookFileTransfers: List<BookFileTransfer>,
    bookFileTransferError: String?,
    replaceBackupConfirmationTransferId: String?,
    replacingBackupTransferId: String?,
    intentDispatcher: IntentDispatcher<BookDetailIntent>,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val hasBackdrop = book.coverUrl != null
    val dominantColorState = rememberDominantColorState(
        url = book.coverUrl,
        cacheKey = book.uuid,
        defaultColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            MaterialTheme.colorScheme.surface
        } else {
            Color(0xFF1A1A1A)
        },
    )
    val dominantColor by dominantColorState
    val outerScheme = MaterialTheme.colorScheme
    val contentScheme = remember(dominantColor, hasBackdrop, outerScheme) {
        if (hasBackdrop) backdropColorScheme(dominantColor) else outerScheme
    }

    LaunchedEffect(conflictResolutionError) {
        conflictResolutionError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error.message ?: "Failed to sync reading position",
                duration = SnackbarDuration.Short,
            )
            intentDispatcher(BookDetailIntent.OnConflictResolutionErrorDismissed)
        }
    }
    deleteConfirmationBookType?.let { bookType ->
        DeleteCacheConfirmationDialog(
            bookType = bookType,
            onConfirm = { intentDispatcher(BookDetailIntent.OnDeleteCacheConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnDeleteCacheDismissed) },
        )
    }

    if (showDeleteLocalBookConfirmation) {
        DeleteLocalBookConfirmationDialog(
            bookTitle = book.title,
            onConfirm = { intentDispatcher(BookDetailIntent.OnDeleteLocalBookConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnDeleteLocalBookDismissed) },
        )
    }

    if (showBackupConfirmation) {
        BackupRightsConfirmationDialog(
            bookTitle = book.title,
            attested = backupRightsAttested,
            onAttestedChanged = { intentDispatcher(BookDetailIntent.OnBackupAttestationChanged(it)) },
            onConfirm = { intentDispatcher(BookDetailIntent.OnBackupConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnBackupDismissed) },
        )
    }

    cloudBackupDeleteConfirmationType?.let { bookType ->
        DeleteCloudBackupConfirmationDialog(
            bookTitle = book.title,
            bookType = bookType,
            onConfirm = { intentDispatcher(BookDetailIntent.OnDeleteCloudBackupConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnDeleteCloudBackupDismissed) },
        )
    }

    if (replaceBackupConfirmationTransferId != null) {
        ReplaceCloudBackupConfirmationDialog(
            bookTitle = book.title,
            onConfirm = { intentDispatcher(BookDetailIntent.OnReplaceBackupConfirmed) },
            onDismiss = { intentDispatcher(BookDetailIntent.OnReplaceBackupDismissed) },
        )
    }

    LaunchedEffect(bookFileTransferError) {
        bookFileTransferError?.let { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    if (pendingOpenBookType != null && progressInfo?.hasConflict == true) {
        PositionConflictDialog(
            localProgressPercent = progressInfo.localProgressPercent ?: 0,
            remoteProgressPercent = progressInfo.remoteProgressPercent ?: 0,
            onUseLocal = { intentDispatcher(BookDetailIntent.OnUseLocalPositionClicked) },
            onUseRemote = { intentDispatcher(BookDetailIntent.OnUseRemotePositionClicked) },
            onDismissRequest = { intentDispatcher(BookDetailIntent.OnConflictDialogDismissed) },
        )
    }

    MaterialTheme(colorScheme = contentScheme) {
        Scaffold(
            modifier = modifier,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                                    contentDescription = if (favorite) {
                                        stringResource(StringRes.books_detail_remove_favorite)
                                    } else {
                                        stringResource(StringRes.books_detail_add_favorite)
                                    },
                                    tint = if (favorite) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                book.coverUrl?.let { coverUrl ->
                    CoilImage(
                        data = coverUrl,
                        cacheKey = book.uuid,
                        modifier = Modifier.fillMaxSize().blur(60.dp),
                        contentScale = ContentScale.FillBounds,
                        contentDescription = null,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    BookHeader(book = book)

                    Spacer(modifier = Modifier.height(16.dp))

                    PrimaryMediaAction(
                        book = book,
                        ebookDownloadState = ebookDownloadState,
                        audiobookDownloadState = audiobookDownloadState,
                        readaloudDownloadState = readaloudDownloadState,
                        progressPercent = progressInfo?.progressPercent ?: 0,
                        intentDispatcher = intentDispatcher,
                    )

                    val mediaFormatCount =
                        listOf(book.hasEbook, book.hasReadaloud, book.hasAudiobook).count { it }
                    if (mediaFormatCount > 1) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionTitle(text = stringResource(StringRes.books_detail_secondary_formats))
                        Spacer(modifier = Modifier.height(12.dp))

                        MediaActionButtons(
                            book = book,
                            ebookDownloadState = ebookDownloadState,
                            audiobookDownloadState = audiobookDownloadState,
                            readaloudDownloadState = readaloudDownloadState,
                            intentDispatcher = intentDispatcher,
                        )
                    } else {
                        // One format: the primary action above already covers it,
                        // so no duplicate card — only housekeeping for the on-device copy.
                        Spacer(modifier = Modifier.height(16.dp))
                        SingleFormatHousekeeping(
                            book = book,
                            ebookDownloadState = ebookDownloadState,
                            audiobookDownloadState = audiobookDownloadState,
                            readaloudDownloadState = readaloudDownloadState,
                            intentDispatcher = intentDispatcher,
                        )
                    }

                    if (supportsBookBackup) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { intentDispatcher(BookDetailIntent.OnBackupClicked) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = stringResource(StringRes.cloud_backup_button),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(StringRes.cloud_backup_button))
                        }
                    }

                    if (supportsBookDeletion && book is BookUiModel.StorytellerBook) {
                        book.mediaResources
                            .filter { resource ->
                                resource.cloudBookFileId != null &&
                                    resource.remoteAvailability in setOf("Available", "Deleting")
                            }
                            .forEach { resource ->
                                val bookType = BookType.fromValue(resource.mediaType)
                                val mediaTypeName = when (bookType) {
                                    BookType.EBOOK -> stringResource(StringRes.books_media_ebook)
                                    BookType.READALOUD -> stringResource(StringRes.books_media_readaloud)
                                    BookType.AUDIOBOOK -> stringResource(StringRes.books_media_audio)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        intentDispatcher(BookDetailIntent.OnDeleteCloudBackupClicked(bookType))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            StringRes.cloud_backup_delete_button,
                                            mediaTypeName,
                                        ),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(
                                            StringRes.cloud_backup_delete_button,
                                            mediaTypeName,
                                        ),
                                    )
                                }
                            }
                    }

                    if (bookFileTransfers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        BookBackupProgressSection(
                            transfers = bookFileTransfers,
                            replacingTransferId = replacingBackupTransferId,
                            onCancel = { transferId ->
                                intentDispatcher(BookDetailIntent.OnCancelBookFileTransferClicked(transferId))
                            },
                            onRetry = { transferId ->
                                intentDispatcher(BookDetailIntent.OnRetryBookBackupClicked(transferId))
                            },
                            onReplace = { transferId ->
                                intentDispatcher(BookDetailIntent.OnReplaceBackupClicked(transferId))
                            },
                        )
                    }

                    progressInfo?.displayProgression?.let { progress ->
                        if (progress > 0.0) {
                            Spacer(modifier = Modifier.height(20.dp))
                            ReadingProgressSection(
                                progress = progress,
                                progressPercent = progressInfo.progressPercent,
                                hasConflict = progressInfo.hasConflict,
                                localProgression = progressInfo.localProgression,
                                localProgressPercent = progressInfo.localProgressPercent,
                                remoteProgression = progressInfo.remoteProgression,
                                remoteProgressPercent = progressInfo.remoteProgressPercent,
                                isResolvingConflict = isResolvingConflict,
                                onUseLocal = { intentDispatcher(BookDetailIntent.OnUseLocalPositionClicked) },
                                onUseRemote = { intentDispatcher(BookDetailIntent.OnUseRemotePositionClicked) },
                            )
                        }
                    }

                    book.description?.let { description ->
                        val readableDescription = remember(description) {
                            plainTextDescription(description)
                        }
                        if (readableDescription.isNotBlank()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            SectionDivider()
                            Spacer(modifier = Modifier.height(20.dp))
                            DescriptionSection(description = readableDescription)
                        }
                    }

                    if (book.tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionDivider()
                        Spacer(modifier = Modifier.height(20.dp))
                        TagsSection(
                            tags = book.tags,
                            onTagClick = { intentDispatcher(BookDetailIntent.OnTagClicked(it)) },
                        )
                    }

                    if (book.series.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionDivider()
                        Spacer(modifier = Modifier.height(20.dp))
                        SeriesSection(
                            series = book.series,
                            onSeriesClick = { intentDispatcher(BookDetailIntent.OnSeriesClicked(it)) },
                        )
                    }

                    if (book is BookUiModel.LocalBook) {
                        Spacer(modifier = Modifier.height(20.dp))
                        SectionDivider()
                        Spacer(modifier = Modifier.height(20.dp))
                        DeleteLocalBookButton(
                            onClick = { intentDispatcher(BookDetailIntent.OnDeleteLocalBookClicked) },
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun BookHeader(
    book: BookUiModel,
    modifier: Modifier = Modifier,
) {
    val coverEntrance = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) {
        coverEntrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoilImage(
            data = book.coverUrl,
            cacheKey = book.uuid,
            modifier = Modifier
                .width(CoverWidth)
                .aspectRatio(2f / 3f)
                .scale(coverEntrance.value)
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentScale = ContentScale.Crop,
            contentDescription = book.title,
        )

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            book.subtitle?.let { subtitle ->
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (book.authors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                MetadataItem(
                    label = "Author",
                    value = book.authors.joinToString(", "),
                )
            }

            val ratingValue = book.rating
            if (ratingValue != null && ratingValue > 0f) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = ratingValue.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            val publicationDisplay = remember(book.publicationDate) {
                formatPublicationDate(book.publicationDate)
            }
            if (publicationDisplay != null) {
                Spacer(modifier = Modifier.height(6.dp))
                MetadataItem(
                    label = stringResource(StringRes.books_detail_publication_date),
                    value = publicationDisplay,
                )
            }
        }
    }
}

/**
 * Turns raw metadata dates into something a reader can scan at a glance:
 * "2021" instead of "2021-05-04T00:00:00.000Z". Placeholder dates such as
 * "0101-01-01..." have no meaning for a reader, so they are hidden entirely.
 *
 * @return the display value, or null when the date should not be shown.
 */
private fun formatPublicationDate(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val datePart = value.take(10).split("-")
    if (datePart.size < 3) return value
    val year = datePart[0].toIntOrNull() ?: return value
    if (year < 1000) return null
    return year.toString()
}

@Composable
private fun MetadataItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
    )
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun ReadingProgressSection(
    progress: Double,
    progressPercent: Int,
    hasConflict: Boolean,
    localProgression: Double?,
    localProgressPercent: Int?,
    remoteProgression: Double?,
    remoteProgressPercent: Int?,
    isResolvingConflict: Boolean,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(text = stringResource(StringRes.books_reading_progress))
        Spacer(modifier = Modifier.height(12.dp))

        if (hasConflict && localProgression != null && remoteProgression != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProgressRow(
                    label = stringResource(StringRes.books_progress_local),
                    progression = localProgression,
                    percentText = "$localProgressPercent%",
                    barColor = MaterialTheme.colorScheme.primary,
                )
                ProgressRow(
                    label = stringResource(StringRes.books_progress_remote),
                    progression = remoteProgression,
                    percentText = "$remoteProgressPercent%",
                    barColor = MaterialTheme.colorScheme.tertiary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onUseLocal,
                        enabled = !isResolvingConflict,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(StringRes.reader_conflict_use_local))
                    }
                    OutlinedButton(
                        onClick = onUseRemote,
                        enabled = !isResolvingConflict,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(StringRes.reader_conflict_use_remote))
                    }
                }
            }
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.toFloat(),
                animationSpec = tween(durationMillis = 600),
                label = "readingProgress",
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(
    label: String,
    progression: Double,
    percentText: String,
    barColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(50.dp),
        )
        val animatedProgress by animateFloatAsState(
            targetValue = progression.toFloat(),
            animationSpec = tween(durationMillis = 600),
            label = "progressBar",
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = percentText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The one action a reader should see first: read, continue where they left off,
 * listen, or download. Falls back to the per-format cards below when the book
 * has no media available.
 */
@Composable
private fun PrimaryMediaAction(
    book: BookUiModel,
    ebookDownloadState: DownloadState,
    audiobookDownloadState: DownloadState,
    readaloudDownloadState: DownloadState,
    progressPercent: Int,
    intentDispatcher: IntentDispatcher<BookDetailIntent>,
    modifier: Modifier = Modifier,
) {
    val primaryType = when {
        book.hasEbook -> BookType.EBOOK
        book.hasReadaloud -> BookType.READALOUD
        book.hasAudiobook -> BookType.AUDIOBOOK
        else -> null
    }
    if (primaryType == null) return

    val rawState = when (primaryType) {
        BookType.EBOOK -> ebookDownloadState
        BookType.AUDIOBOOK -> audiobookDownloadState
        BookType.READALOUD -> readaloudDownloadState
    }
    val state = if (book is BookUiModel.LocalBook) DownloadState.Cached else rawState
    val isAudio = primaryType != BookType.EBOOK
    val isCached = state is DownloadState.Cached
    val isDownloading = state is DownloadState.Downloading
    val isFailed = state is DownloadState.Failed

    val label = when {
        isDownloading -> {
            val percent = (state as DownloadState.Downloading).progress
                ?.let { (it * 100).toInt() }
            if (percent != null) {
                stringResource(StringRes.books_detail_action_downloading, percent)
            } else {
                stringResource(StringRes.books_detail_action_downloading, 0)
            }
        }
        isFailed -> stringResource(StringRes.books_detail_action_download_failed)
        !isCached && isAudio -> stringResource(StringRes.books_detail_action_download_to_listen)
        !isCached -> stringResource(StringRes.books_detail_action_download_to_read)
        isAudio && progressPercent > 0 ->
            stringResource(StringRes.books_detail_action_continue_listening, progressPercent)
        !isAudio && progressPercent > 0 ->
            stringResource(StringRes.books_detail_action_continue_reading, progressPercent)
        isAudio -> stringResource(StringRes.books_detail_action_listen)
        else -> stringResource(StringRes.books_detail_action_read)
    }

    Button(
        onClick = {
            if (isCached) {
                when (primaryType) {
                    BookType.EBOOK -> intentDispatcher(BookDetailIntent.OnReadEbookClicked)
                    BookType.AUDIOBOOK -> intentDispatcher(BookDetailIntent.OnPlayAudiobookClicked)
                    BookType.READALOUD -> intentDispatcher(BookDetailIntent.OnReadReadaloudClicked)
                }
            } else {
                intentDispatcher(BookDetailIntent.OnDownloadClicked(primaryType))
            }
        },
        enabled = !isDownloading,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Icon(
                imageVector = if (isAudio) Icons.Filled.PlayArrow else Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val isLocalBook = book is BookUiModel.LocalBook
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (book.hasEbook) {
            MediaButton(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                label = stringResource(StringRes.books_media_ebook),
                downloadState = ebookDownloadState,
                isLocalBook = isLocalBook,
                canDeleteCache = book.localOrigin(BookType.EBOOK) != "import",
                onReadClick = { intentDispatcher(BookDetailIntent.OnReadEbookClicked) },
                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.EBOOK)) },
                onDeleteClick = {
                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.EBOOK))
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (book.hasAudiobook) {
            MediaButton(
                icon = Icons.Filled.Headphones,
                label = stringResource(StringRes.books_media_audio),
                downloadState = audiobookDownloadState,
                isLocalBook = isLocalBook,
                canDeleteCache = book.localOrigin(BookType.AUDIOBOOK) != "import",
                onReadClick = { intentDispatcher(BookDetailIntent.OnPlayAudiobookClicked) },
                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.AUDIOBOOK)) },
                onDeleteClick = {
                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.AUDIOBOOK))
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (book.hasReadaloud) {
            MediaButton(
                icon = Icons.Outlined.RecordVoiceOver,
                label = stringResource(StringRes.books_media_readaloud),
                downloadState = readaloudDownloadState,
                isLocalBook = isLocalBook,
                canDeleteCache = book.localOrigin(BookType.READALOUD) != "import",
                onReadClick = { intentDispatcher(BookDetailIntent.OnReadReadaloudClicked) },
                onDownloadClick = { intentDispatcher(BookDetailIntent.OnDownloadClicked(BookType.READALOUD)) },
                onDeleteClick = {
                    intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(BookType.READALOUD))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun BookUiModel.localOrigin(bookType: BookType): String? =
    (this as? BookUiModel.StorytellerBook)
        ?.mediaResources
        ?.firstOrNull { resource -> resource.mediaType.equals(bookType.value, ignoreCase = true) }
        ?.localOrigin

/**
 * Single-format books only: the primary action already covers reading/listening,
 * so all that is left here is managing the on-device copy.
 */
@Composable
private fun SingleFormatHousekeeping(
    book: BookUiModel,
    ebookDownloadState: DownloadState,
    audiobookDownloadState: DownloadState,
    readaloudDownloadState: DownloadState,
    intentDispatcher: IntentDispatcher<BookDetailIntent>,
    modifier: Modifier = Modifier,
) {
    val primaryType = when {
        book.hasEbook -> BookType.EBOOK
        book.hasReadaloud -> BookType.READALOUD
        book.hasAudiobook -> BookType.AUDIOBOOK
        else -> return
    }
    val rawState = when (primaryType) {
        BookType.EBOOK -> ebookDownloadState
        BookType.AUDIOBOOK -> audiobookDownloadState
        BookType.READALOUD -> readaloudDownloadState
    }
    val isCached = (book is BookUiModel.LocalBook) || rawState is DownloadState.Cached
    val canDeleteCache = book.localOrigin(primaryType) != "import"
    if (!isCached || !canDeleteCache) return

    TextButton(
        onClick = { intentDispatcher(BookDetailIntent.OnDeleteCacheClicked(primaryType)) },
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(StringRes.books_detail_remove_download),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(StringRes.books_detail_remove_download),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    label: String,
    downloadState: DownloadState,
    isLocalBook: Boolean,
    canDeleteCache: Boolean,
    onReadClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveState = if (isLocalBook) DownloadState.Cached else downloadState
    val isDownloading = effectiveState is DownloadState.Downloading
    val isCached = effectiveState is DownloadState.Cached
    val downloadProgress = (effectiveState as? DownloadState.Downloading)?.progress

    Surface(
        onClick = {
            when {
                isCached -> onReadClick()
                else -> onDownloadClick()
            }
        },
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(16.dp),
        ),
        shape = RoundedCornerShape(16.dp),
        color = if (isCached) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.background
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloading) {
                    Box(contentAlignment = Alignment.Center) {
                        if (downloadProgress != null) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(StringRes.general_cancel),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Icon(
                        imageVector = if (isCached) Icons.AutoMirrored.Outlined.MenuBook else icon,
                        contentDescription = if (isCached) {
                            label
                        } else {
                            stringResource(StringRes.books_media_download)
                        },
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                    if (downloadProgress != null) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(StringRes.general_cancel),
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = stringResource(StringRes.general_cancel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
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
                            contentDescription = stringResource(StringRes.books_media_ready),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(StringRes.books_media_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                        )
                    }

                    if (!isLocalBook && canDeleteCache) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(StringRes.books_media_delete),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onDeleteClick)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = stringResource(StringRes.books_media_download),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(StringRes.books_media_download),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 6.sp,
                                maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Publishers deliver descriptions as HTML (`<p><b>…`), which the markdown
 * renderer draws as nothing at all - leaving an empty "Description" heading.
 * Convert markup to plain text so the reader sees the words, and let callers
 * hide the section entirely when nothing readable is left.
 */
private fun plainTextDescription(description: String): String {
    val withBreaks = description
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</?[a-zA-Z][^>]*>"), " ")
    return withBreaks
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex(" +([,.;:!?)])"), "$1")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

@Composable
private fun DescriptionSection(
    description: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val isLongDescription = description.length > DescriptionExpandThreshold

    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(text = stringResource(StringRes.books_detail_description))
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = tween(durationMillis = 200),
            ),
        ) {
            if (isLongDescription && !expanded) {
                Box(
                    modifier = Modifier.heightIn(max = DescriptionCollapsedMaxHeight),
                ) {
                    Markdown(
                        content = description,
                        colors = markdownColor(
                            text = MaterialTheme.colorScheme.onSurface,
                        ),
                        typography = markdownTypography(
                            text = MaterialTheme.typography.bodyMedium,
                        ),
                    )
                }
            } else {
                Markdown(
                    content = description,
                    colors = markdownColor(
                        text = MaterialTheme.colorScheme.onSurface,
                    ),
                    typography = markdownTypography(
                        text = MaterialTheme.typography.bodyMedium,
                    ),
                )
            }

            if (isLongDescription) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(
                        start = 0.dp,
                        end = 0.dp,
                        top = 0.dp,
                        bottom = 0.dp,
                    ),
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(StringRes.books_detail_show_less)
                        } else {
                            stringResource(StringRes.books_detail_show_more)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
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
        SectionTitle(text = stringResource(StringRes.books_detail_tags))
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                SuggestionChip(
                    onClick = { onTagClick(tag) },
                    label = {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    ),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesSection(
    series: List<SeriesUiModel>,
    onSeriesClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(text = stringResource(StringRes.books_detail_series))
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            series.forEach { seriesItem ->
                SuggestionChip(
                    onClick = { onSeriesClick(seriesItem.uuid) },
                    label = {
                        Text(
                            text = buildString {
                                append(seriesItem.name)
                                seriesItem.position?.also { append(" #$it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                    ),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
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
        BookType.EBOOK -> stringResource(StringRes.books_media_ebook)
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

@Composable
private fun DeleteLocalBookConfirmationDialog(
    bookTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(StringRes.books_delete_local_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = stringResource(StringRes.books_delete_local_message, bookTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(StringRes.books_delete_local_confirm),
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

@Composable
private fun DeleteCloudBackupConfirmationDialog(
    bookTitle: String,
    bookType: BookType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mediaTypeName = when (bookType) {
        BookType.EBOOK -> stringResource(StringRes.books_media_ebook)
        BookType.AUDIOBOOK -> stringResource(StringRes.books_media_audio)
        BookType.READALOUD -> stringResource(StringRes.books_media_readaloud)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(StringRes.cloud_backup_delete_title)) },
        text = {
            Text(
                stringResource(StringRes.cloud_backup_delete_message, mediaTypeName, bookTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(StringRes.cloud_backup_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun ReplaceCloudBackupConfirmationDialog(
    bookTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(StringRes.cloud_backup_replace_title)) },
        text = {
            Text(
                stringResource(StringRes.cloud_backup_replace_message, bookTitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(StringRes.cloud_backup_replace_button),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun BackupRightsConfirmationDialog(
    bookTitle: String,
    attested: Boolean,
    onAttestedChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(StringRes.cloud_backup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(bookTitle, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = attested, onCheckedChange = onAttestedChanged)
                    Text(
                        text = stringResource(StringRes.cloud_backup_attestation_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = attested) {
                Text(stringResource(StringRes.cloud_backup_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(StringRes.general_cancel))
            }
        },
    )
}

@Composable
private fun BookBackupProgressSection(
    transfers: List<BookFileTransfer>,
    replacingTransferId: String?,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onReplace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleTransfers = transfers.filterNot { it.state == "cancelled" }
    val transfer = visibleTransfers.firstOrNull {
        it.state in setOf("pending", "transferring", "verifying", "finalizing")
    }
        ?: visibleTransfers.firstOrNull()
        ?: return
    val fraction = if (transfer.totalBytes > 0) {
        (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
    } else {
        null
    }
    val progressPercent = ((fraction ?: 0f) * 100).toInt()
    val isDownload = transfer.direction == "download"
    val statusText = when (transfer.state) {
        "pending" -> stringResource(
            if (isDownload) StringRes.cloud_download_queued else StringRes.cloud_backup_queued,
        )
        "transferring" -> stringResource(
            if (isDownload) StringRes.cloud_download_progress else StringRes.cloud_backup_progress,
            progressPercent,
        )
        "verifying", "finalizing" -> stringResource(
            if (isDownload) StringRes.cloud_download_finishing else StringRes.cloud_backup_finishing,
        )
        "completed" -> stringResource(
            if (isDownload) StringRes.cloud_download_complete else StringRes.cloud_backup_complete,
        )
        "failed" -> stringResource(
            if (isDownload) StringRes.cloud_download_failed else StringRes.cloud_backup_failed,
            localizedTransferFailureReason(isDownload, transfer.lastError),
        )
        "cancelled" -> stringResource(
            if (isDownload) StringRes.cloud_download_cancelled else StringRes.cloud_backup_cancelled,
        )
        else -> stringResource(StringRes.cloud_backup_unknown_state)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        if (transfer.state in setOf("pending", "transferring", "verifying", "finalizing")) {
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = "${formatByteCount(transfer.bytesTransferred)} / ${formatByteCount(transfer.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onCancel(transfer.transferId) }) {
                Text(
                    stringResource(
                        if (isDownload) StringRes.cloud_download_cancel else StringRes.cloud_backup_cancel,
                    ),
                )
            }
            if (transfer.state == "pending" && transfer.attemptCount > 0 && transfer.lastError != null) {
                TextButton(onClick = { onRetry(transfer.transferId) }) {
                    Text(
                        stringResource(
                            if (isDownload) StringRes.cloud_download_retry else StringRes.cloud_backup_retry,
                        ),
                    )
                }
            }
        } else if (transfer.state == "failed") {
            if (transfer.transferId == replacingTransferId) {
                Text(stringResource(StringRes.cloud_backup_replacing))
            } else if (!isDownload && transfer.lastError == "file_exists") {
                TextButton(onClick = { onReplace(transfer.transferId) }) {
                    Text(stringResource(StringRes.cloud_backup_replace_button))
                }
            } else {
                TextButton(onClick = { onRetry(transfer.transferId) }) {
                    Text(
                        stringResource(
                            if (isDownload) StringRes.cloud_download_retry else StringRes.cloud_backup_retry,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun localizedTransferFailureReason(isDownload: Boolean, reason: String?): String = when {
    isDownload && reason == "verify_failed" -> stringResource(StringRes.cloud_download_reason_verify_failed)
    isDownload && reason in setOf("cloud_file_unavailable", "cloud_file_changed") ->
        stringResource(StringRes.cloud_download_reason_unavailable)
    isDownload -> stringResource(StringRes.cloud_download_reason_generic)
    reason == "file_exists" -> stringResource(StringRes.cloud_backup_reason_file_exists)
    reason == "quota_exceeded" -> stringResource(StringRes.cloud_backup_reason_quota_exceeded)
    reason == "content_blocked" -> stringResource(StringRes.cloud_backup_reason_content_blocked)
    reason == "attestation_required" -> stringResource(StringRes.cloud_backup_reason_attestation_required)
    reason == "verify_failed" -> stringResource(StringRes.cloud_backup_reason_verify_failed)
    else -> stringResource(StringRes.cloud_backup_reason_generic)
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun DeleteLocalBookButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(StringRes.books_delete_local_button),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(StringRes.books_delete_local_button),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
