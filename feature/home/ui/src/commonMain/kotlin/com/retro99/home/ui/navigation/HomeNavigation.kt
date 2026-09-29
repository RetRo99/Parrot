package com.retro99.home.ui.navigation

import androidx.compose.foundation.layout.Box
import com.retro99.base.ui.compose.Ember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation3.runtime.entryProvider
import com.retro99.analytics.api.ContinueReadingEntryPoint
import com.retro99.books.ui.detail.BookDetailScreen
import com.retro99.books.ui.list.BooksListScreen
import com.retro99.books.ui.series.detail.SeriesDetailScreen
import com.retro99.cloudaccount.ui.CloudAccountScreen
import com.retro99.home.ui.appsettings.AppSettingsScreen
import com.retro99.home.ui.appsettings.ProfileOperationTapShieldHolder
import com.retro99.home.ui.series.SeriesListScreen
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.audiobook.AudiobookPlayerScreen
import com.retro99.reader.ui.reader.ReaderScreen
import com.retro99.reader.ui.reader.ReaderCloseSource
import com.retro99.settings.ui.SettingsScreen
import com.retro99.settings.ui.servers.ServerManagementScreen
import com.retro99.statistics.ui.StatisticsScreen
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.continue_reading_position_save_failed
import resources.translations.general_retry

@Composable
fun HomeNavigation(
    onNavigateToLogin: (String?, Boolean) -> Unit,
    failedExistingServerLoginIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    viewModel: HomeNavigationViewModel = koinViewModel(),
) {
    // Navigation state managed by Nav3's rememberNavBackStack for automatic persistence
    val navigationState = rememberHomeNavigationState()

    // UI state from ViewModel (currently reading, bubble position)
    val uiState by viewModel.viewState.collectAsState()
    val isProfileOperationTapShielded by ProfileOperationTapShieldHolder.instance.isBlocking.collectAsState()
    val cancelAutofillSession = rememberAutofillSessionCanceller()

    // Intent dispatcher for navigation actions
    val intentDispatcher: (HomeNavigationIntent) -> Unit = { viewModel.onIntent(it) }
    val snackbarHostState = remember { SnackbarHostState() }
    val bubblePositionSaveFailedMessage = stringResource(StringRes.continue_reading_position_save_failed)
    val retryMessage = stringResource(StringRes.general_retry)

    LaunchedEffect(uiState.bubblePositionSaveFailureCount) {
        if (uiState.bubblePositionSaveFailureCount > 0) {
            val result = snackbarHostState.showSnackbar(
                message = bubblePositionSaveFailedMessage,
                actionLabel = retryMessage,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) {
                intentDispatcher(HomeNavigationIntent.RetryBubblePositionSave)
            } else {
                intentDispatcher(HomeNavigationIntent.DismissBubblePositionSaveError)
            }
        }
    }

    // Handle navigation events from ViewModel
    LaunchedEffect(viewModel) {
        // Inspect the restored back stack before optionally opening the current book.
        viewModel.checkOpenLastBookOnLaunch(navigationState.currentDestination)
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is HomeNavigationEvent.NavigateTo -> {
                    if (shouldCancelCloudAccountAutofill(navigationState.currentDestination)) {
                        cancelAutofillSession()
                    }
                    navigationState.navigateTo(event.destination)
                }
                is HomeNavigationEvent.SwitchTab -> {
                    if (
                        shouldCancelCloudAccountAutofill(navigationState.currentDestination) &&
                        event.tab != navigationState.currentTab
                    ) {
                        cancelAutofillSession()
                    }
                    navigationState.switchTab(event.tab)
                    viewModel.reportTabSwitchApplied(
                        sourceTab = event.sourceTab,
                        destinationTab = event.tab,
                        correlationId = event.correlationId,
                        applied = navigationState.currentTab == event.tab,
                    )
                }
                is HomeNavigationEvent.GoBack -> {
                    if (shouldCancelCloudAccountAutofill(navigationState.currentDestination)) {
                        cancelAutofillSession()
                    }
                    val applied = navigationState.goBack()
                    event.context?.let { context ->
                        viewModel.reportBackNavigationApplied(context, applied)
                    }
                }
                is HomeNavigationEvent.NavigateToReaderReplacing -> {
                    if (
                        event.isLastBookOnLaunch &&
                        navigationState.currentDestination.isReaderFor(
                            event.serverId,
                            event.bookUuid,
                            event.bookType,
                        )
                    ) {
                        viewModel.reportLastBookRouteAlreadyRestored(
                            bookType = event.bookType,
                            readerWillResolveOutcome = navigationState.currentDestination
                                .isLastBookLaunchReaderFor(
                                    event.serverId,
                                    event.bookUuid,
                                    event.bookType,
                                ),
                            attemptAlreadyRecorded = true,
                        )
                    } else {
                        if (shouldCancelCloudAccountAutofill(navigationState.currentDestination)) {
                            cancelAutofillSession()
                        }
                        navigationState.switchTab(event.tab)
                        navigationState.navigateToReplacing(
                            HomeDestination.Reader(
                                serverId = event.serverId,
                                bookUuid = event.bookUuid,
                                bookType = event.bookType,
                                isLastBookOnLaunch = event.isLastBookOnLaunch,
                            ),
                            event.tab,
                        )
                    }
                }
            }
            // Clear replay cache after consuming the event to prevent replay on recomposition
            viewModel.clearNavigationEventReplayCache()
        }
    }

    // Reset navigation when user profile changes
    LaunchedEffect(viewModel) {
        viewModel.userProfileChanged.collect {
            if (shouldCancelCloudAccountAutofill(navigationState.currentDestination)) {
                cancelAutofillSession()
            }
            navigationState.resetAllStacks()
        }
    }

    val currentDestination = navigationState.currentDestination
    var lastVisibleDestination by remember { mutableStateOf<HomeDestination?>(null) }
    var lastVisibleTab by remember { mutableStateOf(navigationState.currentTab) }
    LaunchedEffect(currentDestination, navigationState.currentTab) {
        if (currentDestination is HomeDestination.ServerManagement) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val exposure = serverManagementExposureContext(previousDestination)
            viewModel.reportServerManagementViewed(
                sourceScreen = exposure.sourceScreen,
                entryPoint = exposure.entryPoint,
            )
        }
        if (currentDestination is HomeDestination.SyncAndBackup) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val exposure = syncAndBackupExposureContext(previousDestination)
            viewModel.reportSyncAndBackupViewed(
                sourceScreen = exposure.sourceScreen,
                entryPoint = exposure.entryPoint,
            )
        }
        if (currentDestination is HomeDestination.AppSettings) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val entryPoint = when {
                previousDestination == null -> "route_restore"
                lastVisibleTab != navigationState.currentTab -> "bottom_navigation"
                else -> "navigation_back"
            }
            viewModel.reportAppSettingsViewed(
                sourceScreen = previousDestination?.analyticsScreenName() ?: "home",
                entryPoint = entryPoint,
            )
        }
        if (currentDestination is HomeDestination.Statistics) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val exposure = statisticsExposureContext(
                previousDestination = previousDestination,
                tabChanged = lastVisibleTab != navigationState.currentTab,
            )
            viewModel.reportStatisticsViewed(
                sourceScreen = exposure.sourceScreen,
                entryPoint = exposure.entryPoint,
            )
        }
        if (currentDestination is HomeDestination.Settings) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val exposure = readerSettingsExposureContext(previousDestination)
            viewModel.reportReaderSettingsViewed(
                sourceScreen = exposure.sourceScreen,
                entryPoint = exposure.entryPoint,
            )
        }
        lastVisibleDestination = currentDestination
        lastVisibleTab = navigationState.currentTab
    }
    val showBottomBar = (currentDestination as? BottomBarDestination)?.showBottomBar != false
    val isInReader = currentDestination is HomeDestination.Reader
    val currentlyReading = uiState.currentlyReading
    val nowPlayingInfo = uiState.nowPlayingInfo
    val isAudioPlaying = uiState.isAudioPlaying

    val requestBack: (String) -> Unit = { entryPoint ->
        val source = navigationState.currentDestination
        val currentStack = navigationState.currentBackStack
        val destination = when {
            currentStack.size > 1 -> currentStack[currentStack.lastIndex - 1]
            navigationState.currentTab != navigationState.startTab ->
                navigationState.backStacks[navigationState.startTab]?.lastOrNull()
            else -> null
        }
        intentDispatcher(
            if (source != null && destination != null) {
                HomeNavigationIntent.GoBack(
                    sourceScreen = source.analyticsScreenName(),
                    destinationScreen = destination.analyticsScreenName(),
                    entryPoint = entryPoint,
                )
            } else {
                HomeNavigationIntent.GoBack()
            },
        )
    }

    // Show mini-player when audio is playing and not in reader
    val showMiniPlayer = !isInReader && nowPlayingInfo != null

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        // Mini-player above bottom nav
                        AnimatedMiniPlayer(
                            visible = showMiniPlayer,
                            nowPlayingInfo = nowPlayingInfo,
                            isPlaying = isAudioPlaying,
                            onPlayPauseClick = { intentDispatcher(HomeNavigationIntent.ToggleMiniPlayerPlayPause) },
                            onStopClick = { intentDispatcher(HomeNavigationIntent.StopMiniPlayerPlayback) },
                            onPlayerClick = {
                                // Navigate to reader when clicking the mini-player
                                nowPlayingInfo?.let { info ->
                                    intentDispatcher(
                                        HomeNavigationIntent.OpenReader(
                                            serverId = info.serverId,
                                            bookUuid = info.bookUuid,
                                            bookType = info.bookType,
                                        )
                                    )
                                }
                            },
                        )
                        HomeBottomNavigationBar(
                            currentTab = navigationState.currentTab,
                            onTabSelected = { tab ->
                                intentDispatcher(
                                    HomeNavigationIntent.SwitchTab(
                                        sourceTab = navigationState.currentTab,
                                        tab = tab,
                                    ),
                                )
                            },
                        )
                    }
                }
            },
        ) { paddingValues ->
            BottomSheetNavDisplay(
                backStack = navigationState.currentBackStack,
                onBack = { requestBack("system_back") },
                modifier = Modifier.padding(paddingValues),
                entryProvider = entryProvider {
                    entry<HomeDestination.BooksList> {
                        BooksListScreen(
                            onNavigateToBookDetail = { book ->
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(
                                        HomeDestination.BookDetail(
                                            serverId = book.serverId,
                                            bookUuid = book.uuid,
                                        )
                                    )
                                )
                            },
                            headerContent = { books ->
                                if (uiState.showContinueReading) {
                                    currentlyReading?.let { book ->
                                        ContinueReadingShelf(
                                            currentlyReading = book,
                                            author = books
                                                .firstOrNull { candidate ->
                                                    candidate.uuid == book.bookUuid
                                                }
                                                ?.authors
                                                ?.joinToString(", "),
                                            onClick = {
                                                intentDispatcher(
                                                    HomeNavigationIntent.RequestOpenReader(
                                                        serverId = book.serverId,
                                                        bookUuid = book.bookUuid,
                                                        bookType = book.bookType,
                                                        bookTitle = book.bookTitle,
                                                        continueReadingEntryPoint = ContinueReadingEntryPoint.Shelf.value,
                                                    )
                                                )
                                            },
                                            onClear = {
                                                intentDispatcher(HomeNavigationIntent.ClearCurrentlyReading)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }

                    entry<HomeDestination.SeriesList> {
                        SeriesListScreen(
                            onNavigateToSeriesDetail = { series ->
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(
                                        HomeDestination.SeriesDetail(
                                            seriesUuid = series.uuid,
                                            seriesName = series.name,
                                        )
                                    )
                                )
                            },
                        )
                    }

                    entry<HomeDestination.SeriesDetail> { destination ->
                        SeriesDetailScreen(
                            seriesUuid = destination.seriesUuid,
                            seriesName = destination.seriesName,
                            onNavigateToBookDetail = { book ->
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(
                                        HomeDestination.BookDetail(
                                            serverId = book.serverId,
                                            bookUuid = book.uuid,
                                        )
                                    )
                                )
                            },
                            onBack = { requestBack("toolbar_back") },
                        )
                    }

                    entry<HomeDestination.BookDetail> { destination ->
                        BookDetailScreen(
                            serverId = destination.serverId,
                            bookUuid = destination.bookUuid,
                            onNavigateToReader = { serverId, bookUuid, bookType, bookTitle ->
                                intentDispatcher(
                                    HomeNavigationIntent.RequestOpenReader(
                                        serverId = serverId,
                                        bookUuid = bookUuid,
                                        bookType = bookType,
                                        bookTitle = bookTitle,
                                    )
                                )
                            },
                            onNavigateToSeriesDetail = { seriesUuid, seriesName ->
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(
                                        HomeDestination.SeriesDetail(
                                            seriesUuid = seriesUuid,
                                            seriesName = seriesName,
                                        )
                                    )
                                )
                            },
                            onBack = { requestBack("toolbar_back") },
                        )
                    }

                    entry<HomeDestination.Reader> { destination ->
                        if (destination.bookType == BookType.AUDIOBOOK) {
                            AudiobookPlayerScreen(
                                serverId = destination.serverId,
                                bookUuid = destination.bookUuid,
                                readerOpenEntryPoint = destination.readerOpenEntryPoint,
                                readerOpenCorrelationId = destination.readerOpenCorrelationId,
                                onClose = { requestBack("close_button") },
                            )
                        } else {
                            ReaderScreen(
                                serverId = destination.serverId,
                                bookUuid = destination.bookUuid,
                                bookType = destination.bookType,
                                isLastBookOnLaunch = destination.isLastBookOnLaunch,
                                readerOpenEntryPoint = destination.readerOpenEntryPoint,
                                readerOpenCorrelationId = destination.readerOpenCorrelationId,
                                onClose = { closeSource -> requestBack(closeSource.entryPoint) },
                                onSettingsClick = {
                                    intentDispatcher(
                                        HomeNavigationIntent.NavigateTo(HomeDestination.Settings)
                                    )
                                },
                            )
                        }
                    }

                    entry<HomeDestination.Settings> {
                        SettingsScreen(
                            onClose = { requestBack("close_button") },
                        )
                    }

                    entry<HomeDestination.AppSettings> {
                        AppSettingsScreen(
                            onNavigateToStatistics = {
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(HomeDestination.Statistics)
                                )
                            },
                            onNavigateToServerManagement = {
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(HomeDestination.ServerManagement)
                                )
                            },
                            onNavigateToSyncAndBackup = {
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(HomeDestination.SyncAndBackup)
                                )
                            },
                            onNavigateToReaderSettings = {
                                intentDispatcher(
                                    HomeNavigationIntent.NavigateTo(HomeDestination.Settings)
                                )
                            },
                        )
                    }

                    entry<HomeDestination.ServerManagement> {
                        ServerManagementScreen(
                            onNavigateToLogin = onNavigateToLogin,
                            failedLoginServerIds = failedExistingServerLoginIds,
                            onBack = { requestBack("toolbar_back") },
                            stopPlaybackForServer = viewModel::stopPlaybackForServer,
                            modifier = Modifier,
                        )
                    }

                    entry<HomeDestination.SyncAndBackup> {
                        CloudAccountScreen(
                            onBack = { requestBack("toolbar_back") },
                        )
                    }

                    entry<HomeDestination.Statistics> {
                        StatisticsScreen(
                            onBack = { requestBack("toolbar_back") },
                            // Back arrow only makes sense when Statistics is pushed onto a stack;
                            // as a tab root it is the root of its own tab.
                            showBack = navigationState.currentBackStack.size > 1,
                        )
                    }
                },
            )
        }

        // Draggable floating bubble for Continue Reading
        // Only show when position is loaded (not null) to avoid flicker.
        // It stays off settings-style screens (they carry toggles and rows the
        // bubble would otherwise cover) and off the reader.
        val bubblePosition = uiState.bubblePosition
        if (!isInReader &&
            currentDestination !is HomeDestination.BooksList &&
            currentDestination?.hidesContinueBubble != true &&
            currentlyReading != null &&
            bubblePosition != null &&
            uiState.showContinueReading
        ) {
            DraggableFloatingBubble(
                modifier = Modifier.fillMaxSize(),
                initialSide = bubblePosition.toBubbleSide(),
                initialYFraction = bubblePosition.yFraction,
                edgePadding = 16f,
                onPositionChanged = { side, yFraction ->
                    intentDispatcher(HomeNavigationIntent.UpdateBubblePosition(side, yFraction))
                },
            ) {
                ContinueReadingBubble(
                    currentlyReading = currentlyReading,
                    onClick = {
                        intentDispatcher(
                            HomeNavigationIntent.RequestOpenReader(
                                serverId = currentlyReading.serverId,
                                bookUuid = currentlyReading.bookUuid,
                                bookType = currentlyReading.bookType,
                                bookTitle = currentlyReading.bookTitle,
                                continueReadingEntryPoint = ContinueReadingEntryPoint.FloatingBubble.value,
                            )
                        )
                    },
                )
            }
        }

        // Playback conflict dialog
        uiState.playbackConflictDialog?.let { dialogState ->
            PlaybackConflictDialog(
                state = dialogState,
                onStopAndOpen = { intentDispatcher(HomeNavigationIntent.PlaybackConflictStopAndOpen) },
                onDismiss = { intentDispatcher(HomeNavigationIntent.PlaybackConflictDismiss) },
            )
        }

        if (isProfileOperationTapShielded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isProfileOperationTapShielded) {
                        awaitPointerEventScope {
                            while (isProfileOperationTapShielded) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun HomeBottomNavigationBar(
    currentTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    Column(modifier = modifier.background(colors.nav)) {
        HorizontalDivider(thickness = style.border, color = colors.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTab.entries.forEach { tab ->
                val selected = currentTab == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onTabSelected(tab) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 28.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.navActive else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = if (selected) colors.navActiveContent else colors.ink2,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = Ember.type.navLabel,
                        color = when {
                            !selected -> colors.ink2
                            style.progressOutlined -> colors.ink
                            else -> colors.navActiveContent
                        },
                    )
                }
            }
        }
    }
}
