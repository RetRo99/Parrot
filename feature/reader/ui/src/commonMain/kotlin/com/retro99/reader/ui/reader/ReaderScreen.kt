package com.retro99.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.retro99.base.nowMillis
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.LoadingScreen
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.model.backgroundColor
import com.retro99.reader.ui.publication.EpubPublication
import com.retro99.translations.StringRes
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_close
import resources.translations.reader_readaloud_no_audio
import resources.translations.reader_time_remaining_less_than_minute
import resources.translations.reader_time_remaining_minutes
import resources.translations.reader_toc_jumped_to_chapter
import resources.translations.reader_toc_title
import resources.translations.reader_toc_undo
import resources.translations.settings_icon_content_description

/** Duration in milliseconds before auto-hiding the media controls */
private const val CONTROLS_AUTO_HIDE_DELAY_MS = 5000L

@Composable
fun ReaderScreen(
    bookUuid: String,
    bookType: BookType,
    onClose: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = koinViewModel {
        parametersOf(bookUuid, bookType, onClose, onSettingsClick)
    },
) {
    // Intercept hardware back press to ensure audio progress is saved before navigation
    val backHandlerState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backHandlerState,
        onBackCompleted = { viewModel.close() },
    )

    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        ReaderScreenContent(
            bookUuid = bookUuid,
            viewState = viewState,
            intentDispatcher = intentDispatcher,
        )
    }
}

@Composable
internal fun ReaderScreenContent(
    bookUuid: String,
    viewState: ReaderViewState,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
) {
    // Hide system bars (status bar, navigation bar) for immersive reading when enabled
    if (viewState.currentSettings?.fullscreenMode == true) {
        HideSystemBars()
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {

        val movableLoader = movableContentOf {
            LoadingScreen()
        }
        when {
            viewState.publication != null -> {
                ReaderContent(
                    bookUuid = bookUuid,
                    publication = viewState.publication,
                    currentSettings = viewState.currentSettings,
                    isReadAloud = viewState.isReadAloud,
                    isPlaying = viewState.isPlaying,
                    currentAudioPositionMs = viewState.currentAudioPositionMs,
                    totalDurationMs = viewState.totalDurationMs,
                    playbackSpeed = viewState.playbackSpeed,
                    isAudioPlayerReady = viewState.isAudioPlayerReady,
                    tableOfContents = viewState.tableOfContents,
                    isTocVisible = viewState.isTocVisible,
                    previousTocPosition = viewState.previousTocPosition,
                    lastKnownPosition = viewState.lastKnownPosition,
                    chapterInfo = viewState.chapterInfo,
                    chapterReadingTimeInfo = viewState.chapterReadingTimeInfo,
                    currentTime = viewState.currentTime,
                    intentDispatcher = intentDispatcher,
                    loader = movableLoader,
                )
            }
            viewState.error != null -> {
                ReaderErrorView(
                    message = viewState.error.message ?: "An error occurred",
                    onRetry = { intentDispatcher(ReaderIntent.Retry) },
                )
            }
            else -> {
                movableLoader()
            }
        }

        viewState.positionConflict?.let { conflict ->
            PositionConflictDialog(
                conflict = conflict,
                onUseLocal = { intentDispatcher(ReaderIntent.UseLocalPosition) },
                onUseRemote = { intentDispatcher(ReaderIntent.UseRemotePosition) },
            )
        }

        NoAudioSnackbar(
            showMessage = viewState.showNoAudioMessage,
            onDismiss = { intentDispatcher(ReaderIntent.DismissNoAudioMessage) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun NoAudioSnackbar(
    showMessage: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val noAudioMessage = stringResource(StringRes.reader_readaloud_no_audio)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            snackbarHostState.showSnackbar(
                message = noAudioMessage,
                duration = SnackbarDuration.Short,
            )
            onDismiss()
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    bookUuid: String,
    publication: EpubPublication,
    currentSettings: ReaderSettingsUiModel?,
    isReadAloud: Boolean,
    isPlaying: Boolean,
    currentAudioPositionMs: Long,
    totalDurationMs: Long?,
    playbackSpeed: Float,
    tableOfContents: List<TocItemUiModel>,
    isTocVisible: Boolean,
    previousTocPosition: PositionUiModel?,
    lastKnownPosition: PositionUiModel?,
    chapterInfo: ChapterInfo?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    currentTime: String,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    isAudioPlayerReady: Boolean,
    loader: @Composable (() -> Unit),
) {
    val settings = currentSettings ?: publication.initialSettings
    var tempScale by remember(settings.fontSize) { mutableStateOf(settings.fontSize) }
    var isZooming by remember { mutableStateOf(false) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(0L) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(areControlsVisible, lastInteractionTime) {
        if (areControlsVisible) {
            delay(CONTROLS_AUTO_HIDE_DELAY_MS)
            areControlsVisible = false
        }
    }

    val onControlsInteraction: () -> Unit = {
        lastInteractionTime = nowMillis()
        areControlsVisible = true
    }

    val backgroundColor = settings.theme.backgroundColor()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {

        AnimatedProgressBar(
            settings = settings,
            areControlsVisible = areControlsVisible,
            position = ProgressBarPosition.TOP,
            lastKnownPosition = lastKnownPosition,
            chapterInfo = chapterInfo,
            chapterReadingTimeInfo = chapterReadingTimeInfo,
            currentTime = currentTime,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { containerSize = it },
        ) {
            EpubReaderView(
                bookUuid = bookUuid,
                publication = publication,
                intentDispatcher = intentDispatcher,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = settings.marginVertical.dp)
                    .readerGestures(
                        containerSize = containerSize,
                        consumeDoubleTaps = !isReadAloud,
                        onZoomChange = { scale ->
                            isZooming = true
                            tempScale = (settings.fontSize * scale).coerceIn(0.5, 3.0)
                            onControlsInteraction()
                        },
                        onZoomEnd = { finalScale ->
                            val newFontSize = (settings.fontSize * finalScale).coerceIn(0.5, 3.0)
                            intentDispatcher(
                                ReaderIntent.UpdateSettings(
                                    settings.copy(fontSize = newFontSize)
                                )
                            )
                            isZooming = false
                        },
                        onLeftTap = {
                            intentDispatcher(ReaderIntent.GoToPreviousPage)
                        },
                        onRightTap = {
                            intentDispatcher(ReaderIntent.GoToNextPage)
                        },
                        onMiddleTap = {
                            areControlsVisible = !areControlsVisible
                            if (areControlsVisible) {
                                lastInteractionTime = nowMillis()
                            }
                        },
                    ),
            )

            // Loading overlay for ReadAloud books while audio player initializes
            if (isReadAloud && !isAudioPlayerReady) {
                loader()
            }

            // Visual Overlay (Shows only while pinching)
            if (isZooming) {
                Box(
                    modifier = Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "${(tempScale * 100).toInt()}%",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // ReadAloud audio controls
            if (isReadAloud && isAudioPlayerReady) {
                this@Column.AnimatedVisibility(
                    visible = areControlsVisible,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ReadAloudControls(
                        isPlaying = isPlaying,
                        currentPositionMs = currentAudioPositionMs,
                        totalDurationMs = totalDurationMs,
                        playbackSpeed = playbackSpeed,
                        intentDispatcher = intentDispatcher,
                        onInteraction = onControlsInteraction,
                        onSwipeDown = { areControlsVisible = false },
                    )
                }
            }

            // Top toolbar with settings and TOC icons
            this@Column.AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                ReaderToolbar(
                    onCloseClick = { intentDispatcher(ReaderIntent.Close) },
                    onTocClick = { intentDispatcher(ReaderIntent.ToggleToc) },
                    onSettingsClick = { intentDispatcher(ReaderIntent.OnSettingsClicked) },
                    onInteraction = onControlsInteraction,
                )
            }

            // Table of Contents bottom sheet
            if (isTocVisible) {
                TableOfContentsSheet(
                    tableOfContents = tableOfContents,
                    currentChapterHref = lastKnownPosition?.href,
                    onChapterClick = { href ->
                        intentDispatcher(ReaderIntent.GoToChapter(href, lastKnownPosition))
                    },
                    onDismiss = { intentDispatcher(ReaderIntent.ToggleToc) },
                )
            }

            ChapterNavigationUndoSnackbar(
                previousTocPosition = previousTocPosition,
                onUndo = { position ->
                    intentDispatcher(ReaderIntent.UndoChapterNavigation(position))
                },
                onDismiss = {
                    intentDispatcher(ReaderIntent.DismissChapterNavigationUndo)
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedProgressBar(
            settings = settings,
            areControlsVisible = areControlsVisible,
            position = ProgressBarPosition.BOTTOM,
            lastKnownPosition = lastKnownPosition,
            chapterInfo = chapterInfo,
            chapterReadingTimeInfo = chapterReadingTimeInfo,
            currentTime = currentTime,
        )
    }
}

@Composable
private fun AnimatedProgressBar(
    settings: ReaderSettingsUiModel,
    areControlsVisible: Boolean,
    position: ProgressBarPosition,
    lastKnownPosition: PositionUiModel?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    chapterInfo: ChapterInfo?,
    currentTime: String,
) {
    val isVisible = when (settings.showProgressBar) {
        true -> settings.progressBarPosition == position
        null -> areControlsVisible
        false -> false
    }
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        ReadingProgressBar(
            totalProgression = lastKnownPosition?.totalProgression,
            chapterInfo = chapterInfo,
            chapterReadingTimeInfo = chapterReadingTimeInfo,
            chapterTitle = lastKnownPosition?.title,
            chapterProgressDisplayMode = settings.chapterProgressDisplayMode,
            chapterProgression = lastKnownPosition?.progression,
            fixedPosition = lastKnownPosition?.position,
            showTotalProgress = settings.showTotalProgress,
            progressIndicatorMode = settings.progressIndicatorMode,
            currentTime = currentTime,
            showReadingTime = settings.showReadingTime,
        )
    }
}

@Composable
private fun ReaderToolbar(
    onCloseClick: () -> Unit,
    onTocClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Back button on the left
            IconButton(
                onClick = {
                    onInteraction()
                    onCloseClick()
                },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(StringRes.general_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // TOC and Settings buttons on the right
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                IconButton(
                    onClick = {
                        onInteraction()
                        onTocClick()
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(StringRes.reader_toc_title),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = {
                        onInteraction()
                        onSettingsClick()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(StringRes.settings_icon_content_description),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterNavigationUndoSnackbar(
    previousTocPosition: PositionUiModel?,
    onUndo: (PositionUiModel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val jumpedToChapterMessage = stringResource(StringRes.reader_toc_jumped_to_chapter)
    val undoLabel = stringResource(StringRes.reader_toc_undo)

    LaunchedEffect(previousTocPosition) {
        if (previousTocPosition != null) {
            val result = snackbarHostState.showSnackbar(
                message = jumpedToChapterMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> onUndo(previousTocPosition)
                SnackbarResult.Dismissed -> onDismiss()
            }
        } else {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    ) { snackbarData ->
        val dismissState = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                snackbarData.dismiss()
            }
        }
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {},
        ) {
            Snackbar(snackbarData = snackbarData)
        }
    }
}

@Composable
private fun ReadingProgressBar(
    totalProgression: Double?,
    chapterInfo: ChapterInfo?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    chapterTitle: String?,
    chapterProgressDisplayMode: ChapterProgressDisplayMode,
    chapterProgression: Double?,
    fixedPosition: Int?,
    showTotalProgress: Boolean,
    progressIndicatorMode: ProgressIndicatorMode,
    currentTime: String,
    showReadingTime: Boolean,
    modifier: Modifier = Modifier,
) {
    val totalProgress = totalProgression?.toFloat() ?: 0f
    val totalProgressPercent = (totalProgress * 100).toInt()
    val chapterProgress = chapterProgression?.toFloat() ?: 0f
    val chapterProgressPercent = chapterProgression?.let { (it * 100).toInt() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Show progress indicator based on mode
            when (progressIndicatorMode) {
                ProgressIndicatorMode.NONE -> {
                    // No progress indicator shown
                }

                ProgressIndicatorMode.CHAPTER -> {
                    LinearProgressIndicator(
                        progress = { chapterProgress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                ProgressIndicatorMode.BOOK -> {
                    LinearProgressIndicator(
                        progress = { totalProgress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Show chapter title with position info based on display mode
            val chapterText = buildString {
                chapterTitle?.let { append(it) }
                when (chapterProgressDisplayMode) {
                    ChapterProgressDisplayMode.NONE -> {
                        // No chapter progress info shown
                    }

                    ChapterProgressDisplayMode.PERCENTAGE -> {
                        chapterProgressPercent?.let { percent ->
                            if (isNotEmpty()) append(" ")
                            append("($percent%)")
                        }
                    }

                    ChapterProgressDisplayMode.RELATIVE -> {
                        chapterInfo?.let { info ->
                            if (isNotEmpty()) append(" ")
                            append("(${info.currentPage}/${info.totalPages})")
                        }
                    }

                    ChapterProgressDisplayMode.FIXED -> {
                        fixedPosition?.let { position ->
                            if (isNotEmpty()) append(" ")
                            append("($position)")
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                // Current time on the left (formatted according to user's locale)
                // Only shown when showCurrentTime setting is enabled (currentTime is non-empty)
                if (currentTime.isNotEmpty()) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }

                // Centered chapter title
                if (chapterText.isNotEmpty()) {
                    Text(
                        text = chapterText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // Right side: reading time and/or total progress
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Show estimated reading time if enabled
                    if (showReadingTime && chapterReadingTimeInfo != null) {
                        val readingTimeText = if (chapterReadingTimeInfo.remainingMinutes < 1) {
                            stringResource(StringRes.reader_time_remaining_less_than_minute)
                        } else {
                            stringResource(
                                StringRes.reader_time_remaining_minutes,
                                chapterReadingTimeInfo.remainingMinutes,
                            )
                        }
                        Text(
                            text = readingTimeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Show total book progress percentage if enabled
                    if (showTotalProgress) {
                        Text(
                            text = "$totalProgressPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
