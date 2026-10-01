package com.retro99.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import co.touchlab.kermit.Logger
import com.retro99.base.nowMillis
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.LoadingScreen
import com.retro99.base.ui.compose.TooltipIconButton
import com.retro99.books.domain.model.BookType
import com.retro99.books.ui.components.LinkedResumeDialog
import com.retro99.books.ui.components.toUiModel
import com.retro99.reader.domain.model.ChapterProgressDisplayMode
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ProgressIndicatorMode
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.model.backgroundColor
import com.retro99.reader.ui.publication.PublicationState
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_close
import resources.translations.mini_player_pause
import resources.translations.mini_player_play
import resources.translations.reader_action_bookmarks
import resources.translations.reader_action_readaloud
import resources.translations.reader_action_settings
import resources.translations.reader_action_toc
import resources.translations.reader_bookmark_added
import resources.translations.reader_bookmark_already_exists
import resources.translations.reader_bookmark_no_more_bookmarks
import resources.translations.reader_bookmark_save_failed
import resources.translations.reader_bookmark_undo
import resources.translations.reader_page_of_pages
import resources.translations.reader_position_save_failed
import resources.translations.reader_position_save_retry
import resources.translations.reader_readaloud_no_audio
import resources.translations.reader_tts_playback_failed
import resources.translations.reader_tts_playback_retry
import resources.translations.reader_time_remaining_less_than_minute
import resources.translations.reader_time_remaining_minutes
import resources.translations.reader_toc_jumped_to_chapter
import resources.translations.reader_toc_undo
import resources.translations.reader_tts_pause
import resources.translations.reader_tts_read_aloud
import resources.translations.reader_tts_voice_settings
import resources.translations.resume_linked_compare
import resources.translations.settings_changed
import resources.translations.settings_tts_enabled
import resources.translations.settings_undo
import resources.translations.sleep_timer_ending_soon_title
import resources.translations.sleep_timer_let_it_end
import resources.translations.sleep_timer_postpone
import kotlin.math.abs

private val logger = Logger.withTag("ReaderScreen")

/** Duration in milliseconds before auto-hiding the media controls */
private const val CONTROLS_AUTO_HIDE_DELAY_MS = 5000L

@Composable
fun ReaderScreen(
    serverId: String,
    bookUuid: String,
    bookType: BookType,
    isLastBookOnLaunch: Boolean = false,
    readerOpenEntryPoint: String? = null,
    readerOpenCorrelationId: String? = null,
    linkedResumeResolved: Boolean = false,
    listenMode: Boolean = false,
    onComparePositions: () -> Unit = {},
    onClose: (ReaderCloseSource) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = koinViewModel {
        parametersOf(
            serverId,
            bookUuid,
            bookType,
            isLastBookOnLaunch,
            onClose,
            onSettingsClick,
            readerOpenEntryPoint,
            readerOpenCorrelationId,
            linkedResumeResolved,
            onComparePositions,
        )
    },
) {
    // Intercept hardware back press to ensure audio progress is saved before navigation
    val backHandlerState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backHandlerState,
        onBackCompleted = { viewModel.close(ReaderCloseSource.SystemBack) },
    )

    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        var listenStarted by remember(bookUuid, listenMode) { mutableStateOf(false) }
        LaunchedEffect(listenMode, viewState.isAudioPlayerReady,
            viewState.linkedResumeOffer, viewState.positionConflict) {
            if (listenMode && !listenStarted && viewState.isAudioPlayerReady &&
                viewState.linkedResumeOffer == null && viewState.positionConflict == null) {
                listenStarted = true
                intentDispatcher(ReaderIntent.StartListening(ListenSource.NARRATION))
            }
        }
        ReaderScreenContent(
            bookUuid = bookUuid,
            viewState = viewState,
            intentDispatcher = intentDispatcher,
        )
    }
}

@Composable
private fun ReaderScreenContent(
    bookUuid: String,
    viewState: ReaderViewState,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
) {
    KeepScreenOn(
        enabled = viewState.currentSettings?.fullscreenMode == true ||
            (
                    (viewState.isReadAloud || viewState.isTtsReadAloud) &&
                    viewState.isPlaying &&
                    viewState.currentSettings?.keepScreenOnDuringAudio == true
                ),
    )

    // Hide system bars (status bar, navigation bar) for immersive reading when enabled
    if (viewState.currentSettings?.fullscreenMode == true) {
        HideSystemBars()
    }

    // Focus requester to ensure the reader can receive key events
    val focusRequester = remember { FocusRequester() }

    // Request focus when the reader content is first displayed
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Audio-only mode: show audiobook-style UI for ReadAloud books
    if (viewState.isReadAloud && viewState.isAudioPlayerReady && viewState.isAudioOnlyMode) {
        ReadAloudAudioOnlyView(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            onExit = { intentDispatcher(ReaderIntent.ToggleAudioOnlyMode) },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handlePageNavigationKeyEvent(
                    event = event,
                    settings = viewState.currentSettings,
                    isReadAloud = viewState.isReadAloud,
                    intentDispatcher = intentDispatcher,
                )
            },
    ) {

        val movableLoader = movableContentOf {
            LoadingScreen()
        }
        when {
            viewState.publicationState != null -> {
                ReaderOverlayContent(
                    bookUuid = bookUuid,
                    viewState = viewState,
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

        // ReaderToolbar owns the close action, but it only exists once content is loaded
        // and auto-hides after CONTROLS_AUTO_HIDE_DELAY_MS. Before publicationState is set
        // there is otherwise no visible way out of a slow or failed open.
        if (viewState.publicationState == null) {
            TooltipIconButton(
                tooltip = stringResource(StringRes.general_close),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = { intentDispatcher(ReaderIntent.Close) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 4.dp),
            )
        }

        viewState.linkedResumeOffer?.let { offer ->
            LinkedResumeDialog(
                model = offer.toUiModel(),
                onContinue = { intentDispatcher(ReaderIntent.ContinueLinkedResume) },
                onStay = { intentDispatcher(ReaderIntent.StayLinkedResume) },
                compareAll = {
                    TextButton(
                        onClick = { intentDispatcher(ReaderIntent.CompareLinkedPositions) },
                    ) {
                        Text(stringResource(StringRes.resume_linked_compare))
                    }
                },
            )
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

        TtsPlaybackFailedSnackbar(
            showMessage = viewState.showTtsPlaybackFailed,
            onRetry = { intentDispatcher(ReaderIntent.RetryTtsPlayback) },
            onDismiss = { intentDispatcher(ReaderIntent.DismissTtsPlaybackFailed) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        BookmarkSaveFailedSnackbar(
            showMessage = viewState.showBookmarkSaveFailed,
            onDismiss = { intentDispatcher(ReaderIntent.DismissBookmarkSaveFailed) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        PositionSaveFailedSnackbar(
            showMessage = viewState.showPositionSaveFailed,
            onRetry = { intentDispatcher(ReaderIntent.RetryPositionSave) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        BookmarkAddedSnackbar(
            showMessage = viewState.showBookmarkAdded,
            bookmarkId = viewState.lastAddedBookmarkId,
            onUndo = { id -> intentDispatcher(ReaderIntent.UndoBookmark(id)) },
            onDismiss = { intentDispatcher(ReaderIntent.DismissBookmarkAdded) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        BookmarkAlreadyExistsSnackbar(
            showMessage = viewState.showBookmarkAlreadyExists,
            onDismiss = { intentDispatcher(ReaderIntent.DismissBookmarkAlreadyExists) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        NoMoreBookmarksSnackbar(
            showMessage = viewState.showNoMoreBookmarks,
            onDismiss = { intentDispatcher(ReaderIntent.DismissNoMoreBookmarks) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (viewState.showSleepTimerWarningPrompt && viewState.sleepTimerRemainingMs != null) {
            SleepTimerDurationDialog(
                title = stringResource(StringRes.sleep_timer_ending_soon_title),
                message = "Playback will pause in ${
                    formatSleepTimerLabel(viewState.sleepTimerRemainingMs)
                }. Choose how many more minutes to keep listening.",
                confirmLabel = stringResource(StringRes.sleep_timer_postpone),
                dismissLabel = stringResource(StringRes.sleep_timer_let_it_end),
                initialMinutes = 5,
                onConfirm = { minutes ->
                    intentDispatcher(ReaderIntent.StartSleepTimer(minutes * 60_000L))
                },
                onDismiss = {
                    intentDispatcher(ReaderIntent.DismissSleepTimerWarning)
                },
            )
        }
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

@Composable
private fun TtsPlaybackFailedSnackbar(
    showMessage: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_tts_playback_failed)
    val retryLabel = stringResource(StringRes.reader_tts_playback_retry)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            when (snackbarHostState.showSnackbar(
                message = message,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Long,
            )) {
                SnackbarResult.ActionPerformed -> onRetry()
                SnackbarResult.Dismissed -> onDismiss()
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
private fun BookmarkSaveFailedSnackbar(
    showMessage: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_bookmark_save_failed)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            snackbarHostState.showSnackbar(
                message = message,
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

@Composable
private fun PositionSaveFailedSnackbar(
    showMessage: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showMessage) return
    val message = stringResource(StringRes.reader_position_save_failed)
    val retryLabel = stringResource(StringRes.reader_position_save_retry)

    Snackbar(
        modifier = modifier,
        action = { TextButton(onClick = onRetry) { Text(retryLabel) } },
    ) { Text(message) }
}

@Composable
private fun BookmarkAddedSnackbar(
    showMessage: Boolean,
    bookmarkId: String?,
    onUndo: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_bookmark_added)
    val undoLabel = stringResource(StringRes.reader_bookmark_undo)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> {
                    bookmarkId?.let { id -> onUndo(id) }
                }
                SnackbarResult.Dismissed -> onDismiss()
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
private fun BookmarkAlreadyExistsSnackbar(
    showMessage: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_bookmark_already_exists)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            snackbarHostState.showSnackbar(
                message = message,
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

@Composable
private fun NoMoreBookmarksSnackbar(
    showMessage: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = stringResource(StringRes.reader_bookmark_no_more_bookmarks)

    LaunchedEffect(showMessage) {
        if (showMessage) {
            snackbarHostState.showSnackbar(
                message = message,
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

@Composable
internal fun AnimatedProgressBar(
    settings: ReaderSettingsUiModel,
    areControlsVisible: Boolean,
    position: ProgressBarPosition,
    lastKnownPosition: PositionUiModel?,
    chapterReadingTimeInfo: ChapterReadingTimeInfo?,
    chapterInfo: ChapterInfo?,
    currentTime: String,
    audioStatus: String? = null,
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
            audioStatus = audioStatus,
        )
    }
}

@Composable
private fun ReaderToolbar(
    bookTitle: String,
    onCloseClick: () -> Unit,
    onTocClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showTtsAction: Boolean,
    ttsEnabled: Boolean,
    isTtsPlaying: Boolean,
    onTtsEnabledChange: (Boolean) -> Unit,
    onTtsPlayPause: () -> Unit,
    onVoiceSettings: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(StringRes.general_close)
    val tocLabel = stringResource(StringRes.reader_action_toc)
    val bookmarksLabel = stringResource(StringRes.reader_action_bookmarks)
    val readAloudLabel = stringResource(StringRes.reader_action_readaloud)
    val settingsLabel = stringResource(StringRes.reader_action_settings)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipIconButton(
                tooltip = closeLabel,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = {
                    onInteraction()
                    onCloseClick()
                },
            )

            Text(
                text = bookTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            TooltipIconButton(
                tooltip = tocLabel,
                icon = Icons.AutoMirrored.Filled.List,
                onClick = {
                    onInteraction()
                    onTocClick()
                },
            )

            TooltipIconButton(
                tooltip = bookmarksLabel,
                icon = Icons.Default.Bookmark,
                onClick = {
                    onInteraction()
                    onBookmarksClick()
                },
            )

            if (showTtsAction) {
                Box {
                    var ttsExpanded by remember { mutableStateOf(false) }
                    TooltipIconButton(
                        tooltip = readAloudLabel,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        onClick = {
                            onInteraction()
                            ttsExpanded = true
                        },
                    )
                    DropdownMenu(
                        expanded = ttsExpanded,
                        onDismissRequest = { ttsExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(StringRes.settings_tts_enabled)) },
                            trailingIcon = {
                                Switch(
                                    checked = ttsEnabled,
                                    onCheckedChange = null,
                                )
                            },
                            onClick = {
                                onInteraction()
                                onTtsEnabledChange(!ttsEnabled)
                            },
                        )
                        if (ttsEnabled) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isTtsPlaying) {
                                            stringResource(StringRes.reader_tts_pause)
                                        } else {
                                            stringResource(StringRes.reader_tts_read_aloud)
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isTtsPlaying) {
                                            Icons.Default.Pause
                                        } else {
                                            Icons.Default.PlayArrow
                                        },
                                        contentDescription = if (isTtsPlaying) {
                                            stringResource(StringRes.mini_player_pause)
                                        } else {
                                            stringResource(StringRes.mini_player_play)
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    ttsExpanded = false
                                    onInteraction()
                                    onTtsPlayPause()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(StringRes.reader_tts_voice_settings))
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = stringResource(StringRes.reader_tts_voice_settings),
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    ttsExpanded = false
                                    onInteraction()
                                    onVoiceSettings()
                                },
                            )
                        }
                    }
                }
            }

            TooltipIconButton(
                tooltip = settingsLabel,
                icon = Icons.Default.Settings,
                onClick = {
                    onInteraction()
                    onSettingsClick()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeUndoSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterNavigationUndoSnackbar(
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
    audioStatus: String? = null,
) {
    val totalProgress = totalProgression?.toFloat() ?: 0f
    val totalProgressPercent = bookPercent(totalProgression)
    val chapterProgress = chapterProgression?.toFloat() ?: 0f
    val chapterProgressPercent = chapterProgression?.let { (it * 100).toInt() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Ember.colors.surface,
        shadowElevation = 0.dp,
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
                        color = Ember.colors.accent,
                        trackColor = Ember.colors.track,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                ProgressIndicatorMode.BOOK -> {
                    LinearProgressIndicator(
                        progress = { totalProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Ember.colors.accent,
                        trackColor = Ember.colors.track,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Chapter title (can be truncated)
            val chapterTitleText = chapterTitle ?: ""

            // Page info based on display mode (should always be visible)
            val pageInfoText = when (chapterProgressDisplayMode) {
                ChapterProgressDisplayMode.NONE -> ""
                ChapterProgressDisplayMode.PERCENTAGE -> {
                    chapterProgressPercent?.let { "($it%)" } ?: ""
                }
                ChapterProgressDisplayMode.RELATIVE -> {
                    chapterInfo?.let {
                        stringResource(StringRes.reader_page_of_pages, it.currentPage, it.totalPages)
                    } ?: ""
                }
                ChapterProgressDisplayMode.FIXED -> {
                    fixedPosition?.let { "($it)" } ?: ""
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Current time on the left (formatted according to user's locale)
                // Only shown when showCurrentTime setting is enabled (currentTime is non-empty)
                if (currentTime.isNotEmpty()) {
                    Text(
                        text = currentTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ember.colors.ink2,
                        maxLines = 1,
                    )
                }

                // Centered chapter title - uses weight to take remaining space and truncate if needed
                Text(
                    text = chapterTitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ember.colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )

                // Right side: page info, reading time and/or total progress
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Show page info (always visible when enabled)
                    if (pageInfoText.isNotEmpty()) {
                        Text(
                            text = pageInfoText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ember.colors.ink2,
                        )
                    }

                    // While listening, the audio position replaces the reading-time estimate.
                    if (audioStatus != null) {
                        Text(
                            text = audioStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ember.colors.ink2,
                        )
                    } else if (showReadingTime && chapterReadingTimeInfo != null) {
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
                            color = Ember.colors.ink2,
                        )
                    }

                    // Show total book progress percentage if enabled
                    if (showTotalProgress) {
                        Text(
                            text = "$totalProgressPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ember.colors.ink2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Handles key events for page navigation in the reader.
 *
 * Page Up/Down and Direction keys always control page navigation, respecting user's
 * configured actions for "up" and "down" buttons.
 *
 * Volume buttons only control page navigation when:
 * 1. Volume button navigation is enabled in settings
 * 2. Not in read-aloud mode (users need volume buttons for audio control)
 *
 * @return true if the key event was consumed, false otherwise
 */
private fun handlePageNavigationKeyEvent(
    event: KeyEvent,
    settings: ReaderSettingsUiModel?,
    isReadAloud: Boolean,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
): Boolean {
    // Only handle key down events to avoid double-triggering
    if (event.type != KeyEventType.KeyDown) return false

    // Get configured actions, with sensible defaults
    val upAction = settings?.volumeUpAction ?: NavigationAction.NEXT_PAGE
    val downAction = settings?.volumeDownAction ?: NavigationAction.PREVIOUS_PAGE

    return when (event.key) {
        // Page Up/Down and Direction keys always control page navigation
        // They respect the same action configuration as volume buttons
        Key.PageUp, Key.DirectionUp -> {
            dispatchNavigationAction(upAction, intentDispatcher)
            true
        }
        Key.PageDown, Key.DirectionDown -> {
            dispatchNavigationAction(downAction, intentDispatcher)
            true
        }
        // Volume buttons only work when enabled and not in read-aloud mode
        Key.VolumeUp -> {
            if (settings?.volumeButtonsEnabled == true && !isReadAloud) {
                dispatchNavigationAction(upAction, intentDispatcher)
                true
            } else {
                false
            }
        }
        Key.VolumeDown -> {
            if (settings?.volumeButtonsEnabled == true && !isReadAloud) {
                dispatchNavigationAction(downAction, intentDispatcher)
                true
            } else {
                false
            }
        }
        else -> false
    }
}

/**
 * Dispatches the appropriate navigation intent based on the action.
 */
private fun dispatchNavigationAction(
    action: NavigationAction,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
) {
    when (action) {
        NavigationAction.NEXT_PAGE -> intentDispatcher(ReaderIntent.GoToNextPage)
        NavigationAction.PREVIOUS_PAGE -> intentDispatcher(ReaderIntent.GoToPreviousPage)
    }
}
