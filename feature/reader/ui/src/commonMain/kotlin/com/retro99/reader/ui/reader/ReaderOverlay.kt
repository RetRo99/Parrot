package com.retro99.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import com.retro99.reader.domain.model.ProgressBarPosition
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.retro99.base.nowMillis
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.RelativeTime
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.model.relativeTimeFromIso
import com.retro99.reader.ui.model.backgroundColor
import com.retro99.reader.ui.publication.PublicationState
import com.retro99.reader.ui.reader.ReaderViewState
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import resources.translations.action_delete
import resources.translations.action_edit
import resources.translations.general_back
import resources.translations.general_close
import resources.translations.reader_bookmark_days_ago
import resources.translations.reader_bookmark_hours_ago
import resources.translations.reader_bookmark_just_now
import resources.translations.reader_bookmark_minutes_ago
import resources.translations.reader_bookmark_rename
import resources.translations.reader_bookmark_rename_cancel
import resources.translations.reader_bookmark_rename_confirm
import resources.translations.reader_bookmark_rename_label
import resources.translations.reader_bookmarks_empty_overlay
import resources.translations.reader_overlay_bookmark_location
import resources.translations.reader_overlay_chapter_page_count
import resources.translations.reader_overlay_contents
import resources.translations.reader_overlay_current_chapter
import resources.translations.reader_overlay_device_voice
import resources.translations.reader_overlay_device_voice_description
import resources.translations.reader_overlay_display
import resources.translations.reader_overlay_listen
import resources.translations.reader_overlay_listening
import resources.translations.reader_overlay_minutes_left
import resources.translations.reader_overlay_narration
import resources.translations.reader_overlay_narration_description
import resources.translations.reader_overlay_narration_unavailable
import resources.translations.reader_overlay_page_in_chapter
import resources.translations.reader_overlay_search
import resources.translations.reader_overlay_start_listening
import resources.translations.reader_overlay_voice
import resources.translations.reader_overlay_page_in_chapter
import resources.translations.reader_page_of_pages
import resources.translations.reader_search_empty
import resources.translations.reader_search_failed
import resources.translations.reader_search_hint
import resources.translations.reader_search_no_results
import resources.translations.reader_search_result_count
import resources.translations.reader_search_title
import resources.translations.reader_toc_no_chapters
import resources.translations.reader_toc_no_results
import resources.translations.reader_toc_search_hint
import resources.translations.settings_changed
import resources.translations.settings_undo
import resources.translations.sleep_timer_cancel
import resources.translations.sleep_timer_custom
import resources.translations.sleep_timer_end_of_audio
import resources.translations.sleep_timer_ending_soon_title
import resources.translations.sleep_timer_let_it_end
import resources.translations.sleep_timer_postpone
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SWIPE_HIDE_THRESHOLD_PX = 80f
private val MINI_PLAYER_HEIGHT = 51.dp

@Composable
internal fun ReaderOverlayContent(
    bookUuid: String,
    viewState: ReaderViewState,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    loader: @Composable (() -> Unit),
) {
    val publicationState = viewState.publicationState ?: return
    val settings = publicationState.settings
    val currentPosition = publicationState.position
    var controlsVisible by remember(bookUuid) { mutableStateOf(true) }
    var lastInteractionTime by remember(bookUuid) { mutableStateOf(0L) }
    var isZooming by remember { mutableStateOf(false) }
    var temporaryFontScale by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat()) }
    var readerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val fontUndoState = androidx.compose.material3.SnackbarHostState()
    val snackbarMessage = stringResource(StringRes.settings_changed)
    val undoLabel = stringResource(StringRes.settings_undo)
    val openSheet = viewState.isTocVisible || viewState.isBookmarksVisible ||
        viewState.isListenSheetVisible || viewState.isBookSearchVisible ||
        viewState.showSleepTimerWarningPrompt

    LaunchedEffect(controlsVisible, lastInteractionTime, openSheet, viewState.isNarrationStartPending) {
        if (viewState.isNarrationStartPending || viewState.showSleepTimerWarningPrompt) {
            controlsVisible = true
        } else if (controlsVisible && !openSheet) {
            delay(5_000L)
            if (!openSheet) controlsVisible = false
        }
    }

    val onInteraction: () -> Unit = {
        controlsVisible = true
        lastInteractionTime = nowMillis()
    }
    val currentBookmark = currentPosition?.let { position ->
        viewState.bookmarks.firstOrNull { bookmark ->
            bookmark.locatorHref == position.href && bookmark.position == position.position
        }
    }
    val currentChapterIndex = currentPosition?.chapterIndex
        ?: viewState.tableOfContents.indexOfFirst { it.href == currentPosition?.href }.takeIf { it >= 0 }
        ?: 0
    val chapterNumber = (currentChapterIndex + 1).coerceAtLeast(1)
    val chapterTitle = currentPosition?.title
        ?: viewState.tableOfContents.getOrNull(currentChapterIndex)?.title.orEmpty()
    val chapterProgress = (currentPosition?.progression ?: 0.0).coerceIn(0.0, 1.0)
    val pageInfo = viewState.chapterInfo?.let { info ->
        stringResource(StringRes.reader_overlay_page_in_chapter, info.currentPage, info.totalPages)
    } ?: "Page — of — in chapter"
    val remainingTime = viewState.chapterReadingTimeInfo?.let { info ->
        stringResource(StringRes.reader_overlay_minutes_left, info.remainingMinutes)
    } ?: "— min left in chapter"
    val selectedVoice = viewState.ttsVoices.firstOrNull { it.id == viewState.selectedTtsVoiceId }
    val voiceDetail = selectedVoice?.let { "${it.locale} · ${it.name.substringBefore('(').trim()}" }
        ?: "System voice"
    val isEink = Ember.style.isEink

    val showStrip = settings.showProgressBar == true
    val hasTopStrip = showStrip && settings.progressBarPosition == ProgressBarPosition.TOP
    val hasBottomStrip = showStrip && settings.progressBarPosition == ProgressBarPosition.BOTTOM
    val hasBottomBar = viewState.isListening || hasBottomStrip
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topInset = if (hasTopStrip) with(density) { topBarPx.toDp() } else 0.dp
    // The mini player collapses while the controls are shown; keep its height reserved so
    // the reader does not reflow when they toggle.
    val miniReserve = if (viewState.isListening && controlsVisible) MINI_PLAYER_HEIGHT else 0.dp
    val bottomInset = if (hasBottomBar) with(density) { bottomBarPx.toDp() } + miniReserve else 0.dp
    val progressBarTime = viewState.currentTime

    Box(
        Modifier.fillMaxSize().background(settings.theme.backgroundColor()).statusBarsPadding(),
    ) {
        Box(
            Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset).onSizeChanged { readerSize = it },
        ) {
            EpubReaderView(
                bookUuid = bookUuid,
                publicationState = publicationState,
                intentDispatcher = intentDispatcher,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = settings.marginVertical.dp)
                    .readerGestures(
                        containerSize = readerSize,
                        detectDoubleTaps = viewState.isReadAloud || (viewState.isTtsReadAloud && settings.ttsEnabled),
                        doubleTapTimeoutMs = settings.doubleTapTimeoutMs,
                        tapNavigationEnabled = settings.tapNavigationEnabled,
                        onZoomChange = { scale ->
                            isZooming = true
                            temporaryFontScale = (settings.fontSize * scale).toFloat().coerceIn(0.5f, 3f)
                            onInteraction()
                        },
                        onZoomEnd = { finalScale ->
                            val newFontSize = (settings.fontSize * finalScale).coerceIn(0.5, 3.0)
                            if (abs(newFontSize - settings.fontSize) > 0.001) {
                                val oldSettings = settings
                                intentDispatcher(ReaderIntent.UpdateSettings(settings.copy(fontSize = newFontSize)))
                                scope.launch {
                                    fontUndoState.currentSnackbarData?.dismiss()
                                    val result = fontUndoState.showSnackbar(
                                        message = snackbarMessage,
                                        actionLabel = undoLabel,
                                        duration = androidx.compose.material3.SnackbarDuration.Short,
                                    )
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                        intentDispatcher(ReaderIntent.UpdateSettings(oldSettings))
                                    }
                                }
                            }
                            isZooming = false
                        },
                        onLeftTap = {
                            when (settings.leftTapAction) {
                                NavigationAction.NEXT_PAGE -> intentDispatcher(ReaderIntent.GoToNextPage)
                                NavigationAction.PREVIOUS_PAGE -> intentDispatcher(ReaderIntent.GoToPreviousPage)
                            }
                        },
                        onRightTap = {
                            when (settings.rightTapAction) {
                                NavigationAction.NEXT_PAGE -> intentDispatcher(ReaderIntent.GoToNextPage)
                                NavigationAction.PREVIOUS_PAGE -> intentDispatcher(ReaderIntent.GoToPreviousPage)
                            }
                        },
                        onMiddleTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) lastInteractionTime = nowMillis()
                        },
                    ),
            )

            if (viewState.isReadAloud && !viewState.isAudioPlayerReady) loader()

            if (isZooming) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "${(temporaryFontScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            ChapterNavigationUndoSnackbar(
                previousTocPosition = viewState.previousTocPosition,
                onUndo = { intentDispatcher(ReaderIntent.UndoChapterNavigation(it)) },
                onDismiss = { intentDispatcher(ReaderIntent.DismissChapterNavigationUndo) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            FontSizeUndoSnackbarHost(
                hostState = fontUndoState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }


        // Persistent progress strip / mini player. Always composed (under the overlay) so the
        // reader keeps a constant size when the controls toggle.
        if (hasTopStrip) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Ember.colors.surface)
                    .onSizeChanged { topBarPx = it.height },
            ) {
                AnimatedProgressBar(
                    settings = settings,
                    areControlsVisible = true,
                    position = ProgressBarPosition.TOP,
                    lastKnownPosition = currentPosition,
                    chapterReadingTimeInfo = viewState.chapterReadingTimeInfo,
                    chapterInfo = viewState.chapterInfo,
                    currentTime = progressBarTime,
                )
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = if (isEink) EnterTransition.None else fadeIn() + slideInVertically { -it },
            exit = if (isEink) ExitTransition.None else fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
        ) {
            ReaderOverlayToolbar(
                bookTitle = viewState.bookTitle,
                bookAuthor = viewState.bookAuthor,
                isBookmarked = currentBookmark != null,
                onBack = { intentDispatcher(ReaderIntent.Close) },
                onBookmark = {
                    onInteraction()
                    if (currentBookmark == null) intentDispatcher(ReaderIntent.AddBookmark)
                    else intentDispatcher(ReaderIntent.DeleteBookmark(currentBookmark.id))
                },
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = if (isEink) EnterTransition.None else fadeIn() + slideInVertically { it },
                exit = if (isEink) ExitTransition.None else fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .then(
                        if (hasBottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier,
                    )
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onVerticalDrag = { _, delta -> total += delta },
                            onDragEnd = { if (total > SWIPE_HIDE_THRESHOLD_PX) controlsVisible = false },
                        )
                    },
            ) {
                if (viewState.isListening) {
                    val sourceLabel = if (viewState.isReadAloud) {
                        stringResource(StringRes.reader_overlay_narration)
                    } else {
                        "${stringResource(StringRes.reader_overlay_device_voice)} · ${selectedVoice?.locale ?: "English (US)"}"
                    }
                    ReaderListeningPanel(
                        sourceLabel = sourceLabel,
                        isPlaying = viewState.isPlaying,
                        isLoading = viewState.isNarrationLoading,
                        currentPositionMs = viewState.currentAudioPositionMs,
                        totalDurationMs = viewState.totalDurationMs,
                        playbackSpeed = if (viewState.isTtsReadAloud) settings.ttsRate else settings.playbackSpeed,
                        sleepTimerRemainingMs = viewState.sleepTimerRemainingMs,
                        isEink = isEink,
                        onVoice = { intentDispatcher(ReaderIntent.ToggleListenSheet) },
                        onStop = { intentDispatcher(ReaderIntent.StopListening) },
                        onSeek = { intentDispatcher(ReaderIntent.SeekTo(it)) },
                        onSpeed = { intentDispatcher(ReaderIntent.SetPlaybackSpeed(it)) },
                        onSkipBack = { intentDispatcher(ReaderIntent.SkipBackward()) },
                        onPlayPause = { intentDispatcher(ReaderIntent.TogglePlayback) },
                        onSkipForward = { intentDispatcher(ReaderIntent.SkipForward()) },
                        onStartSleepTimer = { intentDispatcher(ReaderIntent.StartSleepTimer(it)) },
                        onCancelSleepTimer = { intentDispatcher(ReaderIntent.CancelSleepTimer) },
                        onDismissSleepTimerWarning = { intentDispatcher(ReaderIntent.DismissSleepTimerWarning) },
                        onPostponeSleepTimer = { intentDispatcher(ReaderIntent.StartSleepTimer(it * 60_000L)) },
                        showSleepTimerWarning = viewState.showSleepTimerWarningPrompt,
                    )
                } else {
                    ReaderReadingPanel(
                        chapterNumber = chapterNumber,
                        chapterTitle = chapterTitle,
                        progression = chapterProgress,
                        bookProgression = currentPosition?.totalProgression,
                        pageInfo = pageInfo,
                        remainingTime = remainingTime,
                        currentPage = viewState.chapterInfo?.currentPage,
                        totalPages = viewState.chapterInfo?.totalPages,
                        isEink = isEink,
                        onSeek = { intentDispatcher(ReaderIntent.SeekToChapterProgress(it)) },
                        onContents = { intentDispatcher(ReaderIntent.ToggleToc) },
                        onSearch = { intentDispatcher(ReaderIntent.ToggleBookSearch) },
                        onListen = {
                            if (viewState.isReadAloud) {
                                intentDispatcher(ReaderIntent.StartListening(ListenSource.NARRATION))
                            } else {
                                intentDispatcher(ReaderIntent.ToggleListenSheet)
                            }
                        },
                        onListenLongPress = { intentDispatcher(ReaderIntent.ToggleListenSheet) },
                        onDisplay = { intentDispatcher(ReaderIntent.OnSettingsClicked) },
                    )
                }
            }

            if (hasBottomBar) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Ember.colors.surface)
                        .navigationBarsPadding()
                        .onSizeChanged { bottomBarPx = it.height },
                ) {
                    Column {
                        if (viewState.isListening) {
                            val progress = if (viewState.totalDurationMs != null && viewState.totalDurationMs > 0L) {
                                viewState.currentAudioPositionMs.toFloat() / viewState.totalDurationMs.toFloat()
                            } else {
                                0f
                            }
                            // The listening panel replaces the mini player while the controls are shown.
                            ReaderMiniPlayer(
                                isPlaying = viewState.isPlaying,
                                progression = progress,
                                onPlayPause = { intentDispatcher(ReaderIntent.TogglePlayback) },
                                modifier = Modifier.then(if (controlsVisible) Modifier.height(0.dp) else Modifier).clipToBounds(),
                            )
                        }
                        if (hasBottomStrip) {
                            Box {
                                AnimatedProgressBar(
                                    settings = settings,
                                    areControlsVisible = true,
                                    position = ProgressBarPosition.BOTTOM,
                                    lastKnownPosition = currentPosition,
                                    chapterReadingTimeInfo = viewState.chapterReadingTimeInfo,
                                    chapterInfo = viewState.chapterInfo,
                                    currentTime = progressBarTime,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (viewState.isTocVisible || viewState.isBookmarksVisible) {
        ReaderContentsSheet(
            tableOfContents = viewState.tableOfContents,
            currentHref = currentPosition?.href,
            currentProgress = currentPosition?.progression,
            currentPage = viewState.chapterInfo?.currentPage,
            currentChapterPages = viewState.chapterInfo?.totalPages,
            bookmarks = viewState.bookmarks,
            onChapterClick = { chapter ->
                intentDispatcher(ReaderIntent.GoToChapter(chapter.href, currentPosition))
            },
            onBookmarkClick = { intentDispatcher(ReaderIntent.GoToBookmark(it)) },
            onBookmarkDelete = { intentDispatcher(ReaderIntent.DeleteBookmark(it)) },
            onBookmarkRename = { id, title -> intentDispatcher(ReaderIntent.RenameBookmark(id, title)) },
            onBookmarkReorder = { intentDispatcher(ReaderIntent.ReorderBookmarks(it)) },
            onDismiss = {
                if (viewState.isTocVisible) intentDispatcher(ReaderIntent.ToggleToc)
                if (viewState.isBookmarksVisible) intentDispatcher(ReaderIntent.ToggleBookmarks)
            },
        )
    }

    if (viewState.isBookSearchVisible) {
        ReaderSearchSheet(
            query = viewState.bookSearchQuery,
            results = viewState.bookSearchResults,
            isLoading = viewState.isBookSearchLoading,
            failed = viewState.bookSearchFailed,
            onQueryChange = { intentDispatcher(ReaderIntent.SearchBook(it)) },
            onResultClick = { intentDispatcher(ReaderIntent.GoToSearchResult(it)) },
            onDismiss = { intentDispatcher(ReaderIntent.ToggleBookSearch) },
        )
    }

    if (viewState.isListenSheetVisible) {
        ReaderListenSheet(
            hasNarration = viewState.isReadAloud,
            hasDeviceVoice = viewState.isTtsReadAloud,
            voiceDescription = voiceDetail,
            playbackSpeed = if (viewState.isTtsReadAloud) settings.ttsRate else settings.playbackSpeed,
            onDismiss = { intentDispatcher(ReaderIntent.ToggleListenSheet) },
            onOpenVoiceSettings = {
                intentDispatcher(ReaderIntent.ToggleListenSheet)
                intentDispatcher(ReaderIntent.OpenVoiceSettings)
            },
            onStart = { source, speed ->
                intentDispatcher(ReaderIntent.SetPlaybackSpeed(speed))
                intentDispatcher(ReaderIntent.StartListening(source))
            },
        )
    }
}

@Composable
internal fun ReaderOverlayToolbar(
    bookTitle: String,
    bookAuthor: String,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(StringRes.general_back), tint = colors.ink)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = bookTitle,
                    style = Ember.type.bookTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bookAuthor.isNotBlank()) {
                    Text(
                        text = bookAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onBookmark,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark this page",
                    tint = if (isBookmarked) colors.accentText else colors.ink,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
internal fun ReaderReadingPanel(
    chapterNumber: Int,
    chapterTitle: String,
    progression: Double,
    bookProgression: Double?,
    pageInfo: String,
    remainingTime: String,
    currentPage: Int?,
    totalPages: Int?,
    isEink: Boolean,
    onSeek: (Double) -> Unit,
    onContents: () -> Unit,
    onSearch: () -> Unit,
    onListen: () -> Unit,
    onListenLongPress: () -> Unit,
    onDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier.shadow(2.dp, shape)),
        shape = shape,
        color = colors.surface,
        shadowElevation = if (isEink) 0.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$chapterNumber · $chapterTitle",
                    modifier = Modifier.weight(1f),
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${((bookProgression ?: progression) * 100).roundToInt()}%",
                    color = colors.accentText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            ChapterProgressScrubber(
                progression = progression,
                isEink = isEink,
                onSeek = onSeek,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pageInfo,
                    modifier = Modifier.weight(1f),
                    color = colors.ink2,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = remainingTime,
                    color = colors.ink2,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ReaderToolTile(
                    label = stringResource(StringRes.reader_overlay_contents),
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    onClick = onContents,
                    isEink = isEink,
                    modifier = Modifier.weight(1f),
                )
                ReaderToolTile(
                    label = stringResource(StringRes.reader_overlay_search),
                    icon = { Icon(Icons.Default.Search, null) },
                    onClick = onSearch,
                    isEink = isEink,
                    modifier = Modifier.weight(1f),
                )
                ReaderToolTile(
                    label = stringResource(StringRes.reader_overlay_listen),
                    icon = { Icon(Icons.Default.Headphones, null) },
                    onClick = onListen,
                    onLongClick = onListenLongPress,
                    isEink = isEink,
                    modifier = Modifier.weight(1f),
                )
                ReaderToolTile(
                    label = stringResource(StringRes.reader_overlay_display),
                    icon = { Text("Aa", style = Ember.type.cardTitle, color = colors.ink) },
                    onClick = onDisplay,
                    isEink = isEink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReaderToolTile(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    isEink: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(60.dp)
            .clip(shape)
            .background(if (isEink) colors.surface else colors.bg)
            .then(if (isEink) Modifier.border(1.5.dp, colors.line, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ChapterProgressScrubber(
    progression: Double,
    isEink: Boolean,
    onSeek: (Double) -> Unit,
) {
    val colors = Ember.colors
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val value = (dragProgress ?: progression.toFloat()).coerceIn(0f, 1f)
    val trackHeight = if (isEink) 10.dp else 4.dp
    val thumbRadius = 10.dp

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(widthPx) {
                detectTapGestures { offset ->
                    val next = (offset.x / widthPx).coerceIn(0f, 1f)
                    dragProgress = next
                    onSeek(next.toDouble())
                    dragProgress = null
                }
            }
            .pointerInput(widthPx) {
                detectDragGestures(
                    onDragStart = { offset -> dragProgress = (offset.x / widthPx).coerceIn(0f, 1f) },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / widthPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragProgress?.let { onSeek(it.toDouble()) }
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                )
            },
    ) {
        val centerY = size.height / 2f
        val horizontalInset = thumbRadius.toPx()
        val left = horizontalInset
        val right = (size.width - horizontalInset).coerceAtLeast(left + 1f)
        val usableWidth = right - left
        val trackPx = trackHeight.toPx()
        val trackTop = centerY - trackPx / 2f
        drawRoundRect(
            color = colors.track,
            topLeft = Offset(left, trackTop),
            size = Size(usableWidth, trackPx),
            cornerRadius = CornerRadius(trackPx / 2f),
        )
        if (isEink) {
            drawRoundRect(
                color = colors.line,
                topLeft = Offset(left, trackTop),
                size = Size(usableWidth, trackPx),
                cornerRadius = CornerRadius(trackPx / 2f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        val thumbX = left + usableWidth * value
        drawRoundRect(
            color = colors.accent,
            topLeft = Offset(left, trackTop),
            size = Size((thumbX - left).coerceAtLeast(0f), trackPx),
            cornerRadius = CornerRadius(trackPx / 2f),
        )
        for (tick in 1..4) {
            val tickX = left + usableWidth * tick / 5f
            drawLine(
                color = if (isEink) colors.ink else colors.mutedAccent,
                start = Offset(tickX, centerY - 5.dp.toPx()),
                end = Offset(tickX, centerY + 5.dp.toPx()),
                strokeWidth = if (isEink) 1.5.dp.toPx() else 1.dp.toPx(),
            )
        }
        drawCircle(colors.surface, thumbRadius.toPx() + 3.dp.toPx(), Offset(thumbX, centerY))
        drawCircle(colors.accent, thumbRadius.toPx(), Offset(thumbX, centerY))
        if (isEink) {
            drawCircle(colors.line, thumbRadius.toPx(), Offset(thumbX, centerY), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
internal fun ReaderListeningPanel(
    sourceLabel: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long?,
    playbackSpeed: Float,
    sleepTimerRemainingMs: Long?,
    isEink: Boolean,
    onVoice: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onStartSleepTimer: (Long) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onDismissSleepTimerWarning: () -> Unit,
    onPostponeSleepTimer: (Int) -> Unit,
    showSleepTimerWarning: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier.shadow(2.dp, shape)),
        shape = shape,
        color = colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.navActive),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Headphones, null, tint = colors.accentText, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(stringResource(StringRes.reader_overlay_listening), color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(sourceLabel, color = colors.ink2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OverlayPill(text = stringResource(StringRes.reader_overlay_voice), onClick = onVoice)
                IconButton(onClick = onStop, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Close, stringResource(StringRes.general_close), tint = colors.ink2)
                }
            }
            Spacer(Modifier.height(10.dp))
            AudioSeekBar(
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                onSeek = onSeek,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OverlayPill(text = "${formatSpeed(playbackSpeed)}×", onClick = {
                    val speeds = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)
                    val next = speeds.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.01f }
                    onSpeed(speeds[(next + 1).mod(speeds.size)])
                })
                IconButton(onClick = onSkipBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Replay10, "Back 10 seconds", tint = colors.ink, modifier = Modifier.size(25.dp))
                }
                IconButton(
                    onClick = onPlayPause,
                    enabled = !isLoading,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.accent),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = colors.onAccent,
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = onSkipForward, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = colors.ink, modifier = Modifier.size(25.dp))
                }
                SleepTimerPill(
                    remainingMs = sleepTimerRemainingMs,
                    currentPositionMs = currentPositionMs,
                    totalDurationMs = totalDurationMs,
                    onStart = onStartSleepTimer,
                    onCancel = onCancelSleepTimer,
                )
            }
        }
    }

    if (showSleepTimerWarning && sleepTimerRemainingMs != null) {
        SleepTimerDurationDialog(
            title = stringResource(StringRes.sleep_timer_ending_soon_title),
            message = "Playback will pause in ${formatSleepTimerLabel(sleepTimerRemainingMs)}. Choose how many more minutes to keep listening.",
            confirmLabel = stringResource(StringRes.sleep_timer_postpone),
            dismissLabel = stringResource(StringRes.sleep_timer_let_it_end),
            initialMinutes = 5,
            onConfirm = onPostponeSleepTimer,
            onDismiss = onDismissSleepTimerWarning,
        )
    }
}

@Composable
internal fun ReaderMiniPlayer(
    isPlaying: Boolean,
    progression: Float,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.track)) {
                Box(Modifier.fillMaxWidth(progression.coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().height(48.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = colors.ink,
                    )
                }
                Text(stringResource(StringRes.reader_overlay_listening), color = colors.ink2, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ReaderContentsSheet(
    tableOfContents: List<TocItemUiModel>,
    currentHref: String?,
    currentProgress: Double?,
    currentPage: Int?,
    currentChapterPages: Int?,
    bookmarks: List<BookmarkUiModel>,
    onChapterClick: (TocItemUiModel) -> Unit,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onBookmarkDelete: (String) -> Unit,
    onBookmarkRename: (String, String) -> Unit,
    onBookmarkReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(ContentsTab.CHAPTERS) }
    EmberBottomSheet(onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxSheetHeight = maxHeight * 0.88f
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxSheetHeight).navigationBarsPadding().padding(bottom = 8.dp),
            ) {
                SheetTitle(
                    title = stringResource(StringRes.reader_overlay_contents),
                    onDismiss = onDismiss,
                )
                SegmentedContentsTabs(
                    selectedTab = selectedTab,
                    bookmarkCount = bookmarks.size,
                    onSelect = { selectedTab = it },
                )
                when (selectedTab) {
                    ContentsTab.CHAPTERS -> ChaptersTab(
                        tableOfContents = tableOfContents,
                        currentHref = currentHref,
                        currentProgress = currentProgress,
                        currentPage = currentPage,
                        currentChapterPages = currentChapterPages,
                        onChapterClick = onChapterClick,
                    )

                    ContentsTab.BOOKMARKS -> BookmarksTab(
                        bookmarks = bookmarks,
                        onBookmarkClick = onBookmarkClick,
                        onDelete = onBookmarkDelete,
                        onRename = onBookmarkRename,
                        onReorder = onBookmarkReorder,
                    )
                }
            }
        }
    }
}

private enum class ContentsTab { CHAPTERS, BOOKMARKS }

@Composable
private fun SheetTitle(title: String, onDismiss: () -> Unit) {
    val colors = Ember.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = colors.ink)
        IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Close, stringResource(StringRes.general_close), tint = colors.ink)
        }
    }
}

@Composable
private fun SegmentedContentsTabs(
    selectedTab: ContentsTab,
    bookmarkCount: Int,
    onSelect: (ContentsTab) -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(shape).background(colors.bg).border(1.dp, colors.chipBorder, shape).padding(3.dp),
    ) {
        Segment(
            text = "Chapters",
            selected = selectedTab == ContentsTab.CHAPTERS,
            onClick = { onSelect(ContentsTab.CHAPTERS) },
            modifier = Modifier.weight(1f),
        )
        Segment(
            text = "Bookmarks · $bookmarkCount",
            selected = selectedTab == ContentsTab.BOOKMARKS,
            onClick = { onSelect(ContentsTab.BOOKMARKS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    Box(
        modifier.height(40.dp).clip(CircleShape)
            .background(if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) colors.onAccent else colors.ink2,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ChaptersTab(
    tableOfContents: List<TocItemUiModel>,
    currentHref: String?,
    currentProgress: Double?,
    currentPage: Int?,
    currentChapterPages: Int?,
    onChapterClick: (TocItemUiModel) -> Unit,
) {
    val colors = Ember.colors
    var query by remember { mutableStateOf("") }
    val filtered = remember(tableOfContents, query) {
        if (query.isBlank()) tableOfContents
        else tableOfContents.filter { it.title.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val currentIndex = tableOfContents.indexOfFirst { it.href == currentHref }
    val visibleCurrentIndex = filtered.indexOfFirst { it.href == currentHref }
    LaunchedEffect(currentHref, visibleCurrentIndex, query) {
        if (query.isBlank() && visibleCurrentIndex >= 0) listState.scrollToItem(visibleCurrentIndex)
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            placeholder = { Text(stringResource(StringRes.reader_toc_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(14.dp),
        )
        if (tableOfContents.isEmpty()) {
            Text(stringResource(StringRes.reader_toc_no_chapters), color = colors.ink2, modifier = Modifier.padding(24.dp))
        } else if (filtered.isEmpty()) {
            Text(stringResource(StringRes.reader_toc_no_results), color = colors.ink2, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            ) {
                itemsIndexed(filtered, key = { _, item -> item.href }) { _, chapter ->
                    val index = tableOfContents.indexOfFirst { it.href == chapter.href }
                    val isCurrent = chapter.href == currentHref
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
                            .clip(shape)
                            .background(if (isCurrent && !Ember.style.isEink) colors.navActive.copy(alpha = 0.62f) else colors.surface)
                            .then(if (isCurrent && Ember.style.isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
                            .clickable { onChapterClick(chapter) }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(42.dp),
                            color = if (isCurrent) colors.accentText else colors.ink2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Column(Modifier.weight(1f).padding(start = (chapter.level * 10).dp)) {
                            Text(
                                chapter.title,
                                color = colors.ink,
                                fontSize = 16.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrent) {
                                val percent = ((currentProgress ?: 0.0) * 100).roundToInt()
                                Text(
                                    stringResource(StringRes.reader_overlay_current_chapter, percent),
                                    color = colors.accentText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        val pageCount = if (isCurrent && currentChapterPages != null) {
                            stringResource(StringRes.reader_overlay_chapter_page_count, currentChapterPages)
                        } else {
                            "— p"
                        }
                        Text(pageCount, color = colors.ink2, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksTab(
    bookmarks: List<BookmarkUiModel>,
    onBookmarkClick: (BookmarkUiModel) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<BookmarkUiModel?>(null) }
    var localBookmarks by remember { mutableStateOf(bookmarks) }
    var dragInProgress by remember { mutableStateOf(false) }
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(bookmarks, pendingOrder) {
        if (pendingOrder != null && bookmarks.map { it.id } == pendingOrder) {
            pendingOrder = null
            dragInProgress = false
        }
    }
    if (!dragInProgress && pendingOrder == null && localBookmarks != bookmarks) localBookmarks = bookmarks

    renameTarget?.let { bookmark ->
        BookmarkRenamePrompt(
            bookmark = bookmark,
            onConfirm = { newTitle -> onRename(bookmark.id, newTitle); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    if (localBookmarks.isEmpty()) {
        Text(
            stringResource(StringRes.reader_bookmarks_empty_overlay),
            color = Ember.colors.ink2,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp),
        )
        return
    }

    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localBookmarks = localBookmarks.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
    ) {
        itemsIndexed(localBookmarks, key = { _, bookmark -> bookmark.id }) { _, bookmark ->
            ReorderableItem(reorderableState, key = bookmark.id) {
                BookmarkOverlayRow(
                    bookmark = bookmark,
                    onClick = { onBookmarkClick(bookmark) },
                    onDelete = { onDelete(bookmark.id) },
                    onRename = { renameTarget = bookmark },
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = { dragInProgress = true },
                        onDragStopped = {
                            val order = localBookmarks.map { it.id }
                            pendingOrder = order
                            onReorder(order)
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun BookmarkOverlayRow(
    bookmark: BookmarkUiModel,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val colors = Ember.colors
    val createdTime = remember(bookmark.createdAt) { relativeTimeFromIso(bookmark.createdAt, nowMillis()) }
    val timeAgo = when (val time = createdTime) {
        RelativeTime.JustNow -> stringResource(StringRes.reader_bookmark_just_now)
        is RelativeTime.MinutesAgo -> stringResource(StringRes.reader_bookmark_minutes_ago, time.minutes)
        is RelativeTime.HoursAgo -> stringResource(StringRes.reader_bookmark_hours_ago, time.hours)
        is RelativeTime.DaysAgo -> stringResource(StringRes.reader_bookmark_days_ago, time.days)
    }
    val chapter = (bookmark.chapterIndex ?: 0) + 1
    val page = bookmark.position ?: 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Bookmark, null, tint = colors.accentText, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(
                    stringResource(StringRes.reader_overlay_bookmark_location, chapter, page),
                    color = colors.ink2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    bookmark.locatorTitle ?: "Saved page",
                    color = colors.ink,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Ember.type.bookTitle.fontFamily),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(timeAgo, color = colors.ink2, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Edit, stringResource(StringRes.reader_bookmark_rename), tint = colors.ink2, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, stringResource(StringRes.action_delete), tint = colors.ink2, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = dragHandleModifier.size(32.dp)) {
                Text("⋮", color = colors.ink2, fontSize = 24.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun BookmarkRenamePrompt(
    bookmark: BookmarkUiModel,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.locatorTitle.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(StringRes.reader_bookmark_rename)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(StringRes.reader_bookmark_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(title) }) { Text(stringResource(StringRes.reader_bookmark_rename_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(StringRes.reader_bookmark_rename_cancel)) } },
    )
}

@Composable
internal fun ReaderListenSheet(
    hasNarration: Boolean,
    hasDeviceVoice: Boolean,
    voiceDescription: String,
    playbackSpeed: Float,
    onDismiss: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onStart: (ListenSource, Float) -> Unit,
) {
    var source by remember(hasNarration, hasDeviceVoice) {
        mutableStateOf(if (hasNarration) ListenSource.NARRATION else ListenSource.DEVICE_VOICE)
    }
    var speed by remember(playbackSpeed) { mutableFloatStateOf(playbackSpeed) }
    EmberBottomSheet(onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.9f)
                    .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                SheetTitle(stringResource(StringRes.reader_overlay_listen), onDismiss)
                ListenSourceCard(
                    title = stringResource(StringRes.reader_overlay_narration),
                    description = if (hasNarration) stringResource(StringRes.reader_overlay_narration_description)
                    else stringResource(StringRes.reader_overlay_narration_unavailable),
                    selected = source == ListenSource.NARRATION,
                    enabled = hasNarration,
                    onClick = { source = ListenSource.NARRATION },
                )
                Spacer(Modifier.height(8.dp))
                ListenSourceCard(
                    title = stringResource(StringRes.reader_overlay_device_voice),
                    description = stringResource(StringRes.reader_overlay_device_voice_description),
                    selected = source == ListenSource.DEVICE_VOICE,
                    enabled = hasDeviceVoice,
                    onClick = { source = ListenSource.DEVICE_VOICE },
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ember.colors.bg)
                        .clickable(onClick = onOpenVoiceSettings).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(StringRes.reader_overlay_voice), color = Ember.colors.ink, fontWeight = FontWeight.Bold)
                        Text(voiceDescription, color = Ember.colors.ink2, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("›", color = Ember.colors.ink2, fontSize = 28.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text("SPEED", color = Ember.colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clip(CircleShape).background(Ember.colors.bg)
                        .border(1.dp, Ember.colors.chipBorder, CircleShape).padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(0.8f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        val selected = kotlin.math.abs(speed - value) < 0.01f
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(CircleShape)
                                .background(if (selected) Ember.colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { speed = value },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${formatSpeed(value)}×",
                                color = if (selected) Ember.colors.onAccent else Ember.colors.ink2,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onStart(source, speed) },
                    enabled = when (source) {
                        ListenSource.NARRATION -> hasNarration
                        ListenSource.DEVICE_VOICE -> hasDeviceVoice
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(StringRes.reader_overlay_start_listening), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ListenSourceCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) colors.navActive.copy(alpha = 0.52f) else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, color = if (enabled) colors.ink else colors.ink2, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, color = colors.ink2, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ReaderSearchSheet(
    query: String,
    results: List<ReaderSearchResult>,
    isLoading: Boolean,
    failed: Boolean,
    onQueryChange: (String) -> Unit,
    onResultClick: (ReaderSearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    EmberBottomSheet(onDismiss = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.86f).navigationBarsPadding().padding(bottom = 8.dp),
            ) {
                SheetTitle(stringResource(StringRes.reader_search_title), onDismiss)
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    placeholder = { Text(stringResource(StringRes.reader_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                when {
                    failed -> Text(stringResource(StringRes.reader_search_failed), color = Ember.colors.ink2, modifier = Modifier.padding(24.dp))
                    isLoading -> Text("Searching…", color = Ember.colors.ink2, modifier = Modifier.padding(24.dp))
                    query.isBlank() -> Text(stringResource(StringRes.reader_search_empty), color = Ember.colors.ink2, modifier = Modifier.padding(24.dp))
                    results.isEmpty() -> Text(stringResource(StringRes.reader_search_no_results), color = Ember.colors.ink2, modifier = Modifier.padding(24.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
                        itemsIndexed(results) { index, result ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onResultClick(result) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                            ) {
                                Text(result.title ?: "Result ${index + 1}", color = Ember.colors.ink, fontWeight = FontWeight.Bold)
                                Text(result.snippet, color = Ember.colors.ink2, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(Ember.colors.line))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSeekBar(
    currentPositionMs: Long,
    totalDurationMs: Long?,
    onSeek: (Long) -> Unit,
) {
    val duration = totalDurationMs ?: 0L
    val progress = if (duration > 0L) (currentPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = { value -> if (duration > 0L) onSeek((value * duration).toLong()) },
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatAudioTime(currentPositionMs), color = Ember.colors.ink2, style = MaterialTheme.typography.bodySmall)
            Text("−${formatAudioTime((duration - currentPositionMs).coerceAtLeast(0L))} in chapter", color = Ember.colors.ink2, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OverlayPill(text: String, onClick: () -> Unit) {
    val colors = Ember.colors
    val shape = CircleShape
    Box(
        Modifier.clip(shape).background(colors.surface).border(1.dp, colors.chipBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.accentText, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SleepTimerPill(
    remainingMs: Long?,
    currentPositionMs: Long,
    totalDurationMs: Long?,
    onStart: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val remainingToEndMs = totalDurationMs?.minus(currentPositionMs)?.takeIf { it > 0L }
    Box {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Ember.colors.bg).border(1.dp, Ember.colors.chipBorder, CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("☾", color = Ember.colors.ink, fontSize = 24.sp)
        }
        androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf(5, 10, 15, 30, 60).forEach { minutes ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("$minutes minutes") },
                    onClick = { onStart(minutes * 60_000L); expanded = false },
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(StringRes.sleep_timer_custom)) },
                onClick = { expanded = false; showCustomDialog = true },
            )
            if (remainingToEndMs != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(StringRes.sleep_timer_end_of_audio)) },
                    onClick = { onStart(remainingToEndMs); expanded = false },
                )
            }
            if (remainingMs != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(StringRes.sleep_timer_cancel)) },
                    onClick = { onCancel(); expanded = false },
                )
            }
        }
    }
    if (showCustomDialog) {
        SleepTimerDurationDialog(
            title = "Custom sleep timer",
            message = "Choose how long playback should continue before pausing.",
            confirmLabel = "Start",
            dismissLabel = stringResource(StringRes.general_close),
            initialMinutes = 5,
            onConfirm = { onStart(it * 60_000L); showCustomDialog = false },
            onDismiss = { showCustomDialog = false },
        )
    }
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

private fun formatAudioTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}
