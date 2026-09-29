package com.retro99.home.ui.navigation

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.ContinueReadingEntryPoint
import com.retro99.analytics.api.ContinueReadingMediaType
import com.retro99.analytics.api.ContinueReadingOpenOperation
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent.ContinueReadingOpenOutcome
import com.retro99.analytics.api.NavigationAnalyticsEvent.ContinueReadingOpenReasonCode
import com.retro99.analytics.api.ReaderSettingsScreenViewed
import com.retro99.analytics.api.ServerManagementAnalyticsEvent
import com.retro99.analytics.api.StatisticsAnalyticsEvent
import com.retro99.analytics.api.beginContinueReadingOpen
import com.retro99.analytics.api.completeContinueReadingOpen
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
import com.retro99.reader.ui.playback.stopForServer
import com.retro99.user.api.UserRegistry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
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
    private var hasCheckedOpenLastBookOnLaunch = false
    private var failedBubblePosition: BubblePositionModel? = null

    /** Stops an active media session only when it belongs to the server being logged out. */
    fun stopPlaybackForServer(serverId: String, operationContext: DiagnosticContext) {
        val stopped = try {
            nowPlayingProvider.stopForServer(serverId) {
                analytics.logBreadcrumb(
                    operationContext.copy(stage = "playback_stop", outcome = "started"),
                )
            }
        } catch (error: Exception) {
            analytics.logBreadcrumb(
                operationContext.copy(
                    stage = "playback_stop",
                    outcome = "failed",
                    reasonCode = "playback_stop_failed",
                ),
            )
            throw error
        }

        if (stopped) {
            analytics.logBreadcrumb(
                operationContext.copy(stage = "playback_stop", outcome = "succeeded"),
            )
        }
    }

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
        var correlationId: String? = null
        var reasonCode = "initial_load"
        var stateResolved = false
        observeProfileScopedPreference(
            activeProfileIds = userRegistry.observeActiveProfile().map { it?.id },
            observePreference = { observeCurrentlyReadingUseCase() },
        )
            .onEach { snapshot ->
                if (!snapshot.isResolved) {
                    correlationId = newContinueReadingProfileCorrelationId()
                    reasonCode = if (snapshot.isProfileSwitch) "profile_switch" else "initial_load"
                    stateResolved = false
                    updateState { it.copy(currentlyReading = null) }
                    analytics.logBreadcrumb(
                        continueReadingProfileContext(
                            correlationId = correlationId,
                            stage = "started",
                            outcome = "started",
                            reasonCode = reasonCode,
                        ),
                    )
                } else {
                    updateState { it.copy(currentlyReading = snapshot.value?.toUiModel()) }
                    if (snapshot.isFirstValue && !stateResolved) {
                        stateResolved = true
                        val outcome = if (snapshot.value == null) "unavailable" else "available"
                        analytics.logEvent(
                            NavigationAnalyticsEvent.ContinueReadingProfileStateResolved(
                                isAvailable = snapshot.value != null,
                                isProfileSwitch = snapshot.isProfileSwitch,
                            ),
                        )
                        analytics.logBreadcrumb(
                            continueReadingProfileContext(
                                correlationId = correlationId,
                                stage = "terminal",
                                outcome = outcome,
                                reasonCode = reasonCode,
                            ),
                        )
                    }
                }
            }
            .catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                val failureContext = continueReadingProfileContext(
                    correlationId = correlationId ?: newContinueReadingProfileCorrelationId(),
                    stage = "terminal",
                    outcome = "failed",
                    reasonCode = "current_book_observation_failed",
                )
                analytics.logException(error, failureContext)
                updateState { it.copy(currentlyReading = null) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Checks if the "Open Last Book on Launch" setting is enabled and
     * emits a navigation event to open the reader if there's a currently reading book.
     */
    /** Runs after saveable navigation state is restored to avoid replacing an existing Reader. */
    fun checkOpenLastBookOnLaunch(restoredDestination: HomeDestination?) {
        if (hasCheckedOpenLastBookOnLaunch) return
        hasCheckedOpenLastBookOnLaunch = true

        val isEnabled = preferences.getBoolean(
            PreferencesKey.OpenLastBookOnLaunch,
            defaultValue = false,
        )
        if (!isEnabled) return

        val currentlyReading = getCurrentlyReadingUseCase()
        if (currentlyReading == null) {
            analytics.logEvent(
                NavigationAnalyticsEvent.LastBookLaunchCompleted(
                    screen = "home",
                    outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Skipped,
                    reasonCode = "no_current_book",
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "home",
                    sourceScreen = "splash",
                    entryPoint = "app_launch",
                    action = "open_last_book",
                    operation = "reader_open",
                    stage = "terminal",
                    outcome = "skipped",
                    reasonCode = "no_current_book",
                ),
            )
            return
        }

        val isMatchingRestoredReader = restoredDestination.isReaderFor(
            serverId = currentlyReading.serverId,
            bookUuid = currentlyReading.bookUuid,
            bookType = currentlyReading.bookType,
        )
        if (isMatchingRestoredReader) {
            reportLastBookRouteAlreadyRestored(
                bookType = currentlyReading.bookType,
                readerWillResolveOutcome = restoredDestination.isLastBookLaunchReaderFor(
                    serverId = currentlyReading.serverId,
                    bookUuid = currentlyReading.bookUuid,
                    bookType = currentlyReading.bookType,
                ),
            )
            return
        }

        analytics.logEvent(
            NavigationAnalyticsEvent.LastBookLaunchAttempted(
                bookType = currentlyReading.bookType.name.lowercase(),
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "home",
                sourceScreen = "splash",
                entryPoint = "app_launch",
                action = "open_last_book",
                operation = "reader_open",
                stage = "navigation",
                outcome = "started",
                mediaType = currentlyReading.bookType.name.lowercase(),
            ),
        )

        emitNavigationEvent(
            HomeNavigationEvent.NavigateToReaderReplacing(
                serverId = currentlyReading.serverId,
                bookUuid = currentlyReading.bookUuid,
                bookType = currentlyReading.bookType,
                tab = HomeTab.Books,
                isLastBookOnLaunch = true,
            )
        )
    }

    /** Records that a restored Reader route already satisfies the app-launch destination. */
    fun reportLastBookRouteAlreadyRestored(
        bookType: BookType,
        readerWillResolveOutcome: Boolean,
        attemptAlreadyRecorded: Boolean = false,
    ) {
        if (!attemptAlreadyRecorded) {
            analytics.logEvent(
                NavigationAnalyticsEvent.LastBookLaunchAttempted(
                    bookType = bookType.name.lowercase(),
                ),
            )
        }
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "home",
                sourceScreen = "splash",
                entryPoint = "app_launch",
                action = "open_last_book",
                operation = "reader_open",
                stage = "route_restored",
                outcome = "started",
                reasonCode = "reader_route_restored",
                mediaType = bookType.name.lowercase(),
            ),
        )
        if (!readerWillResolveOutcome) {
            analytics.logEvent(
                NavigationAnalyticsEvent.LastBookLaunchCompleted(
                    screen = "reader",
                    outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Skipped,
                    reasonCode = "reader_route_restored",
                    bookType = bookType.name.lowercase(),
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "reader",
                    sourceScreen = "home",
                    entryPoint = "app_launch",
                    action = "open_last_book",
                    operation = "reader_open",
                    stage = "terminal",
                    outcome = "skipped",
                    reasonCode = "reader_route_restored",
                    mediaType = bookType.name.lowercase(),
                ),
            )
        }
    }

    private fun observeBubblePosition() {
        observeProfileScopedPreference(
            activeProfileIds = userRegistry.observeActiveProfile().map { it?.id },
            observePreference = {
                observeUserPreferenceUseCase<BubblePositionModel>(PreferencesKey.BubblePosition)
            },
        )
            .onEach { snapshot ->
                if (!snapshot.isResolved) {
                    failedBubblePosition = null
                    updateState {
                        it.copy(
                            bubblePosition = null,
                            bubblePositionSaveFailureCount = 0,
                        )
                    }
                } else {
                    updateState {
                        it.copy(bubblePosition = snapshot.value ?: BubblePositionModel.DEFAULT)
                    }
                }
            }
            .catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                analytics.logException(
                    error,
                    DiagnosticContext(
                        screen = "home",
                        action = "observe_bubble_position",
                        operation = "bubble_position_observation",
                        stage = "terminal",
                        outcome = "failed",
                        reasonCode = "bubble_position_observation_failed",
                    ),
                )
                failedBubblePosition = null
                updateState { it.copy(bubblePosition = null) }
            }
            .launchIn(viewModelScope)
    }

    private fun saveBubblePosition(position: BubblePositionModel, isRetry: Boolean): Boolean {
        val succeeded = executeBubblePositionSave(
            analytics = analytics,
            side = position.toBubbleSide(),
            isRetry = isRetry,
            persist = { saveUserPreferenceUseCase(PreferencesKey.BubblePosition, position) },
        )
        if (succeeded) {
            failedBubblePosition = null
            updateState { it.copy(bubblePositionSaveFailureCount = 0) }
        } else {
            failedBubblePosition = position
            updateState {
                it.copy(bubblePositionSaveFailureCount = it.bubblePositionSaveFailureCount + 1)
            }
        }
        return succeeded
    }

    private fun saveBubblePosition(side: BubbleSide, yFraction: Float) {
        saveBubblePosition(
            position = BubblePositionModel.fromBubbleSide(side, yFraction),
            isRetry = false,
        )
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
            HomeNavigationIntent.RetryBubblePositionSave -> {
                failedBubblePosition?.let { saveBubblePosition(it, isRetry = true) }
            }
            HomeNavigationIntent.DismissBubblePositionSaveError -> {
                failedBubblePosition = null
            }
            HomeNavigationIntent.ClearCurrentlyReading -> {
                clearCurrentlyReadingUseCase()
            }

            // Navigation intents - emit corresponding events
            is HomeNavigationIntent.NavigateTo -> {
                emitNavigationEvent(HomeNavigationEvent.NavigateTo(intent.destination))
            }
            is HomeNavigationIntent.SwitchTab -> handleTabSwitch(intent)
            is HomeNavigationIntent.GoBack -> handleGoBack(intent)

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

    @OptIn(ExperimentalUuidApi::class)
    private fun handleTabSwitch(intent: HomeNavigationIntent.SwitchTab) {
        val sourceTab = intent.sourceTab.name.lowercase()
        val destinationTab = intent.tab.name.lowercase()
        if (intent.sourceTab == intent.tab) {
            analytics.logEvent(
                NavigationAnalyticsEvent.TabReselected(tabName = destinationTab),
            )
            return
        }

        val correlationId = Uuid.random().toString()
        analytics.logEvent(
            NavigationAnalyticsEvent.TabSwitchAttempted(
                sourceTab = sourceTab,
                destinationTab = destinationTab,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = destinationTab,
                sourceScreen = sourceTab,
                entryPoint = "bottom_navigation",
                action = "switch_tab",
                operation = "tab_navigation",
                stage = "navigation",
                outcome = "started",
                correlationId = correlationId,
            ),
        )
        emitNavigationEvent(
            HomeNavigationEvent.SwitchTab(
                sourceTab = intent.sourceTab,
                tab = intent.tab,
                correlationId = correlationId,
            ),
        )
    }

    /** Records the terminal tab outcome only after the navigation owner applies the selection. */
    fun reportTabSwitchApplied(
        sourceTab: HomeTab,
        destinationTab: HomeTab,
        correlationId: String,
        applied: Boolean,
    ) {
        val source = sourceTab.name.lowercase()
        val destination = destinationTab.name.lowercase()
        val outcome = if (applied) {
            NavigationAnalyticsEvent.TabSwitchOutcome.Succeeded
        } else {
            NavigationAnalyticsEvent.TabSwitchOutcome.Failed
        }
        analytics.logEvent(
            NavigationAnalyticsEvent.TabSwitched(
                sourceTab = source,
                destinationTab = destination,
                outcome = outcome,
            ),
        )

        val context = DiagnosticContext(
            screen = destination,
            sourceScreen = source,
            entryPoint = "bottom_navigation",
            action = "switch_tab",
            operation = "tab_navigation",
            stage = "terminal",
            outcome = outcome.value,
            reasonCode = if (applied) null else "tab_selection_not_applied",
            correlationId = correlationId,
        )
        analytics.logBreadcrumb(context)
        if (!applied) {
            analytics.logException(
                IllegalStateException("Tab navigation did not select the requested destination."),
                context,
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun handleGoBack(intent: HomeNavigationIntent.GoBack) {
        val sourceScreen = intent.sourceScreen
        val destinationScreen = intent.destinationScreen
        val entryPoint = intent.entryPoint
        val context = if (sourceScreen != null && destinationScreen != null && entryPoint != null) {
            HomeNavigationBackContext(
                sourceScreen = sourceScreen,
                destinationScreen = destinationScreen,
                entryPoint = entryPoint,
                correlationId = Uuid.random().toString(),
            )
        } else {
            null
        }

        if (context != null) {
            analytics.logEvent(
                NavigationAnalyticsEvent.BackNavigationAttempted(
                    sourceScreen = context.sourceScreen,
                    destinationScreen = context.destinationScreen,
                    entryPoint = context.entryPoint,
                ),
            )
            analytics.logBreadcrumb(
                backNavigationDiagnosticContext(
                    context = context,
                    stage = "navigation",
                    outcome = "started",
                ),
            )
        }
        emitNavigationEvent(HomeNavigationEvent.GoBack(context))
    }

    /** Completes Back telemetry only after the navigation owner applies the requested pop. */
    fun reportBackNavigationApplied(context: HomeNavigationBackContext, applied: Boolean) {
        val outcome = if (applied) {
            NavigationAnalyticsEvent.BackNavigationOutcome.Succeeded
        } else {
            NavigationAnalyticsEvent.BackNavigationOutcome.Failed
        }
        analytics.logEvent(
            NavigationAnalyticsEvent.BackNavigationCompleted(
                sourceScreen = context.sourceScreen,
                destinationScreen = context.destinationScreen,
                entryPoint = context.entryPoint,
                outcome = outcome,
            ),
        )
        val diagnosticContext = backNavigationDiagnosticContext(
            context = context,
            stage = "terminal",
            outcome = outcome.value,
            reasonCode = if (applied) null else "back_navigation_not_applied",
        )
        analytics.logBreadcrumb(diagnosticContext)
        if (!applied) {
            analytics.logException(
                IllegalStateException("Back navigation did not apply the requested destination."),
                diagnosticContext,
            )
        }
    }

    private fun backNavigationDiagnosticContext(
        context: HomeNavigationBackContext,
        stage: String,
        outcome: String,
        reasonCode: String? = null,
    ) = DiagnosticContext(
        screen = context.sourceScreen,
        sourceScreen = context.sourceScreen,
        destinationScreen = context.destinationScreen,
        entryPoint = context.entryPoint,
        action = "back",
        operation = "navigation_back",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        correlationId = context.correlationId,
    )

    /** Records an exposure only after the navigation owner reports this route as visible. */
    fun reportAppSettingsViewed(sourceScreen: String, entryPoint: String) {
        analytics.logEvent(
            AppSettingsAnalyticsEvent.ScreenViewed(
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "app_settings",
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
                action = "screen_view",
                operation = "app_settings_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    /** Records an exposure only after the navigation owner reports Reader Settings as visible. */
    fun reportReaderSettingsViewed(sourceScreen: String, entryPoint: String) {
        analytics.logEvent(
            ReaderSettingsScreenViewed(
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "reader_settings",
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
                action = "screen_view",
                operation = "reader_settings_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    /** Records an exposure only after the navigation owner reports Statistics as visible. */
    fun reportStatisticsViewed(sourceScreen: String, entryPoint: String) {
        analytics.logEvent(
            StatisticsAnalyticsEvent.StatisticsViewed(
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "statistics",
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
                action = "screen_view",
                operation = "statistics_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    /** Records an exposure only after the navigation owner reports this route as visible. */
    fun reportServerManagementViewed(sourceScreen: String, entryPoint: String) {
        analytics.logEvent(
            ServerManagementAnalyticsEvent.ScreenViewed(
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "server_management",
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
                action = "screen_view",
                operation = "server_management_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    /** Records an exposure only after the navigation owner reports Sync & Backup as visible. */
    fun reportSyncAndBackupViewed(sourceScreen: String, entryPoint: String) {
        analytics.logEvent(
            NavigationAnalyticsEvent.SyncAndBackupViewed(
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
            ),
        )
        analytics.logBreadcrumb(
            DiagnosticContext(
                screen = "sync_and_backup",
                sourceScreen = sourceScreen,
                entryPoint = entryPoint,
                action = "screen_view",
                operation = "sync_and_backup_route",
                stage = "visible",
                outcome = "succeeded",
            ),
        )
    }

    /**
     * Handles the request to open a reader.
     * Checks if there's a playback conflict and shows dialog if needed.
     */
    private fun handleRequestOpenReader(intent: HomeNavigationIntent.RequestOpenReader) {
        val continueReadingOpenOperation = beginContinueReadingOpen(intent.continueReadingEntryPoint, intent.bookType)
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
                        continueReadingOpenOperation = continueReadingOpenOperation,
                    )
                )
            }
        } else {
            // No conflict, navigate directly
            navigateToReader(
                intent.serverId,
                intent.bookUuid,
                intent.bookType,
                continueReadingOpenOperation = continueReadingOpenOperation,
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

        // Confirm the source-attributed attempt instead of recording conflict cancellation.
        updateState { it.copy(playbackConflictDialog = null) }
        navigateToReader(
            dialogState.targetServerId,
            dialogState.targetBookUuid,
            dialogState.targetBookType,
            continueReadingOpenOperation = dialogState.continueReadingOpenOperation,
        )
    }

    private fun dismissPlaybackConflictDialog() {
        viewState.value.playbackConflictDialog?.continueReadingOpenOperation?.let { operation ->
            analytics.completeContinueReadingOpen(
                operation = operation,
                outcome = ContinueReadingOpenOutcome.Cancelled,
                reasonCode = ContinueReadingOpenReasonCode.PlaybackConflictDismissed,
            )
        }
        updateState { it.copy(playbackConflictDialog = null) }
    }

    private fun navigateToReader(
        serverId: String,
        bookUuid: String,
        bookType: BookType,
        continueReadingOpenOperation: ContinueReadingOpenOperation? = null,
    ) {
        emitNavigationEvent(
            HomeNavigationEvent.NavigateTo(
                HomeDestination.Reader(
                    serverId = serverId,
                    bookUuid = bookUuid,
                    bookType = bookType,
                    readerOpenEntryPoint = continueReadingOpenOperation?.entryPoint?.value,
                    readerOpenCorrelationId = continueReadingOpenOperation?.correlationId,
                )
            )
        )
    }

    private fun beginContinueReadingOpen(entryPointValue: String?, bookType: BookType): ContinueReadingOpenOperation? {
        val entryPoint = ContinueReadingEntryPoint.values().firstOrNull { it.value == entryPointValue } ?: return null
        val mediaType = ContinueReadingMediaType.values().firstOrNull { it.value == bookType.value } ?: return null
        return analytics.beginContinueReadingOpen(entryPoint, mediaType)
    }

    private fun emitNavigationEvent(event: HomeNavigationEvent) {
        viewModelScope.launch {
            _navigationEvents.emit(event)
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newContinueReadingProfileCorrelationId(): String = Uuid.random().toString()

private fun continueReadingProfileContext(
    correlationId: String?,
    stage: String,
    outcome: String,
    reasonCode: String,
): DiagnosticContext = DiagnosticContext(
    screen = "home",
    action = "resolve_continue_reading_target",
    operation = "continue_reading_profile_rebind",
    stage = stage,
    outcome = outcome,
    reasonCode = reasonCode,
    correlationId = correlationId,
)
