package com.retro99.home.ui.navigation

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookType
import com.retro99.home.ui.deeplink.DeepLinkDestination
import com.retro99.home.ui.deeplink.DeepLinkHandler
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.usecase.ClearCurrentlyReadingUseCase
import com.retro99.reader.domain.usecase.GetCurrentlyReadingUseCase
import com.retro99.reader.domain.usecase.ObserveCurrentlyReadingUseCase
import com.retro99.reader.ui.playback.NowPlayingProvider
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/**
 * ViewModel for Home screen that handles non-navigation UI concerns.
 *
 * Navigation state is now managed by [HomeNavigationStateHolder] in the composable layer
 * using Nav3's [rememberNavBackStack] for automatic persistence across process death.
 *
 * This ViewModel handles:
 * - Currently reading book state
 * - Bubble position persistence
 * - Deep link navigation requests (emitted as [HomeNavigationEvent])
 * - "Open last book on launch" feature (emitted as [HomeNavigationEvent])
 * - Analytics for tab switches
 */
@KoinViewModel
class HomeNavigationViewModel(
    deepLinkHandler: DeepLinkHandler,
    @Provided val analytics: Analytics,
    @Provided private val getCurrentlyReadingUseCase: GetCurrentlyReadingUseCase,
    @Provided private val observeCurrentlyReadingUseCase: ObserveCurrentlyReadingUseCase,
    @Provided private val clearCurrentlyReadingUseCase: ClearCurrentlyReadingUseCase,
    @Provided private val preferences: Preferences,
    @Provided private val userRegistry: UserRegistry,
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val nowPlayingProvider: NowPlayingProvider,
) : BaseViewModel<HomeUiState, HomeNavigationIntent>(
    HomeUiState(),
) {

    private val _navigationEvents = MutableSharedFlow<HomeNavigationEvent>(replay = 1, extraBufferCapacity = 1)

    private val logger = Logger.withTag("HomeNavigationViewModel")

    /**
     * Navigation events that should be consumed by the composable to perform navigation.
     * These are one-shot events for deep links and "open last book on launch".
     * Uses replay = 1 to ensure events emitted during init (like "open last book on launch")
     * are received by collectors that start after the event was emitted.
     */
    val navigationEvents: SharedFlow<HomeNavigationEvent> = _navigationEvents.asSharedFlow()

    /**
     * Clears the replay cache after an event has been consumed.
     * This prevents the same event from being replayed on recomposition.
     */
    fun clearNavigationEventReplayCache() {
        _navigationEvents.resetReplayCache()
    }

    private val _userProfileChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits when the active user profile changes.
     * Used to reset navigation state when switching users.
     */
    val userProfileChanged: SharedFlow<Unit> = _userProfileChanged.asSharedFlow()

    init {
        observeDeepLinks(deepLinkHandler)
        observeUserProfileChanges()
        observeCurrentlyReading()
        observeBubblePosition()
        observeShowContinueReading()
        checkOpenLastBookOnLaunch()
        observeNowPlaying()
    }

    /**
     * Observes user profile changes and emits events to reset navigation.
     * Uses drop(1) to skip the initial emission when the ViewModel is created.
     */
    private fun observeUserProfileChanges() {
        userRegistry.observeActiveProfile()
            .map { it?.id }
            .distinctUntilChanged()
            .drop(1) // Skip initial value to avoid resetting on first load
            .onEach {
                // Emit event to reset navigation
                _userProfileChanged.emit(Unit)
            }
            .launchIn(viewModelScope)
    }

    private fun observeCurrentlyReading() {
        observeCurrentlyReadingUseCase()
            .onEach { currentlyReading ->
                updateState { it.copy(currentlyReading = currentlyReading?.toUiModel()) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Checks if the "Open Last Book on Launch" setting is enabled and
     * emits a navigation event to open the reader if there's a currently reading book.
     */
    private fun checkOpenLastBookOnLaunch() {
        val isEnabled = preferences.getBoolean(
            PreferencesKey.OpenLastBookOnLaunch,
            defaultValue = false,
        )
        if (!isEnabled) return

        val currentlyReading = getCurrentlyReadingUseCase() ?: return

        analytics.logEvent(
            NavigationAnalyticsEvent.ContinueReadingLaunched(bookUuid = currentlyReading.bookUuid),
        )

        emitNavigationEvent(
            HomeNavigationEvent.NavigateToReaderReplacing(
                serverId = currentlyReading.serverId,
                bookUuid = currentlyReading.bookUuid,
                bookType = currentlyReading.bookType,
                tab = HomeTab.Books,
            )
        )
    }

    private fun observeBubblePosition() {
        observeUserPreferenceUseCase<BubblePositionModel>(PreferencesKey.BubblePosition)
            .onEach { position ->
                updateState { it.copy(bubblePosition = position ?: BubblePositionModel.DEFAULT) }
            }
            .launchIn(viewModelScope)
    }

    private fun saveBubblePosition(side: BubbleSide, yFraction: Float) {
        val position = BubblePositionModel.fromBubbleSide(side, yFraction)
        saveUserPreferenceUseCase(PreferencesKey.BubblePosition, position)
    }

    private fun observeShowContinueReading() {
        preferences.observeBoolean(PreferencesKey.ShowContinueReading, defaultValue = true)
            .onEach { showContinueReading ->
                updateState { it.copy(showContinueReading = showContinueReading) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Observes now-playing state from the playback system.
     * Updates the UI state for the mini-player display.
     * Also navigates to the reader when a different book starts playing
     * (e.g., when user selects a book from Android Auto).
     */
    private fun observeNowPlaying() {
        combine(
            nowPlayingProvider.nowPlayingInfo,
            nowPlayingProvider.isPlaying,
        ) { info, isPlaying ->
            info to isPlaying
        }
            .onEach { (info, isPlaying) ->
                val previousInfo = viewState.value.nowPlayingInfo
                logger.d {
                    "observeNowPlaying: info=${info?.bookTitle}, isPlaying=$isPlaying, " +
                        "prevBook=${previousInfo?.bookUuid}, newBook=${info?.bookUuid}"
                }

                // Check if a different book was selected (e.g., from Android Auto)
                // Navigate to that book's reader to keep phone in sync
                // Note: We check BEFORE updating state, and don't require isPlaying
                // because the book change and play state come in separate emissions
                val bookChanged = info != null && previousInfo?.bookUuid != info.bookUuid

                updateState { it.copy(nowPlayingInfo = info, isAudioPlaying = isPlaying) }

                if (bookChanged) {
                    logger.d { "observeNowPlaying: NAVIGATING to reader for ${info?.bookTitle}" }
                    emitNavigationEvent(
                        HomeNavigationEvent.NavigateToReaderReplacing(
                            serverId = info!!.serverId,
                            bookUuid = info.bookUuid,
                            bookType = info.bookType,
                            tab = HomeTab.Books,
                        )
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeDeepLinks(deepLinkHandler: DeepLinkHandler) {
        deepLinkHandler.navigationEvents
            .onEach { destination ->
                handleDeepLinkDestination(destination)
            }
            .launchIn(viewModelScope)
    }

    private fun handleDeepLinkDestination(destination: DeepLinkDestination) {
        when (destination) {
            is DeepLinkDestination.Reader -> {
                analytics.logEvent(
                    NavigationAnalyticsEvent.DeepLinkOpened(
                        bookUuid = destination.bookUuid,
                        bookType = destination.bookType.name,
                    ),
                )
                emitNavigationEvent(
                    HomeNavigationEvent.NavigateToReaderReplacing(
                        serverId = destination.serverId,
                        bookUuid = destination.bookUuid,
                        bookType = destination.bookType,
                        tab = HomeTab.Books,
                    ),
                )
            }
        }
    }

    override fun onIntent(intent: HomeNavigationIntent) {
        when (intent) {
            // UI state intents
            is HomeNavigationIntent.UpdateBubblePosition -> saveBubblePosition(intent.side, intent.yFraction)
            HomeNavigationIntent.ClearCurrentlyReading -> {
                clearCurrentlyReadingUseCase()
            }

            // Navigation intents - emit corresponding events
            is HomeNavigationIntent.NavigateTo -> {
                emitNavigationEvent(HomeNavigationEvent.NavigateTo(intent.destination))
            }
            is HomeNavigationIntent.SwitchTab -> {
                analytics.logEvent(NavigationAnalyticsEvent.TabSwitched(tabName = intent.tab.name.lowercase()))
                emitNavigationEvent(HomeNavigationEvent.SwitchTab(intent.tab))
            }
            HomeNavigationIntent.GoBack -> {
                emitNavigationEvent(HomeNavigationEvent.GoBack)
            }

            // Reader navigation with conflict check
            is HomeNavigationIntent.RequestOpenReader -> {
                handleRequestOpenReader(intent)
            }
            is HomeNavigationIntent.OpenReader -> {
                navigateToReader(intent.serverId, intent.bookUuid, intent.bookType)
            }

            // Mini-player intents
            HomeNavigationIntent.ToggleMiniPlayerPlayPause -> {
                nowPlayingProvider.togglePlayPause()
            }
            HomeNavigationIntent.StopMiniPlayerPlayback -> {
                nowPlayingProvider.stop()
            }

            // Playback conflict dialog intents
            HomeNavigationIntent.PlaybackConflictStopAndOpen -> {
                handleStopAndOpenNewBook()
            }
            HomeNavigationIntent.PlaybackConflictDismiss -> {
                dismissPlaybackConflictDialog()
            }
        }
    }

    /**
     * Handles the request to open a reader.
     * Checks if there's a playback conflict and shows dialog if needed.
     */
    private fun handleRequestOpenReader(intent: HomeNavigationIntent.RequestOpenReader) {
        val currentlyPlaying = nowPlayingProvider.nowPlayingInfo.value

        // Check if there's a different book currently playing
        if (currentlyPlaying != null && currentlyPlaying.bookUuid != intent.bookUuid) {
            // Show conflict dialog
            updateState {
                it.copy(
                    playbackConflictDialog = PlaybackConflictDialogState(
                        currentlyPlayingTitle = currentlyPlaying.bookTitle,
                        targetBookTitle = intent.bookTitle,
                        targetServerId = intent.serverId,
                        targetBookUuid = intent.bookUuid,
                        targetBookType = intent.bookType,
                        targetSelection = intent.selection,
                    )
                )
            }
        } else {
            // No conflict, navigate directly
            navigateToReader(
                serverId = intent.serverId,
                bookUuid = intent.bookUuid,
                bookType = intent.bookType,
                selection = intent.selection,
            )
        }
    }

    /**
     * Stops current playback and opens the new book.
     */
    private fun handleStopAndOpenNewBook() {
        val dialogState = viewState.value.playbackConflictDialog ?: return

        // Stop current playback
        nowPlayingProvider.stop()

        // Dismiss dialog and navigate
        dismissPlaybackConflictDialog()
        navigateToReader(
            dialogState.targetServerId,
            dialogState.targetBookUuid,
            dialogState.targetBookType,
            dialogState.targetSelection,
        )
    }

    private fun dismissPlaybackConflictDialog() {
        updateState { it.copy(playbackConflictDialog = null) }
    }

    private fun navigateToReader(
        serverId: String,
        bookUuid: String,
        bookType: BookType,
        selection: HomeDestination.ReaderLaunchSelection? = null,
    ) {
        emitNavigationEvent(
            HomeNavigationEvent.NavigateTo(
                HomeDestination.Reader(
                    serverId = serverId,
                    bookUuid = bookUuid,
                    bookType = bookType,
                    selection = selection,
                )
            )
        )
    }

    private fun emitNavigationEvent(event: HomeNavigationEvent) {
        viewModelScope.launch {
            _navigationEvents.emit(event)
        }
    }
}
