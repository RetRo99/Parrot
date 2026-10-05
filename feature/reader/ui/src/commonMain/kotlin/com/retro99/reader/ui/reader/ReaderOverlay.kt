package com.retro99.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.reader.domain.model.NavigationAction
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.reader.saved.PageRibbon
import resources.translations.saved_bookmark_this_page
import resources.translations.saved_remove_bookmark
import com.retro99.reader.ui.reader.saved.ReaderSavedHost
import com.retro99.reader.ui.reader.saved.SavedAction
import com.retro99.reader.ui.reader.saved.SavedBarHost
import com.retro99.reader.ui.reader.saved.SelectionToolbar
import androidx.compose.ui.graphics.luminance
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.RelativeTime
import com.retro99.reader.ui.model.TocItemUiModel
import com.retro99.reader.ui.model.relativeTimeFromIso
import com.retro99.reader.ui.model.backgroundColor
import com.retro99.reader.ui.model.isDarkPage
import com.retro99.reader.ui.publication.PublicationState
import com.retro99.reader.ui.di.koinReaderScopeInject
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.SavedPageScript
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
import resources.translations.reader_audio_sentence_of
import resources.translations.reader_overlay_audio
import resources.translations.reader_overlay_open_player
import resources.translations.reader_overlay_play
import resources.translations.reader_overlay_pause
import resources.translations.reader_overlay_narration_progress
import resources.translations.reader_overlay_device_voice_progress
import resources.translations.reader_overlay_now_playing_narration
import resources.translations.reader_overlay_now_playing_device_voice
import resources.translations.reader_overlay_mini_narration
import resources.translations.reader_overlay_mini_device_voice
import resources.translations.reader_overlay_contents
import resources.translations.reader_overlay_device_voice
import resources.translations.reader_overlay_device_voice_description
import resources.translations.reader_overlay_display
import resources.translations.reader_overlay_listen
import resources.translations.reader_overlay_listening
import resources.translations.reader_overlay_narration
import resources.translations.reader_overlay_narration_description
import resources.translations.reader_overlay_narration_unavailable
import resources.translations.reader_overlay_search
import resources.translations.reader_overlay_start_listening
import resources.translations.reader_overlay_voice
import resources.translations.reader_page_of_pages
import resources.translations.reader_search_empty
import resources.translations.reader_search_failed
import resources.translations.reader_search_hint
import resources.translations.reader_search_no_results
import resources.translations.reader_search_result_count
import resources.translations.reader_search_title
import resources.translations.reader_audio_back_10
import resources.translations.reader_audio_forward_10
import resources.translations.reader_audio_next_sentence
import resources.translations.reader_audio_previous_sentence
import resources.translations.reader_toc_next_chapter
import resources.translations.reader_toc_no_chapters
import resources.translations.reader_toc_no_results
import resources.translations.reader_toc_previous_chapter
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
import androidx.compose.ui.graphics.toArgb

private const val SWIPE_HIDE_THRESHOLD_PX = 80f
private val MINI_PLAYER_HEIGHT = 60.dp

@Composable
internal fun ReaderOverlayContent(
    bookUuid: String,
    viewState: ReaderViewState,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    loader: @Composable (() -> Unit),
) {
    val publicationState = viewState.publicationState ?: return
    val bookController = koinReaderScopeInject<BookController>(bookUuid)
    val settings = publicationState.settings
    val currentPosition = publicationState.position
    var controlsVisible by remember(bookUuid) { mutableStateOf(true) }
    var lastInteractionTime by remember(bookUuid) { mutableStateOf(0L) }
    var isZooming by remember { mutableStateOf(false) }
    var temporaryFontScale by remember(settings.fontSize) { mutableFloatStateOf(settings.fontSize.toFloat()) }
    var readerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val fontUndoState = remember(bookUuid) { androidx.compose.material3.SnackbarHostState() }
    val snackbarMessage = stringResource(StringRes.settings_changed)
    val undoLabel = stringResource(StringRes.settings_undo)
    val openSheet = viewState.isContentsVisible ||
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
    val saved = viewState.saved
    val pageBookmark = saved.pageBookmark
    val onSaved: (SavedAction) -> Unit = { action -> intentDispatcher(ReaderIntent.Saved(action)) }
    // One resolver feeds the header, the sheet and the progress strip, so they always agree.
    val currentLocation = remember(
        viewState.tableOfContents,
        currentPosition?.href,
        currentPosition?.progression,
        viewState.bookSearchReadingOrder,
    ) {
        findTocLocation(
            viewState.tableOfContents,
            currentPosition?.href,
            currentPosition?.progression,
            viewState.bookSearchReadingOrder,
        )
    }
    val chapterTitle = currentLocation?.item?.title ?: currentPosition?.title.orEmpty()
    val selectedVoice = viewState.ttsVoices.firstOrNull { it.id == viewState.selectedTtsVoiceId }
    val voiceDetail = selectedVoice?.let { "${it.locale} · ${it.name.substringBefore('(').trim()}" }
        ?: "System voice"
    val isEink = Ember.style.isEink
    val searchActive = viewState.selectedSearchIndex != null
    LaunchedEffect(controlsVisible, searchActive, viewState.isReadAloud, viewState.isTtsReadAloud, viewState.isListenSheetVisible) {
        if (controlsVisible && !searchActive) {
            intentDispatcher(ReaderIntent.FeatureVisible(com.retro99.analytics.api.UsageFeature.Bookmarks))
            if (viewState.isReadAloud) {
                intentDispatcher(ReaderIntent.FeatureVisible(com.retro99.analytics.api.UsageFeature.ReadAloud))
            }
            if (!viewState.isReadAloud) {
                intentDispatcher(ReaderIntent.FeatureVisible(com.retro99.analytics.api.UsageFeature.Tts, viewState.isTtsReadAloud))
            }
        }
        if (viewState.isListenSheetVisible) {
            intentDispatcher(ReaderIntent.FeatureVisible(com.retro99.analytics.api.UsageFeature.SleepTimer))
            if (viewState.isTtsReadAloud) {
                intentDispatcher(ReaderIntent.FeatureVisible(com.retro99.analytics.api.UsageFeature.Tts))
            }
        }
    }
    val searchAccent = Ember.colors.accent.toArgb()
    val searchSoft = Ember.colors.navActive.toArgb()
    val searchOnAccent = Ember.colors.onAccent.toArgb()
    LaunchedEffect(searchActive, searchAccent, searchSoft, searchOnAccent, isEink) {
        if (searchActive) {
            controlsVisible = false
            intentDispatcher(ReaderIntent.UpdateSearchDecorations(searchAccent, searchSoft, searchOnAccent, isEink))
        }
    }
    val nowPlaying = if (viewState.isListening) {
        val isNarration = viewState.listenSource == ListenSource.NARRATION
        val stateText = stringResource(
            if (viewState.isPlaying) StringRes.reader_overlay_pause else StringRes.reader_overlay_play,
        )
        val voiceName = selectedVoice?.name?.substringBefore('(')?.trim()
            ?: stringResource(StringRes.reader_overlay_voice)
        val sentenceNumber = (viewState.ttsSentenceIndex + 1).coerceAtLeast(1)
        val speed = formatSpeed(if (isNarration) settings.playbackSpeed else settings.ttsRate)
        val positionText = formatAudioTime(viewState.currentAudioPositionMs)
        val totalText = formatAudioTime(viewState.totalDurationMs ?: 0L)
        val subtitle = if (isNarration) {
            stringResource(StringRes.reader_overlay_narration_progress, positionText, totalText, speed)
        } else {
            stringResource(
                StringRes.reader_overlay_device_voice_progress,
                voiceName,
                sentenceNumber,
                viewState.ttsSentenceCount,
            )
        }
        val progress = if (isNarration) {
            val total = viewState.totalDurationMs ?: 0L
            if (total > 0L) viewState.currentAudioPositionMs.toFloat() / total else 0f
        } else if (viewState.ttsSentenceCount > 0) {
            sentenceNumber.toFloat() / viewState.ttsSentenceCount
        } else {
            0f
        }
        NowPlayingUi(
            isNarration = isNarration,
            title = chapterTitle,
            subtitle = subtitle,
            stripLabel = if (isNarration) {
                "$positionText / $totalText"
            } else {
                stringResource(StringRes.reader_audio_sentence_of, sentenceNumber, viewState.ttsSentenceCount)
            },
            miniLabel = if (isNarration) {
                stringResource(StringRes.reader_overlay_mini_narration, positionText, totalText)
            } else {
                stringResource(StringRes.reader_overlay_mini_device_voice, voiceName)
            },
            progress = progress,
            isPlaying = viewState.isPlaying,
            isLoading = viewState.isNarrationLoading,
            description = stringResource(
                if (isNarration) StringRes.reader_overlay_now_playing_narration
                else StringRes.reader_overlay_now_playing_device_voice,
                chapterTitle,
                "$subtitle, $stateText",
            ),
        )
    } else {
        null
    }
    val openAudioSheet = { intentDispatcher(ReaderIntent.ToggleListenSheet) }
    val sheetMiniPlayer: (@Composable () -> Unit)? = nowPlaying?.let { playing ->
        {
            ReaderMiniPlayer(
                nowPlaying = playing,
                onOpen = {
                    // Swap the open sheet for the audio sheet; playback is untouched either way.
                    if (viewState.isContentsVisible) intentDispatcher(ReaderIntent.ToggleToc)
                    if (viewState.isBookSearchVisible) intentDispatcher(ReaderIntent.ToggleBookSearch)
                    openAudioSheet()
                },
                onPlayPause = { intentDispatcher(ReaderIntent.TogglePlayback) },
            )
        }
    }

    val showStrip = settings.showProgressBar == true
    val hasTopStrip = showStrip && settings.progressBarPosition == ProgressBarPosition.TOP
    val hasBottomStrip = showStrip && settings.progressBarPosition == ProgressBarPosition.BOTTOM
    val hasBottomBar = viewState.isListening || hasBottomStrip
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }
    var bottomControlsTopPx by remember { mutableStateOf<Float?>(null) }
    var readerBottomPx by remember { mutableStateOf<Float?>(null) }
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
            Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInset)
                .onSizeChanged { readerSize = it }
                .onGloballyPositioned { readerBottomPx = it.boundsInRoot().bottom },
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
                        onContentTap = {
                            val id = SavedPageScript.parseSavedTap(
                                bookController.runPageScript(SavedPageScript.takeSavedTap()),
                            )
                            if (id != null) onSaved(SavedAction.OpenDetail(id))
                            id != null
                        },
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
                             if (searchActive) intentDispatcher(ReaderIntent.ToggleFindBar)
                             else {
                                 controlsVisible = !controlsVisible
                                 if (controlsVisible) lastInteractionTime = nowMillis()
                             }
                        },
                    ),
            )

            if (viewState.isReadAloud && !viewState.isAudioPlayerReady) loader()

            if (isZooming) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    color = Ember.colors.surface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "${(temporaryFontScale * 100).roundToInt()}%",
                        style = Ember.type.meta.copy(fontSize = 32.sp, lineHeight = 40.sp),
                        color = Ember.colors.ink,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            BackToOriginPill(
                origin = viewState.jumpOrigin,
                onClick = { intentDispatcher(ReaderIntent.ReturnToJumpOrigin) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (pageBookmark != null && !controlsVisible && !searchActive) {
                PageRibbon(
                    onClick = { onSaved(SavedAction.OpenDetail(pageBookmark.id)) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(
                        end = 24.dp,
                        top = settings.marginVertical.dp,
                    ),
                )
            }
            saved.selection?.let { selection ->
                SelectionToolbar(
                    selection = selection,
                    pageTopDp = settings.marginVertical.toFloat(),
                    bottomObstructionDp = with(density) {
                        ((readerBottomPx ?: 0f) - (bottomControlsTopPx ?: readerBottomPx ?: 0f))
                            .coerceAtLeast(0f).toDp().value
                    },
                    topObstructionDp = if (controlsVisible && !searchActive) 65f else 0f,
                    isDarkPage = settings.theme.isDarkPage,
                    onSaved = onSaved,
                    modifier = Modifier.matchParentSize(),
                )
            }
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
                    chapterTitle = chapterTitle,
                    chapterReadingTimeInfo = viewState.chapterReadingTimeInfo,
                    chapterInfo = viewState.chapterInfo,
                    currentTime = progressBarTime,
                    audioStatus = nowPlaying?.stripLabel,
                    bookmarkTicks = saved.bookmarkTicks,
                )
            }
        }
        AnimatedVisibility(
            visible = controlsVisible && !searchActive,
            enter = if (isEink) EnterTransition.None else fadeIn() + slideInVertically { -it },
            exit = if (isEink) ExitTransition.None else fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
        ) {
            ReaderOverlayToolbar(
                recap = { compact -> com.retro99.reader.ui.recap.ReaderRecapPill(bookUuid, compact = compact) },
                bookTitle = viewState.bookTitle,
                bookAuthor = viewState.bookAuthor,
                isBookmarked = pageBookmark != null,
                onBack = { intentDispatcher(ReaderIntent.Close) },
                onBookmark = {
                    onInteraction()
                    onSaved(SavedAction.ToggleBookmark)
                },
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
            ReaderMessageHost(viewState, intentDispatcher, fontUndoState)
            Column(Modifier.fillMaxWidth().onGloballyPositioned { bottomControlsTopPx = it.boundsInRoot().top }) {
            if (searchActive && viewState.isFindBarVisible && !viewState.isBookSearchVisible) {
                ReaderFindBar(viewState, { intentDispatcher(it) },
                    Modifier.then(if (!hasBottomBar) Modifier.navigationBarsPadding() else Modifier))
            }
            AnimatedVisibility(
                visible = controlsVisible && !searchActive,
                enter = if (isEink) EnterTransition.None else expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = if (isEink) ExitTransition.None else shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
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
                ReaderReadingPanel(
                    nowPlaying = nowPlaying,
                    isEink = isEink,
                    onContents = { intentDispatcher(ReaderIntent.ToggleToc) },
                    onSearch = { intentDispatcher(ReaderIntent.ToggleBookSearch) },
                    onListen = {
                        val source = viewState.listenSource
                        val canStartListening = when (source) {
                            ListenSource.NARRATION -> viewState.isReadAloud
                            ListenSource.DEVICE_VOICE -> viewState.isTtsReadAloud
                        }
                        when {
                            // Card showing but nothing playing: dismiss it, as if never tapped.
                            nowPlaying != null && !viewState.isPlaying ->
                                intentDispatcher(ReaderIntent.StopListening)
                            // Audio is playing: open the full sheet.
                            nowPlaying != null -> openAudioSheet()
                            // Show the compact now-playing card without starting audio; tapping it opens the sheet.
                            canStartListening ->
                                intentDispatcher(ReaderIntent.StartListening(source, autoPlay = false))
                            else -> openAudioSheet()
                        }
                    },
                    onListenLongPress = openAudioSheet,
                    onDisplay = { intentDispatcher(ReaderIntent.OnSettingsClicked) },
                    onPlayPause = { intentDispatcher(ReaderIntent.TogglePlayback) },
                    onPreviousChapter = { intentDispatcher(ReaderIntent.GoToPreviousChapter) },
                    onNextChapter = { intentDispatcher(ReaderIntent.GoToNextChapter) },
                    onSkipBack = { intentDispatcher(ReaderIntent.SkipBackward()) },
                    onSkipForward = { intentDispatcher(ReaderIntent.SkipForward()) },
                )
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
                        if (nowPlaying != null) {
                            // The now-playing card replaces the mini player while the controls are shown.
                            ReaderMiniPlayer(
                                nowPlaying = nowPlaying,
                                onOpen = openAudioSheet,
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
                                    chapterTitle = chapterTitle,
                                    chapterReadingTimeInfo = viewState.chapterReadingTimeInfo,
                                    chapterInfo = viewState.chapterInfo,
                                    currentTime = progressBarTime,
                                    audioStatus = nowPlaying?.stripLabel,
                                    bookmarkTicks = saved.bookmarkTicks,
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (viewState.showSleepTimerWarningPrompt && viewState.sleepTimerRemainingMs != null) {
        SleepTimerDurationDialog(
            title = stringResource(StringRes.sleep_timer_ending_soon_title),
            message = "Playback will pause in ${formatSleepTimerLabel(viewState.sleepTimerRemainingMs)}. " +
                "Choose how many more minutes to keep listening.",
            confirmLabel = stringResource(StringRes.sleep_timer_postpone),
            dismissLabel = stringResource(StringRes.sleep_timer_let_it_end),
            initialMinutes = 5,
            onConfirm = { minutes -> intentDispatcher(ReaderIntent.StartSleepTimer(minutes * 60_000L)) },
            onDismiss = { intentDispatcher(ReaderIntent.DismissSleepTimerWarning) },
        )
    }

    if (viewState.isContentsVisible) {
        ReaderContentsSheet(
            state = viewState,
            onChapterClick = { href ->
                intentDispatcher(ReaderIntent.GoToChapter(href, currentPosition))
            },
            onSaved = onSaved,
            onToggleGroup = { intentDispatcher(ReaderIntent.ToggleContentsGroup(it)) },
            onDismiss = { intentDispatcher(ReaderIntent.ToggleToc) },
            footer = sheetMiniPlayer,
        )
    }

    ReaderSavedHost(
        saved = saved,
        onSaved = onSaved,
        isDarkPage = settings.theme.isDarkPage,
        marginHorizontalDp = settings.marginHorizontal,
        scroll = settings.scrollMode == true,
    )

    if (viewState.isBookSearchVisible) {
        ReaderSearchSheet(
            state = viewState,
            dispatch = { intentDispatcher(it) },
            footer = sheetMiniPlayer,
        )
    }

    if (viewState.isListenSheetVisible) {
        val isNarration = viewState.listenSource == ListenSource.NARRATION
        ReaderAudioSheet(
            ui = AudioSheetUi(
                isNarration = isNarration,
                chapterLabel = chapterTitle,
                isPlaying = viewState.isPlaying,
                isLoading = viewState.isNarrationLoading,
                positionMs = viewState.currentAudioPositionMs,
                totalMs = viewState.totalDurationMs,
                sentenceNumber = (viewState.ttsSentenceIndex + 1).coerceAtLeast(1),
                sentenceCount = viewState.ttsSentenceCount,
                speed = settings.playbackSpeed,
                rate = settings.ttsRate,
                pitch = settings.ttsPitch,
                voice = selectedVoice,
                isVoicePreparing = viewState.isTtsVoicePreparing,
                preparationProgress = viewState.ttsVoicePreparationProgress,
                voiceDownloadFailed = viewState.failedTtsVoicePackage != null,
                sleepRemainingMs = viewState.sleepTimerRemainingMs,
                isAudioOnly = viewState.isAudioOnlyMode,
                isEink = isEink,
            ),
            hasNarration = viewState.isReadAloud,
            canSwitchSource = viewState.canSwitchListenSource,
            actions = AudioSheetActions(
                onDismiss = { intentDispatcher(ReaderIntent.ToggleListenSheet) },
                onStop = {
                    intentDispatcher(ReaderIntent.StopListening)
                    intentDispatcher(ReaderIntent.ToggleListenSheet)
                },
                onPlayPause = {
                    if (viewState.isListening) {
                        intentDispatcher(ReaderIntent.TogglePlayback)
                    } else {
                        intentDispatcher(ReaderIntent.StartListening(viewState.listenSource))
                    }
                },
                onSeek = { position -> intentDispatcher(ReaderIntent.SeekTo(position)) },
                onSkipBack = { intentDispatcher(ReaderIntent.SkipBackward()) },
                onSkipForward = { intentDispatcher(ReaderIntent.SkipForward()) },
                onPreviousChapter = { intentDispatcher(ReaderIntent.GoToPreviousChapter) },
                onNextChapter = { intentDispatcher(ReaderIntent.GoToNextChapter) },
                onSpeed = { speed -> intentDispatcher(ReaderIntent.SetPlaybackSpeed(speed)) },
                onRate = { rate -> intentDispatcher(ReaderIntent.SetTtsRate(rate)) },
                onPitch = { pitch -> intentDispatcher(ReaderIntent.SetTtsPitch(pitch)) },
                onChangeVoice = {
                    intentDispatcher(ReaderIntent.ToggleListenSheet)
                    intentDispatcher(ReaderIntent.OpenVoiceSettings)
                },
                onStartSleepTimer = { durationMs -> intentDispatcher(ReaderIntent.StartSleepTimer(durationMs)) },
                onCancelSleepTimer = { intentDispatcher(ReaderIntent.CancelSleepTimer) },
                onAudioOnly = { intentDispatcher(ReaderIntent.ToggleAudioOnlyMode) },
                onBookmark = { onSaved(SavedAction.BookmarkSentence) },
                onSelectSource = { narration ->
                    intentDispatcher(
                        ReaderIntent.SwitchListenSource(
                            if (narration) ListenSource.NARRATION else ListenSource.DEVICE_VOICE,
                        ),
                    )
                },
            ),
        )
    }
    if (viewState.isVoiceSettingsVisible) {
        VoicesSheet(
            voices = viewState.ttsVoices,
            selectedVoiceId = viewState.selectedTtsVoiceId,
            pendingVoiceId = viewState.pendingTtsVoiceId,
            bookLanguage = viewState.bookLanguage,
            preparingVoicePackage = viewState.preparingTtsVoicePackage,
            preparationProgress = viewState.ttsVoicePreparationProgress,
            failedVoicePackage = viewState.failedTtsVoicePackage,
            deletingVoicePackage = viewState.deletingTtsVoicePackage,
            hasAcceptedSupertonicTerms = viewState.hasAcceptedSupertonicTerms,
            previewingVoiceKey = viewState.ttsPreviewingVoiceId,
            isPreviewPlaying = viewState.isTtsPreviewPlaying,
            isEink = isEink,
            onVoiceSelected = { voiceId -> intentDispatcher(ReaderIntent.SelectTtsVoice(voiceId)) },
            onDownloadPackage = { pack -> intentDispatcher(ReaderIntent.DownloadNeuralVoicePackage(pack)) },
            onUpdatePackage = { pack -> intentDispatcher(ReaderIntent.UpdateNeuralVoicePackage(pack)) },
            onDeletePackage = { pack -> intentDispatcher(ReaderIntent.DeleteNeuralVoicePackage(pack)) },
            onRetryPackage = { pack -> intentDispatcher(ReaderIntent.RetryTtsVoicePreparation(pack)) },
            onCancelPreparation = { intentDispatcher(ReaderIntent.CancelTtsVoicePreparation) },
            onAcceptTermsAndDownload = {
                intentDispatcher(ReaderIntent.AcceptSupertonicTermsAndDownload)
            },
            onAcceptTermsAndSelect = { voiceId ->
                intentDispatcher(ReaderIntent.AcceptSupertonicTermsAndSelect(voiceId))
            },
            onPreviewVoice = { voiceId, text -> intentDispatcher(ReaderIntent.PreviewTtsVoice(voiceId, text)) },
            onStopPreview = { intentDispatcher(ReaderIntent.StopTtsPreview) },
            onClose = {
                intentDispatcher(ReaderIntent.CloseVoiceSettings)
                // Came from the audio sheet while listening: return to it.
                if (viewState.isListening) intentDispatcher(ReaderIntent.ToggleListenSheet)
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
    recap: @Composable (compact: Boolean) -> Unit = {},
) {
    val colors = Ember.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Below 360dp the pill drops its label so the title keeps its column.
            val compact = maxWidth < 360.dp
            EmberTopBar(
                title = bookTitle,
                subtitle = bookAuthor.ifBlank { null },
                titleStyle = Ember.type.bookTitle,
                containerColor = colors.surface,
                applyStatusBarInset = false,
                horizontalPadding = 4.dp,
                titleStartPadding = 4.dp,
                onBack = onBack,
                actions = {
                    recap(compact)
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onBookmark,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = stringResource(
                                if (isBookmarked) StringRes.saved_remove_bookmark else StringRes.saved_bookmark_this_page,
                            ),
                            tint = if (isBookmarked) colors.accentText else colors.ink,
                        )
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

/** Everything the now-playing card and mini-player need, resolved to display strings. */
internal data class NowPlayingUi(
    val isNarration: Boolean,
    val title: String,
    val subtitle: String,
    val miniLabel: String,
    val stripLabel: String,
    val progress: Float,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val description: String,
)

@Composable
internal fun ReaderReadingPanel(
    nowPlaying: NowPlayingUi?,
    isEink: Boolean,
    onContents: () -> Unit,
    onSearch: () -> Unit,
    onListen: () -> Unit,
    onListenLongPress: () -> Unit,
    onDisplay: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
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
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (nowPlaying != null) {
                NowPlayingCard(
                    nowPlaying = nowPlaying,
                    isEink = isEink,
                    // Tapping the card always opens the sheet, unlike the Audio tile which toggles.
                    onOpen = onListenLongPress,
                    onPlayPause = onPlayPause,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                )
                Spacer(Modifier.height(12.dp))
            }
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
                    label = stringResource(
                        if (nowPlaying != null) StringRes.reader_overlay_audio else StringRes.reader_overlay_listen,
                    ),
                    icon = {
                        Icon(
                            if (nowPlaying?.isNarration == false) Icons.Default.GraphicEq else Icons.Default.Headphones,
                            null,
                        )
                    },
                    onClick = onListen,
                    onLongClick = onListenLongPress,
                    isActive = nowPlaying != null,
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

/** Thin read-only progress line (3dp; outlined on e-ink). */
@Composable
private fun ProgressLine(progress: Float, isEink: Boolean, modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(shape)
            .background(colors.track)
            .then(if (isEink) Modifier.border(1.dp, colors.line, shape) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
    }
}

@Composable
private fun NowPlayingCard(
    nowPlaying: NowPlayingUi,
    isEink: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(18.dp)
    val playLabel = stringResource(
        if (nowPlaying.isPlaying) StringRes.reader_overlay_pause else StringRes.reader_overlay_play,
    )
    val openLabel = stringResource(StringRes.reader_overlay_open_player)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isEink) colors.surface else colors.navActive.copy(alpha = 0.5f))
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = nowPlaying.description
                    customActions = listOf(
                        CustomAccessibilityAction(playLabel) { onPlayPause(); true },
                        CustomAccessibilityAction(openLabel) { onOpen(); true },
                    )
                }
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isEink) colors.ink else colors.navActive),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (nowPlaying.isNarration) Icons.Default.Headphones else Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (isEink) colors.surface else colors.accentText,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    nowPlaying.title,
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nowPlaying.subtitle,
                    color = colors.ink2,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = colors.ink2)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardTransportButton(
                icon = Icons.Default.SkipPrevious,
                description = stringResource(StringRes.reader_toc_previous_chapter),
                onClick = onPreviousChapter,
            )
            CardTransportButton(
                icon = if (nowPlaying.isNarration) {
                    Icons.Default.Replay10
                } else {
                    Icons.Default.KeyboardDoubleArrowLeft
                },
                description = stringResource(
                    if (nowPlaying.isNarration) {
                        StringRes.reader_audio_back_10
                    } else {
                        StringRes.reader_audio_previous_sentence
                    },
                ),
                onClick = onSkipBack,
            )
            IconButton(
                onClick = onPlayPause,
                enabled = !nowPlaying.isLoading,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (isEink) colors.ink else colors.accent),
            ) {
                Icon(
                    if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = playLabel,
                    tint = if (isEink) colors.surface else colors.onAccent,
                )
            }
            CardTransportButton(
                icon = if (nowPlaying.isNarration) {
                    Icons.Default.Forward10
                } else {
                    Icons.Default.KeyboardDoubleArrowRight
                },
                description = stringResource(
                    if (nowPlaying.isNarration) {
                        StringRes.reader_audio_forward_10
                    } else {
                        StringRes.reader_audio_next_sentence
                    },
                ),
                onClick = onSkipForward,
            )
            CardTransportButton(
                icon = Icons.Default.SkipNext,
                description = stringResource(StringRes.reader_toc_next_chapter),
                onClick = onNextChapter,
            )
        }
        ProgressLine(nowPlaying.progress, isEink)
    }
}

@Composable
private fun CardTransportButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = Ember.colors.ink)
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
    isActive: Boolean = false,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(16.dp)
    val tint = if (isActive && isEink) colors.surface else if (isActive) colors.accentText else colors.ink
    Column(
        modifier = modifier
            .height(60.dp)
            .clip(shape)
            .background(
                when {
                    isActive && isEink -> colors.ink
                    isActive -> colors.navActive
                    isEink -> colors.surface
                    else -> colors.bg
                },
            )
            .then(if (isEink) Modifier.border(1.5.dp, colors.line, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides tint) { icon() }
        }
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * 60dp mini-player: sits under the persistent reader and as the footer of the Contents, Search
 * and Display sheets so playback stays controllable while a sheet is open.
 */
@Composable
internal fun ReaderMiniPlayer(
    nowPlaying: NowPlayingUi,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val playLabel = stringResource(
        if (nowPlaying.isPlaying) StringRes.reader_overlay_pause else StringRes.reader_overlay_play,
    )
    Surface(modifier = modifier.fillMaxWidth(), color = colors.surface) {
        Column {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.track)) {
                Box(
                    Modifier.fillMaxWidth(nowPlaying.progress.coerceIn(0f, 1f)).height(2.dp).background(colors.accent),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(58.dp)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPlayPause,
                    enabled = !nowPlaying.isLoading,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (nowPlaying.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = playLabel,
                        tint = colors.ink,
                    )
                }
                Text(
                    nowPlaying.miniLabel,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    color = colors.ink2,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

internal fun formatAudioTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}
