package com.retro99.home.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import com.retro99.books.ui.detail.BookDetailScreen
import com.retro99.books.ui.list.BooksListScreen
import com.retro99.books.ui.series.detail.SeriesDetailScreen
import com.retro99.cloudaccount.ui.CloudAccountScreen
import com.retro99.home.ui.appsettings.AppSettingsScreen
import com.retro99.home.ui.series.SeriesListScreen
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.audiobook.AudiobookPlayerScreen
import com.retro99.reader.ui.reader.ReaderScreen
import com.retro99.reader.ui.reader.ReaderCloseSource
import com.retro99.settings.ui.SettingsScreen
import com.retro99.settings.ui.servers.ServerManagementScreen
import com.retro99.statistics.ui.StatisticsScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeNavigation(
    onNavigateToLogin: (String?) -> Unit,
    failedExistingServerLoginIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    viewModel: HomeNavigationViewModel = koinViewModel(),
) {
    // Navigation state managed by Nav3's rememberNavBackStack for automatic persistence
    val navigationState = rememberHomeNavigationState()

    // UI state from ViewModel (currently reading, bubble position)
    val uiState by viewModel.viewState.collectAsState()

    // Intent dispatcher for navigation actions
    val intentDispatcher: (HomeNavigationIntent) -> Unit = { viewModel.onIntent(it) }

    // Handle navigation events from ViewModel
    LaunchedEffect(viewModel) {
        // Inspect the restored back stack before optionally opening the current book.
        viewModel.checkOpenLastBookOnLaunch(navigationState.currentDestination)
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is HomeNavigationEvent.NavigateTo -> {
                    navigationState.navigateTo(event.destination)
                }
                is HomeNavigationEvent.SwitchTab -> {
                    navigationState.switchTab(event.tab)
                    viewModel.reportTabSwitchApplied(
                        sourceTab = event.sourceTab,
                        destinationTab = event.tab,
                        correlationId = event.correlationId,
                        applied = navigationState.currentTab == event.tab,
                    )
                }
                is HomeNavigationEvent.GoBack -> {
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
            navigationState.resetAllStacks()
        }
    }

    val currentDestination = navigationState.currentDestination
    var lastVisibleDestination by remember { mutableStateOf<HomeDestination?>(null) }
    LaunchedEffect(currentDestination) {
        if (currentDestination is HomeDestination.ServerManagement) {
            val previousDestination = lastVisibleDestination
                ?: navigationState.currentBackStack.dropLast(1).lastOrNull()
            val exposure = serverManagementExposureContext(previousDestination)
            viewModel.reportServerManagementViewed(
                sourceScreen = exposure.sourceScreen,
                entryPoint = exposure.entryPoint,
            )
        }
        lastVisibleDestination = currentDestination
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
                            headerContent = {
                                if (uiState.showContinueReading) {
                                    currentlyReading?.let { book ->
                                        ContinueReadingShelf(
                                            currentlyReading = book,
                                            onClick = {
                                                intentDispatcher(
                                                    HomeNavigationIntent.RequestOpenReader(
                                                        serverId = book.serverId,
                                                        bookUuid = book.bookUuid,
                                                        bookType = book.bookType,
                                                        bookTitle = book.bookTitle,
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
                                onClose = { requestBack("close_button") },
                            )
                        } else {
                            ReaderScreen(
                                serverId = destination.serverId,
                                bookUuid = destination.bookUuid,
                                bookType = destination.bookType,
                                isLastBookOnLaunch = destination.isLastBookOnLaunch,
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
    }
}

@Composable
private fun HomeBottomNavigationBar(
    currentTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.labelRes),
                    )
                },
                label = {
                    Text(text = stringResource(tab.labelRes))
                },
            )
        }
    }
}
