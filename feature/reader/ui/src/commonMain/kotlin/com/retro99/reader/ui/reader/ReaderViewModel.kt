package com.retro99.reader.ui.reader

import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.base.formatCurrentTime
import com.retro99.base.now
import com.retro99.base.nowMillis
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.BookmarkDomainModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderInitializationData
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.usecase.AddBookmarkUseCase
import com.retro99.reader.domain.usecase.DeleteBookmarkUseCase
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.InitializeReaderUseCase
import com.retro99.reader.domain.usecase.ObserveBookmarksUseCase
import com.retro99.reader.domain.usecase.ReorderBookmarksUseCase
import com.retro99.reader.domain.usecase.SaveReaderSettingsUseCase
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.domain.usecase.SetCurrentlyReadingUseCase
import com.retro99.reader.domain.usecase.UpdateBookmarkTitleUseCase
import com.retro99.reader.ui.di.InitialAudioPosition
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.BookmarkUiModel
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.ReaderSettingsUiModel
import com.retro99.reader.ui.model.toDomainModel
import com.retro99.reader.ui.model.toPositionUiModel
import com.retro99.reader.ui.model.toUiData
import com.retro99.reader.ui.model.toUiModel
import com.retro99.reader.ui.navigator.AudioController
import com.retro99.reader.ui.navigator.BookController
import com.retro99.reader.ui.navigator.NarrationController
import com.retro99.reader.ui.navigator.TTS_SYSTEM_VOICE_KEY
import com.retro99.reader.ui.navigator.TtsController
import com.retro99.reader.ui.navigator.TtsPreviewState
import com.retro99.reader.ui.publication.PublicationState
import com.retro99.reader.ui.service.EpubPublicationService
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.SupertonicTermsStore
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import com.retro99.reader.ui.tts.neuralVoicePackage
import com.retro99.statistics.domain.usecase.SaveReadingSessionUseCase
import com.retro99.sync.domain.RoutineSyncScheduler
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.TimeSource

@KoinViewModel
class ReaderViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val bookType: BookType,
    @InjectedParam private val isLastBookOnLaunch: Boolean,
    @InjectedParam private val onClose: () -> Unit,
    @InjectedParam private val onSettingsClick: () -> Unit,
    @Provided private val initializeReaderUseCase: InitializeReaderUseCase,
    @Provided private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
    @Provided private val getCustomReaderFontsUseCase: GetCustomReaderFontsUseCase,
    @Provided private val saveReaderSettingsUseCase: SaveReaderSettingsUseCase,
    @Provided private val saveReadingSessionUseCase: SaveReadingSessionUseCase,
    @Provided private val syncNowUseCase: SyncNowUseCase,
    @Provided private val setCurrentlyReadingUseCase: SetCurrentlyReadingUseCase,
    @Provided private val addBookmarkUseCase: AddBookmarkUseCase,
    @Provided private val observeBookmarksUseCase: ObserveBookmarksUseCase,
    @Provided private val deleteBookmarkUseCase: DeleteBookmarkUseCase,
    @Provided private val updateBookmarkTitleUseCase: UpdateBookmarkTitleUseCase,
    @Provided private val reorderBookmarksUseCase: ReorderBookmarksUseCase,
    @Provided private val publicationService: EpubPublicationService,
    @Provided private val analytics: Analytics,
    @Provided private val supertonicTermsStore: SupertonicTermsStore,
) : BaseViewModel<ReaderViewState, ReaderIntent>(
    ReaderViewState(
        bookUuid = bookUuid,
        bookType = bookType,
        hasAcceptedSupertonicTerms = supertonicTermsStore.hasAcceptedCurrentTerms(),
    )
) {

    private val readerScope: Scope by lazy {
        getKoin().getOrCreateScope<ReaderScope>(bookUuid).apply {
            viewState.value.publicationState?.let { pubState ->
                val initialPositionMs = pubState.position?.audioTimestampMs
                val initialHref = pubState.position?.href
                declare(pubState.publication)
                declare(
                    InitialAudioPosition(
                        positionMs = initialPositionMs,
                        href = initialHref,
                    )
                )
            }
        }
    }

    private val bookController: BookController by lazy {
        readerScope.get<BookController>().also {
            addCloseable(it)
        }
    }

    private val audioController: AudioController by lazy {
        readerScope.get<AudioController>().also {
            addCloseable(it)
        }
    }

    private val ttsController: TtsController by lazy {
        readerScope.get<TtsController>().also { controller ->
            addCloseable(controller)
        }
    }

    private var activeNarrationController: NarrationController? = null

    private val syncCoordinator: ReaderSyncCoordinator by lazy {
        readerScope.get<ReaderSyncCoordinator>().also {
            addCloseable(it)
        }
    }

    private val readingSpeedTracker: ReadingSpeedTracker by lazy {
        readerScope.get<ReadingSpeedTracker>()
    }

    /** Job for the time update coroutine, cancelled when showCurrentTime is disabled */
    private var timeUpdateJob: Job? = null

    /** Job for the Read Aloud sleep timer countdown. */
    private var sleepTimerJob: Job? = null

    /** Job for downloading and loading the selected neural voice model. */
    private var ttsPreparationJob: Job? = null

    /** Job for preparing sentence elements and enabling double-tap TTS playback. */
    private var ttsSentencePlaybackJob: Job? = null

    /** Serializes full reader-settings updates so concurrent controls cannot lose changes. */
    private val readerSettingsSaveMutex = Mutex()

    /** Timestamp when the book was opened, used for calculating reading duration */
    private var bookOpenedTimestamp: Long = 0L

    /** Checkpoints the auto-open target before process death can bypass the Reader close action. */
    private var currentBookTargetCheckpoint: CurrentBookTargetCheckpoint? = null

    private val currentBookTargetSaveState = CurrentBookTargetSaveState()

    /** Guards against Close and back both navigating away from the reader. */
    private var hasRequestedClose: Boolean = false

    /** Prevents success, failure and close callbacks from reporting multiple launch outcomes. */
    private val lastBookLaunchOutcomeGate = LastBookLaunchOutcomeGate()

    /** Monotonic mark used to generate unique bookmark IDs with nanosecond precision. */
    private val bookmarkIdMark = TimeSource.Monotonic.markNow()

    /** Tracks the previous playing state to detect play/pause transitions */
    private var wasPlaying: Boolean = false

    private val routineSyncScheduler = RoutineSyncScheduler(
        scope = viewModelScope,
        nowMillis = ::nowMillis,
        requestSync = { routineSchedule ->
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.ROUTINE_PROGRESS,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.ROUTINE,
                    routineSchedule = routineSchedule,
                ),
            )
        },
    )

    init {
        initializeReader()
        observeShowCurrentTimeSetting()
    }

    /**
     * Observes the showCurrentTime setting and starts/stops time updates accordingly.
     * When enabled, updates the current time immediately and then every minute.
     * When disabled, cancels the time update coroutine and clears the current time.
     */
    private fun observeShowCurrentTimeSetting() {
        getReaderSettingsUseCase()
            .map { it.showCurrentTime }
            .distinctUntilChanged()
            .onEach { showCurrentTime ->
                if (showCurrentTime) {
                    startTimeUpdates()
                } else {
                    stopTimeUpdates()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Starts periodic updates of the current time.
     * Updates immediately and then every minute.
     */
    private fun startTimeUpdates() {
        // Cancel any existing job before starting a new one
        timeUpdateJob?.cancel()
        timeUpdateJob = viewModelScope.launch {
            while (true) {
                updateState { it.copy(currentTime = formatCurrentTime()) }
                delay(TIME_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the periodic time updates and clears the current time display.
     */
    private fun stopTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = null
        updateState { it.copy(currentTime = "") }
    }

    override fun onIntent(intent: ReaderIntent) {
        when (intent) {
            is ReaderIntent.UpdateSettings -> updateSettings(intent.settings)
            ReaderIntent.ToggleSettings -> toggleSettings()
            ReaderIntent.Close -> close()
            ReaderIntent.OnSettingsClicked -> onSettingsClick()
            ReaderIntent.UseLocalPosition -> resolveConflictWithLocal()
            ReaderIntent.UseRemotePosition -> resolveConflictWithRemote()
            ReaderIntent.GoToNextPage -> goToNextPage()
            ReaderIntent.GoToPreviousPage -> goToPreviousPage()
            ReaderIntent.TogglePlayback -> togglePlayback()
            is ReaderIntent.SelectTtsVoice -> selectTtsVoice(intent.voiceId)
            is ReaderIntent.DownloadNeuralVoicePackage -> {
                downloadNeuralVoicePackage(intent.voicePackage)
            }

            is ReaderIntent.DeleteNeuralVoicePackage -> {
                deleteNeuralVoicePackage(intent.voicePackage)
            }

            is ReaderIntent.RetryTtsVoicePreparation -> {
                retryTtsVoicePreparation(intent.voicePackage)
            }

            ReaderIntent.AcceptSupertonicTermsAndDownload -> {
                acceptSupertonicTermsAndDownload()
            }

            is ReaderIntent.PreviewTtsVoice -> previewTtsVoice(intent.voiceId, intent.text)
            ReaderIntent.StopTtsPreview -> stopTtsPreview()
            ReaderIntent.OpenVoiceSettings -> openVoiceSettings()
            ReaderIntent.CloseVoiceSettings -> closeVoiceSettings()
            is ReaderIntent.SetTtsRate -> setTtsRate(intent.rate)
            is ReaderIntent.SetTtsPitch -> setTtsPitch(intent.pitch)
            is ReaderIntent.SetTtsEnabled -> setTtsEnabled(intent.enabled)
            ReaderIntent.ToggleAudioOnlyMode -> toggleAudioOnlyMode()
            is ReaderIntent.SeekTo -> seekTo(intent.audioTimestampMs)
            is ReaderIntent.SetPlaybackSpeed -> setPlaybackSpeed(intent.speed)
            is ReaderIntent.StartSleepTimer -> startSleepTimer(intent.durationMs)
            ReaderIntent.CancelSleepTimer -> cancelSleepTimer()
            ReaderIntent.DismissSleepTimerWarning -> dismissSleepTimerWarning()
            is ReaderIntent.SkipForward -> skipForward(intent.milliseconds)
            is ReaderIntent.SkipBackward -> skipBackward(intent.milliseconds)
            ReaderIntent.ToggleToc -> toggleToc()
            is ReaderIntent.GoToChapter -> goToChapter(intent.href, intent.currentPosition)
            ReaderIntent.GoToNextChapter -> goToNextChapter()
            ReaderIntent.GoToPreviousChapter -> goToPreviousChapter()
            is ReaderIntent.GoToChapterAndPlay -> goToChapterAndPlay(intent.href, intent.currentPosition)
            ReaderIntent.GoToNextChapterAndPlay -> goToNextChapterAndPlay()
            ReaderIntent.GoToPreviousChapterAndPlay -> goToPreviousChapterAndPlay()
            is ReaderIntent.UndoChapterNavigation -> undoChapterNavigation(intent.position)
            ReaderIntent.DismissChapterNavigationUndo -> dismissChapterNavigationUndo()
            is ReaderIntent.SetHighlightColor -> setHighlightColor(intent.colorArgb)
            ReaderIntent.Retry -> retry()
            ReaderIntent.DismissNoAudioMessage -> dismissNoAudioMessage()
            ReaderIntent.DismissBookmarkSaveFailed -> dismissBookmarkSaveFailed()
            ReaderIntent.ToggleBookmarks -> toggleBookmarks()
            ReaderIntent.AddBookmark -> addBookmark()
            ReaderIntent.DismissBookmarkAdded -> dismissBookmarkAdded()
            ReaderIntent.DismissBookmarkAlreadyExists -> dismissBookmarkAlreadyExists()
            is ReaderIntent.UndoBookmark -> undoBookmark(intent.id)
            is ReaderIntent.RenameBookmark -> renameBookmark(intent.id, intent.newTitle)
            is ReaderIntent.ReorderBookmarks -> reorderBookmarks(intent.bookmarkIds)
            ReaderIntent.GoToPreviousBookmark -> goToPreviousBookmark()
            ReaderIntent.GoToNextBookmark -> goToNextBookmark()
            ReaderIntent.DismissNoMoreBookmarks -> dismissNoMoreBookmarks()
            is ReaderIntent.DeleteBookmark -> deleteBookmark(intent.id)
            is ReaderIntent.GoToBookmark -> goToBookmark(intent.bookmark)
        }
    }

    private fun dismissNoAudioMessage() {
        updateState { it.copy(showNoAudioMessage = false) }
    }

    private fun dismissBookmarkSaveFailed() {
        updateState { it.copy(showBookmarkSaveFailed = false) }
    }

    private fun retry() {
        updateState { it.copy(error = null) }
        if (isLastBookOnLaunch) {
            lastBookLaunchOutcomeGate.beginAttempt()
            analytics.logEvent(
                NavigationAnalyticsEvent.LastBookLaunchAttempted(
                    bookType = bookType.name.lowercase(),
                    stage = "retry",
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "reader",
                    sourceScreen = "home",
                    entryPoint = "app_launch",
                    action = "open_last_book",
                    operation = "reader_open",
                    stage = "retry",
                    outcome = "started",
                    mediaType = bookType.name.lowercase(),
                ),
            )
        }
        initializeReader()
    }

    private fun goToNextPage() {
        bookController.goToNextPage()
    }

    private fun goToPreviousPage() {
        bookController.goToPreviousPage()
    }

    private fun observeSettingsChanges() {
        getReaderSettingsUseCase()
            .onEach { settings ->
                val uiSettings = settings.toUiModel()
                val previousTtsEnabled = viewState.value.publicationState?.settings?.ttsEnabled
                bookController.setSettings(uiSettings)
                updatePublicationState { it.copy(settings = uiSettings) }
                if (
                    viewState.value.isTtsReadAloud &&
                    previousTtsEnabled != null &&
                    previousTtsEnabled != uiSettings.ttsEnabled
                ) {
                    applyTtsEnabled(uiSettings.ttsEnabled)
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Updates the PublicationState within the ViewState.
     * Only applies the update if publicationState is not null.
     */
    private inline fun updatePublicationState(crossinline update: (PublicationState) -> PublicationState) {
        updateState { state ->
            state.publicationState?.let { pubState ->
                state.copy(publicationState = update(pubState))
            } ?: state
        }
    }

    private fun observeBookLocationChanges() {
        bookController.currentLocator
            .onEach { locator ->
                val currentState = viewState.value
                val positionUiModel = locator.toPositionUiModel(
                    basePosition = currentState.currentPosition,
                    createdAt = now().toString(),
                )
                updatePosition(positionUiModel)

                // Update chapter info from the enriched locator state
                // (word count is used internally by ReadingSpeedTracker via the locator flow)
                updateState { it.copy(chapterInfo = locator.chapterInfo) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Observes reading time info from the ReadingSpeedTracker.
     * The tracker handles all the logic internally (settings, page info, word count).
     */
    private fun observeReadingTimeInfo() {
        readingSpeedTracker.readingTimeInfo
            .onEach { readingTimeInfo ->
                updateState { it.copy(chapterReadingTimeInfo = readingTimeInfo) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Persists a confident reading speed measurement to settings.
     */
    private fun observeReadingSpeedPersistence() {
        readingSpeedTracker.establishedReadingSpeedWpm
            .filterNotNull()
            .distinctUntilChanged()
            .combine(getReaderSettingsUseCase()) { wpm, settings -> wpm to settings }
            .onEach { (wpm, settings) ->
                if (settings.readingSpeedWpm != wpm) {
                    saveReaderSettingsUpdate { latestSettings ->
                        latestSettings.copy(readingSpeedWpm = wpm)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeAudioPlaybackState() {
        audioController.audioPlaybackState
            .onEach { state ->
                updateAudioPosition(
                    positionMs = state.currentPositionMs,
                    totalDurationMs = state.totalDurationMs,
                )
            }
            .launchIn(viewModelScope)

        audioController.currentAudioLocator
            .onEach { audioLocator ->
                val audioHref = audioLocator?.locator?.href ?: return@onEach
                val currentHref = viewState.value.currentPosition?.href
                if (audioHref != currentHref) {
                    updatePublicationState { pubState ->
                        pubState.copy(
                            position = pubState.position?.copy(href = audioHref),
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun initializeReader() {
        viewModelScope.launch {
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.BOOK_OPEN,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.ROUTINE,
                ),
            )
            initializeReaderUseCase(serverId, bookUuid, bookType)
                .onSuccess { data ->
                    openPublication(data)
                }
                .onFailure { error ->
                    analytics.logEvent(
                        ReaderAnalyticsEvent.BookOpenFailed(
                            bookUuid = bookUuid,
                            bookType = bookType.name,
                            errorMessage = error.message ?: "Unknown reader initialization error",
                        ),
                    )
                    reportReaderOpenFailure(
                        error = error,
                        stage = "initialization",
                        reasonCode = "reader_initialization_failed",
                    )
                    updateState { it.copy(error = error) }
                }
        }
    }

    private suspend fun openPublication(data: ReaderInitializationData) {
        val settings = data.initialSettings.toUiModel()
        val customFonts = getCustomReaderFontsUseCase().first()
        val bookType = data.bookType
        val (position, conflict) = data.progressResult.toUiData()

        publicationService.openPublication(
            filePath = data.localEbookPath,
            serverId = data.serverId,
            bookUuid = data.bookUuid,
            bookType = bookType,
        ).onSuccess { publication ->
            // Track book opened event
            bookOpenedTimestamp = nowMillis()
            analytics.logEvent(
                ReaderAnalyticsEvent.BookOpened(
                    bookUuid = data.bookUuid,
                    bookType = bookType.name,
                )
            )

            // Create PublicationState with initial settings and position
            val publicationState = PublicationState(
                publication = publication,
                settings = settings,
                position = position,
                customFonts = customFonts,
            )

            updateState { state ->
                state.copy(
                    bookUuid = data.bookUuid,
                    bookTitle = data.bookTitle,
                    bookCoverUrl = data.bookCoverUrl,
                    publicationState = publicationState,
                    bookType = bookType,
                    positionConflict = conflict,
                    error = null,
                    currentAudioPositionMs = position?.audioTimestampMs ?: 0L,
                    tableOfContents = publication.tableOfContents,
                )
            }
            scheduleCurrentBookTargetCheckpoint()
            if (isLastBookOnLaunch) {
                reportLastBookLaunchOutcome(
                    outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Succeeded,
                    stage = "usable_content",
                    reasonCode = null,
                )
            }
            // Start observing after publication is ready
            observeBookLocationChanges()
            observeReadingTimeInfo()
            observeReadingSpeedPersistence()
            observeSettingsChanges()
            observeCustomFontChanges()
            observeBookmarks()
            // Initialize audio after publication is in state
            if (publication.hasMediaOverlays) {
                initAudio()
            } else {
                if (bookType == BookType.READALOUD) {
                    // Track when a ReadAloud book is missing media overlays and show snackbar
                    analytics.logEvent(
                        ReaderAnalyticsEvent.ReadAloudMissingMediaOverlays(
                            bookUuid = data.bookUuid,
                        ),
                    )
                    updateState { state -> state.copy(showNoAudioMessage = true) }
                }
                initTts()
            }
        }.onFailure { error ->
            analytics.logEvent(
                ReaderAnalyticsEvent.BookOpenFailed(
                    bookUuid = data.bookUuid,
                    bookType = bookType.name,
                    errorMessage = error.message ?: "Unknown publication error",
                )
            )
            reportReaderOpenFailure(
                error = error,
                stage = "publication",
                reasonCode = "publication_open_failed",
            )
            updateState { it.copy(error = error) }
        }
    }

    private fun reportReaderOpenFailure(
        error: AppError,
        stage: String,
        reasonCode: String,
    ) {
        val context = DiagnosticContext(
            screen = "reader",
            sourceScreen = "home".takeIf { isLastBookOnLaunch },
            entryPoint = "app_launch".takeIf { isLastBookOnLaunch },
            action = if (isLastBookOnLaunch) "open_last_book" else "open_book",
            operation = "reader_open",
            stage = stage,
            outcome = "failed",
            reasonCode = reasonCode,
            mediaType = bookType.name.lowercase(),
        )
        if (isLastBookOnLaunch) {
            reportLastBookLaunchOutcome(
                outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Failed,
                stage = "terminal",
                reasonCode = reasonCode,
                error = error,
            )
        } else if (error.shouldReportException) {
            error.log(analytics, context)
        } else {
            analytics.logBreadcrumb(context)
        }
    }

    private fun reportLastBookLaunchOutcome(
        outcome: NavigationAnalyticsEvent.LastBookLaunchOutcome,
        stage: String,
        reasonCode: String?,
        error: AppError? = null,
    ) {
        if (!isLastBookOnLaunch || !lastBookLaunchOutcomeGate.tryResolve()) return

        analytics.logEvent(
            NavigationAnalyticsEvent.LastBookLaunchCompleted(
                screen = "reader",
                outcome = outcome,
                stage = stage,
                reasonCode = reasonCode,
                bookType = bookType.name.lowercase(),
            ),
        )
        val context = DiagnosticContext(
            screen = "reader",
            sourceScreen = "home",
            entryPoint = "app_launch",
            action = "open_last_book",
            operation = "reader_open",
            stage = stage,
            outcome = outcome.value,
            reasonCode = reasonCode,
            mediaType = bookType.name.lowercase(),
        )
        if (error != null && error.shouldReportException) {
            error.log(analytics, context)
        } else {
            analytics.logBreadcrumb(context)
        }
    }

    private fun observeCustomFontChanges() {
        getCustomReaderFontsUseCase()
            .onEach { customFonts ->
                updatePublicationState { it.copy(customFonts = customFonts) }
            }
            .launchIn(viewModelScope)
    }

    private fun initAudio() {
        activeNarrationController = audioController
        observeNarrationPlaybackState(audioController)

        // Start sync coordinator (this also triggers lazy initialization of audioController)
        // Note: Initial audio position is handled via constructor injection in AudioController
        syncCoordinator.start(viewModelScope, audioController)

        // Set now-playing info for mini-player display
        val state = viewState.value
        audioController.setNowPlayingInfo(
            bookUuid = state.bookUuid,
            bookTitle = state.bookTitle,
            coverUrl = state.bookCoverUrl,
        )

        audioController.audioPlaybackState
            .map { it.isPlayerReady }
            .distinctUntilChanged()
            .filter { it }
            .onEach {
                updateState { it.copy(isAudioPlayerReady = true) }
            }
            .launchIn(viewModelScope)
        observeAudioPlaybackState()
    }

    private fun initTts() {
        viewModelScope.launch {
            bookController.currentLocator.first()
            val hasContent = ttsController.hasReadableContent()
            if (!hasContent) return@launch

            val settings = getReaderSettingsUseCase().first()
            val availableVoices = ttsController.availableVoices()
            val savedVoice = availableVoices
                .firstOrNull { voice -> voice.id == settings.ttsVoiceId }
            val selectedVoiceId = settings.ttsVoiceId?.takeIf {
                savedVoice != null &&
                        (
                                savedVoice.neuralVoicePackage != NeuralVoicePackage.SUPERTONIC ||
                                        currentViewState().hasAcceptedSupertonicTerms
                                )
            }
            activeNarrationController = ttsController
            updateState { state ->
                state.copy(
                    isTtsReadAloud = true,
                    showNoAudioMessage = false,
                    ttsVoices = availableVoices,
                    selectedTtsVoiceId = selectedVoiceId,
                )
            }

            observeNarrationPlaybackState(ttsController)

            observeTtsPreviewState()
            observeTtsVoicePreparationState()

            ttsController.selectVoice(selectedVoiceId)
            if (settings.ttsVoiceId != selectedVoiceId) {
                saveReaderSettingsUpdate { latestSettings ->
                    if (latestSettings.ttsVoiceId == settings.ttsVoiceId) {
                        latestSettings.copy(ttsVoiceId = selectedVoiceId)
                    } else {
                        latestSettings
                    }
                }
            }
            if (settings.ttsEnabled) {
                enableTtsSentencePlayback()
            }
            val selectedVoice = availableVoices
                .firstOrNull { voice -> voice.id == selectedVoiceId }
            if (
                settings.ttsEnabled &&
                selectedVoice?.isNeural == true &&
                !selectedVoice.needsDownload
            ) {
                prepareTtsVoice(selectedVoice.id)
            }
        }
    }

    private fun selectTtsVoice(voiceId: String?) {
        val selectedVoice = viewState.value.ttsVoices
            .firstOrNull { voice -> voice.id == voiceId }
        if (selectedVoice?.needsDownload == true) return
        if (
            selectedVoice?.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            return
        }
        val isNeural = selectedVoice?.isNeural == true
        analytics.logEvent(
            ReaderAnalyticsEvent.TtsVoiceSelected(
                bookUuid = bookUuid,
                voiceId = voiceId ?: TTS_SYSTEM_VOICE_KEY,
                isNeural = isNeural,
            ),
        )
        ttsController.stopPreview()
        ttsController.selectVoice(voiceId)
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = publicationState.settings.copy(ttsVoiceId = voiceId),
            )
        }
        launchReaderSettingsUpdate { settings -> settings.copy(ttsVoiceId = voiceId) }
        updateState { state ->
            state.copy(
                selectedTtsVoiceId = voiceId,
                failedTtsVoicePackage = null,
                failedTtsVoicePackageDeletion = null,
                ttsPreviewingVoiceId = null,
                isTtsPreviewPlaying = false,
            )
        }

        if (isNeural && voiceId != null) {
            prepareTtsVoice(voiceId)
        } else {
            ttsPreparationJob?.cancel()
            ttsPreparationJob = null
            updateState { state ->
                state.copy(
                    isTtsVoicePreparing = false,
                    preparingTtsVoicePackage = null,
                    ttsVoicePreparationProgress = null,
                )
            }
        }
    }

    private fun downloadNeuralVoicePackage(voicePackage: NeuralVoicePackage) {
        if (
            voicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            return
        }
        val neuralVoice = currentViewState().ttsVoices
            .firstOrNull { voice ->
                voice.neuralVoicePackage == voicePackage && voice.needsDownload
            }
            ?: return
        prepareTtsVoice(neuralVoice.id)
    }

    private fun acceptSupertonicTermsAndDownload() {
        supertonicTermsStore.acceptCurrentTerms()
        updateState { state -> state.copy(hasAcceptedSupertonicTerms = true) }
        downloadNeuralVoicePackage(NeuralVoicePackage.SUPERTONIC)
    }

    private fun retryTtsVoicePreparation(voicePackage: NeuralVoicePackage) {
        val voiceId = currentViewState().ttsVoices
            .firstOrNull { voice -> voice.neuralVoicePackage == voicePackage }
            ?.id
            ?: return
        prepareTtsVoice(voiceId)
    }

    private fun prepareTtsVoice(
        voiceId: String,
        onPrepared: (() -> Unit)? = null,
    ) {
        val voicePackage = voiceId.neuralVoicePackage() ?: return
        if (
            voicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            return
        }
        val activePackage = currentViewState().preparingTtsVoicePackage
        if (currentViewState().isTtsVoicePreparing && activePackage != voicePackage) {
            return
        }
        ttsPreparationJob?.cancel()
        val initialProgress = currentViewState().ttsVoices
            .firstOrNull { voice -> voice.id == voiceId }
            ?.let { voice ->
                if (voice.needsDownload) {
                    TtsPreparationProgress.Downloading(
                        downloadedBytes = 0L,
                        totalBytes = voice.downloadSizeBytes,
                    )
                } else {
                    TtsPreparationProgress.Finalizing
                }
            }
            ?: TtsPreparationProgress.Finalizing
        val preparationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext()[Job]
            updateState { state ->
                state.copy(
                    isTtsVoicePreparing = true,
                    preparingTtsVoicePackage = voicePackage,
                    ttsVoicePreparationProgress = initialProgress,
                    failedTtsVoicePackage = null,
                )
            }
            try {
                val isPrepared = ttsController.prepareVoice(voiceId) { progress ->
                    updateState { state ->
                        state.copy(ttsVoicePreparationProgress = progress)
                    }
                }
                if (isPrepared) {
                    val refreshedVoices = ttsController.availableVoices()
                    updateState { state ->
                        state.copy(
                            ttsVoices = refreshedVoices,
                            failedTtsVoicePackage = null,
                        )
                    }
                    onPrepared?.invoke()
                } else {
                    updateState { state ->
                        state.copy(
                            failedTtsVoicePackage = voicePackage,
                            ttsPreviewingVoiceId = null,
                            isTtsPreviewPlaying = false,
                        )
                    }
                }
            } finally {
                if (ttsPreparationJob === runningJob) {
                    updateState { state ->
                        state.copy(
                            isTtsVoicePreparing = false,
                            preparingTtsVoicePackage = null,
                            ttsVoicePreparationProgress = null,
                        )
                    }
                    ttsPreparationJob = null
                }
            }
        }
        ttsPreparationJob = preparationJob
        preparationJob.start()
    }

    private fun deleteNeuralVoicePackage(voicePackage: NeuralVoicePackage) {
        val preparationJob = ttsPreparationJob
        ttsPreparationJob = null
        viewModelScope.launch {
            preparationJob?.cancelAndJoin()
            updateState { state ->
                state.copy(
                    deletingTtsVoicePackage = voicePackage,
                    failedTtsVoicePackageDeletion = null,
                )
            }
            try {
                val deleted = ttsController.deleteNeuralVoicePackage(voicePackage)
                val refreshedVoices = ttsController.availableVoices()
                val packageStillDownloaded = refreshedVoices.any { voice ->
                    voice.neuralVoicePackage == voicePackage && !voice.needsDownload
                }
                if (deleted || !packageStillDownloaded) {
                    val selectedVoice = refreshedVoices.firstOrNull { voice ->
                        voice.id == currentViewState().selectedTtsVoiceId
                    }
                    val selectedVoiceWasDeleted =
                        selectedVoice?.neuralVoicePackage == voicePackage ||
                                currentViewState().selectedTtsVoiceId.neuralVoicePackage() ==
                                voicePackage
                    if (selectedVoiceWasDeleted) {
                        ttsController.selectVoice(null)
                        updatePublicationState { publicationState ->
                            publicationState.copy(
                                settings = publicationState.settings.copy(ttsVoiceId = null),
                            )
                        }
                        saveReaderSettingsUpdate { settings ->
                            settings.copy(ttsVoiceId = null)
                        }
                    }
                    updateState { state ->
                        state.copy(
                            ttsVoices = refreshedVoices,
                            selectedTtsVoiceId = if (selectedVoiceWasDeleted) {
                                null
                            } else {
                                state.selectedTtsVoiceId
                            },
                            isTtsVoicePreparing = false,
                            preparingTtsVoicePackage = null,
                            ttsVoicePreparationProgress = null,
                            failedTtsVoicePackage = null,
                            ttsPreviewingVoiceId = null,
                            isTtsPreviewPlaying = false,
                        )
                    }
                } else {
                    updateState { state ->
                        state.copy(failedTtsVoicePackageDeletion = voicePackage)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.logException(error, "Failed to delete neural voice package")
                updateState { state ->
                    state.copy(failedTtsVoicePackageDeletion = voicePackage)
                }
            } finally {
                updateState { state -> state.copy(deletingTtsVoicePackage = null) }
            }
        }
    }

    private fun openVoiceSettings() {
        analytics.logEvent(ReaderAnalyticsEvent.TtsVoiceSettingsOpened(bookUuid = bookUuid))
        updateState { state -> state.copy(isVoiceSettingsVisible = true) }
    }

    private fun closeVoiceSettings() {
        ttsController.stopPreview()
        updateState { state -> state.copy(isVoiceSettingsVisible = false) }
    }

    private fun setTtsRate(rate: Float) {
        analytics.logEvent(ReaderAnalyticsEvent.TtsRateChanged(bookUuid = bookUuid, rate = rate))
        ttsController.stopPreview()
        ttsController.setRate(rate)
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = publicationState.settings.copy(ttsRate = rate),
            )
        }
        launchReaderSettingsUpdate { settings -> settings.copy(ttsRate = rate) }
    }

    private fun setTtsPitch(pitch: Float) {
        analytics.logEvent(
            ReaderAnalyticsEvent.TtsPitchChanged(bookUuid = bookUuid, pitch = pitch),
        )
        ttsController.stopPreview()
        ttsController.setPitch(pitch)
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = publicationState.settings.copy(ttsPitch = pitch),
            )
        }
        launchReaderSettingsUpdate { settings -> settings.copy(ttsPitch = pitch) }
    }

    private fun setTtsEnabled(enabled: Boolean) {
        analytics.logEvent(ReaderAnalyticsEvent.TtsEnabledChanged(isEnabled = enabled))
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = publicationState.settings.copy(ttsEnabled = enabled),
            )
        }
        applyTtsEnabled(enabled)
        launchReaderSettingsUpdate { settings -> settings.copy(ttsEnabled = enabled) }
    }

    private fun applyTtsEnabled(enabled: Boolean) {
        if (!enabled) {
            disableTtsSentencePlayback()
            ttsPreparationJob?.cancel()
            ttsPreparationJob = null
            ttsController.stopPreview()
        } else {
            enableTtsSentencePlayback()
            val selectedVoice = viewState.value.ttsVoices
                .firstOrNull { voice -> voice.id == viewState.value.selectedTtsVoiceId }
            when {
                selectedVoice?.needsDownload == true -> openVoiceSettings()
                selectedVoice?.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC &&
                        !currentViewState().hasAcceptedSupertonicTerms -> openVoiceSettings()

                selectedVoice?.isNeural == true -> prepareTtsVoice(selectedVoice.id)
            }
        }
    }

    private fun enableTtsSentencePlayback() {
        ttsSentencePlaybackJob?.cancel()
        ttsSentencePlaybackJob = viewModelScope.launch {
            if (!ttsController.enableSentencePlayback()) return@launch

            syncCoordinator.startNarration(
                scope = viewModelScope,
                narrationController = ttsController,
                playFromSentence = { event ->
                    playTtsFromSentence(
                        fragmentId = event.fragmentId,
                        chapterHref = event.chapterHref,
                    )
                },
            )
        }
    }

    private fun disableTtsSentencePlayback() {
        ttsSentencePlaybackJob?.cancel()
        ttsSentencePlaybackJob = null
        syncCoordinator.stopNarration()
        ttsController.disableSentencePlayback()
    }

    private fun playTtsFromSentence(fragmentId: String, chapterHref: String?) {
        val selectedVoice = viewState.value.ttsVoices
            .firstOrNull { voice -> voice.id == viewState.value.selectedTtsVoiceId }
        if (selectedVoice?.needsDownload == true) {
            openVoiceSettings()
            return
        }
        if (
            selectedVoice?.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            openVoiceSettings()
            return
        }

        ttsController.playFromSentence(fragmentId, chapterHref)
    }

    private fun previewTtsVoice(voiceId: String?, text: String) {
        val isNeural = viewState.value.ttsVoices
            .firstOrNull { voice -> voice.id == voiceId }
            ?.isNeural == true
        analytics.logEvent(
            ReaderAnalyticsEvent.TtsVoicePreviewed(
                bookUuid = bookUuid,
                voiceId = voiceId ?: TTS_SYSTEM_VOICE_KEY,
                isNeural = isNeural,
            ),
        )
        val voice = viewState.value.ttsVoices.firstOrNull { candidate ->
            candidate.id == voiceId
        }
        if (voice?.needsDownload == true) return
        if (
            voice?.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            return
        }
        startTtsPreview(voiceId, text)
    }

    private fun startTtsPreview(voiceId: String?, text: String) {
        updateState { state ->
            state.copy(
                ttsPreviewingVoiceId = voiceId ?: TTS_SYSTEM_VOICE_KEY,
                isTtsPreviewPlaying = false,
            )
        }
        ttsController.previewVoice(voiceId, text)
    }

    private fun stopTtsPreview() {
        ttsController.stopPreview()
    }

    private fun observeTtsPreviewState() {
        ttsController.previewState
            .onEach { state ->
                when (state) {
                    TtsPreviewState.IDLE -> updateState { viewState ->
                        viewState.copy(
                            ttsPreviewingVoiceId = null,
                            isTtsPreviewPlaying = false,
                        )
                    }

                    TtsPreviewState.LOADING -> updateState { viewState ->
                        viewState.copy(isTtsPreviewPlaying = false)
                    }

                    TtsPreviewState.SPEAKING -> updateState { viewState ->
                        viewState.copy(isTtsPreviewPlaying = true)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeTtsVoicePreparationState() {
        ttsController.voicePreparationState
            .onEach { preparationState ->
                when (preparationState) {
                    TtsVoicePreparationState.Idle -> updateState { state ->
                        state.copy(
                            isTtsVoicePreparing = false,
                            preparingTtsVoicePackage = null,
                            ttsVoicePreparationProgress = null,
                        )
                    }

                    is TtsVoicePreparationState.Running -> updateState { state ->
                        state.copy(
                            isTtsVoicePreparing = true,
                            preparingTtsVoicePackage = preparationState.voicePackage,
                            ttsVoicePreparationProgress = preparationState.progress,
                            failedTtsVoicePackage = null,
                        )
                    }

                    is TtsVoicePreparationState.Complete -> {
                        val refreshedVoices = ttsController.availableVoices()
                        updateState { state ->
                            state.copy(
                                ttsVoices = refreshedVoices,
                                isTtsVoicePreparing = false,
                                preparingTtsVoicePackage = null,
                                ttsVoicePreparationProgress = null,
                                failedTtsVoicePackage = null,
                            )
                        }
                    }

                    is TtsVoicePreparationState.Failed -> updateState { state ->
                        state.copy(
                            isTtsVoicePreparing = false,
                            preparingTtsVoicePackage = null,
                            ttsVoicePreparationProgress = null,
                            failedTtsVoicePackage = preparationState.voicePackage,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun resolveConflictWithLocal() {
        val conflict = viewState.value.positionConflict ?: return
        viewModelScope.launch {
            updateState { it.copy(positionConflict = null) }
            bookController.goToPosition(conflict.localPosition)
        }
    }

    private fun resolveConflictWithRemote() {
        val conflict = viewState.value.positionConflict ?: return
        val currentState = viewState.value
        viewModelScope.launch {
            updateState {
                it.copy(
                    positionConflict = null,
                    currentAudioPositionMs = conflict.remotePosition.audioTimestampMs ?: 0L,
                )
            }
            bookController.goToPosition(conflict.remotePosition)
            // Also update the audio position if this is a ReadAloud book with actual media overlays
            if (currentState.isReadAloud) {
                audioController.setInitialAudioPosition(conflict.remotePosition.audioTimestampMs)
            }
        }
    }

    private fun updatePosition(position: PositionUiModel) {
        if (viewState.value.positionConflict != null) return

        updatePublicationState { it.copy(position = position) }

        val now = now().toString()
        val currentState = viewState.value
        val audioTimestamp = currentState.currentAudioPositionMs.takeIf { it > 0 }
        val positionDomainModel = PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = nowMillis(),
            createdAt = position.createdAt,
            updatedAt = now,
            locatorHref = position.href,
            locatorType = position.type,
            locatorTitle = position.title,
            locatorTarget = null,
            audioTimestampMs = audioTimestamp,
            chapterIndex = position.chapterIndex,
            progression = position.progression,
            totalChapters = position.totalChapters,
            totalDurationMs = currentState.totalDurationMs,
            totalProgression = position.totalProgression,
            position = position.position,
            cssSelector = position.cssSelector,
        )

        viewModelScope.launch {
            saveReadingProgressUseCase(positionDomainModel)
                .onSuccess { routineSyncScheduler.markDirty() }
        }
    }

    private fun updateSettings(settings: ReaderSettingsUiModel) {
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = settings.copy(
                    ttsVoiceId = publicationState.settings.ttsVoiceId,
                    ttsRate = publicationState.settings.ttsRate,
                    ttsPitch = publicationState.settings.ttsPitch,
                    ttsEnabled = publicationState.settings.ttsEnabled,
                ),
            )
        }
        launchReaderSettingsUpdate { latestSettings ->
            settings.toDomainModel().copy(
                ttsVoiceId = latestSettings.ttsVoiceId,
                ttsRate = latestSettings.ttsRate,
                ttsPitch = latestSettings.ttsPitch,
                ttsEnabled = latestSettings.ttsEnabled,
            )
        }
    }

    private fun toggleSettings() {
        val willBeVisible = !viewState.value.isSettingsVisible
        if (willBeVisible) {
            analytics.logEvent(ReaderAnalyticsEvent.SettingsOpened(bookUuid = bookUuid))
        }
        updateState { it.copy(isSettingsVisible = willBeVisible) }
    }

    private fun toggleToc() {
        val willBeVisible = !viewState.value.isTocVisible
        if (willBeVisible) {
            analytics.logEvent(ReaderAnalyticsEvent.TocOpened(bookUuid = bookUuid))
        }
        updateState { it.copy(isTocVisible = willBeVisible) }
    }

    private fun goToChapter(href: String, currentPosition: PositionUiModel?) {
        bookController.goToChapter(href)
        updateState {
            it.copy(
                isTocVisible = false,
                previousTocPosition = currentPosition,
            )
        }
    }

    private fun goToNextChapter() {
        analytics.logEvent(ReaderAnalyticsEvent.ChapterNavigated(bookUuid = bookUuid, direction = "next"))
        val state = viewState.value
        val chapters = state.tableOfContents
        val currentHref = state.currentPosition?.href
        val currentIndex = chapters.indexOfFirst { it.href == currentHref }
        val nextChapter = chapters.getOrNull(currentIndex + 1) ?: return
        goToChapter(nextChapter.href, state.currentPosition)
    }

    private fun goToPreviousChapter() {
        analytics.logEvent(ReaderAnalyticsEvent.ChapterNavigated(bookUuid = bookUuid, direction = "previous"))
        val state = viewState.value
        val chapters = state.tableOfContents
        val currentHref = state.currentPosition?.href
        val currentIndex = chapters.indexOfFirst { it.href == currentHref }
        val previousChapter = chapters.getOrNull(currentIndex - 1) ?: return
        goToChapter(previousChapter.href, state.currentPosition)
    }

    private fun goToChapterAndPlay(href: String, currentPosition: PositionUiModel?) {
        goToChapter(href, currentPosition)
        updatePublicationState { pubState ->
            pubState.copy(
                position = pubState.position?.copy(href = href, progression = 0.0),
            )
        }
        audioController.playFromFragment(fragmentId = "", chapterHref = href)
    }

    private fun goToNextChapterAndPlay() {
        val state = viewState.value
        val chapters = state.tableOfContents
        val currentHref = state.currentPosition?.href
        val currentIndex = chapters.indexOfFirst { it.href == currentHref }
        val nextChapter = chapters.getOrNull(currentIndex + 1) ?: return
        goToChapterAndPlay(nextChapter.href, state.currentPosition)
    }

    private fun goToPreviousChapterAndPlay() {
        val state = viewState.value
        val chapters = state.tableOfContents
        val currentHref = state.currentPosition?.href
        val currentIndex = chapters.indexOfFirst { it.href == currentHref }
        val previousChapter = chapters.getOrNull(currentIndex - 1) ?: return
        goToChapterAndPlay(previousChapter.href, state.currentPosition)
    }

    private fun undoChapterNavigation(position: PositionUiModel) {
        bookController.goToPosition(position)
        updateState { it.copy(previousTocPosition = null) }
    }

    private fun dismissChapterNavigationUndo() {
        updateState { it.copy(previousTocPosition = null) }
    }

    private fun toggleBookmarks() {
        val willBeVisible = !viewState.value.isBookmarksVisible
        if (willBeVisible) {
            analytics.logEvent(ReaderAnalyticsEvent.BookmarksOpened(bookUuid = bookUuid))
        }
        updateState { it.copy(isBookmarksVisible = willBeVisible) }
    }

    private fun addBookmark() {
        val currentPosition = viewState.value.currentPosition ?: return
        val existing = viewState.value.bookmarks.find { bookmark ->
            bookmark.locatorHref == currentPosition.href &&
                bookmark.position == currentPosition.position
        }
        if (existing != null) {
            updateState {
                it.copy(
                    isBookmarksVisible = false,
                    showBookmarkAlreadyExists = true,
                )
            }
            return
        }
        val nanoSuffix = bookmarkIdMark.elapsedNow().inWholeNanoseconds
        val bookmark = BookmarkDomainModel(
            id = "${bookUuid}_${nowMillis()}_$nanoSuffix",
            bookUuid = bookUuid,
            locatorHref = currentPosition.href,
            locatorType = currentPosition.type,
            locatorTitle = currentPosition.title,
            progression = currentPosition.progression,
            totalProgression = currentPosition.totalProgression,
            chapterIndex = currentPosition.chapterIndex,
            position = currentPosition.position,
            createdAt = now().toString(),
        )
        viewModelScope.launch {
            addBookmarkUseCase(bookmark)
                .onSuccess {
                    analytics.logEvent(ReaderAnalyticsEvent.BookmarkAdded(bookUuid = bookUuid))
                    updateState {
                        it.copy(
                            isBookmarksVisible = false,
                            showBookmarkAdded = true,
                            lastAddedBookmarkId = bookmark.id,
                        )
                    }
                }
                .onFailure { error ->
                    error.log(analytics, "ReaderViewModel: Failed to save bookmark")
                    updateState { it.copy(showBookmarkSaveFailed = true) }
                }
        }
    }

    private fun dismissBookmarkAdded() {
        updateState {
            it.copy(
                showBookmarkAdded = false,
                lastAddedBookmarkId = null,
            )
        }
    }

    private fun dismissBookmarkAlreadyExists() {
        updateState { it.copy(showBookmarkAlreadyExists = false) }
    }

    private fun undoBookmark(id: String) {
        viewModelScope.launch {
            deleteBookmarkUseCase(id)
        }
        updateState {
            it.copy(
                showBookmarkAdded = false,
                lastAddedBookmarkId = null,
            )
        }
    }

    private fun deleteBookmark(id: String) {
        analytics.logEvent(ReaderAnalyticsEvent.BookmarkDeleted(bookUuid = bookUuid))
        viewModelScope.launch {
            deleteBookmarkUseCase(id)
        }
    }

    private fun renameBookmark(id: String, newTitle: String) {
        analytics.logEvent(ReaderAnalyticsEvent.BookmarkRenamed(bookUuid = bookUuid))
        viewModelScope.launch {
            updateBookmarkTitleUseCase(id, newTitle)
        }
        updateState { it.copy(renamingBookmark = null) }
    }

    private fun reorderBookmarks(bookmarkIds: List<String>) {
        val orders = bookmarkIds.mapIndexed { index, id -> id to index }
        viewModelScope.launch {
            reorderBookmarksUseCase(orders)
        }
    }

    private fun goToPreviousBookmark() {
        val currentHref = viewState.value.currentPosition?.href ?: return
        val sorted = viewState.value.bookmarks.sortedBy { it.sortOrder }
        val currentIndex = sorted.indexOfFirst { it.locatorHref == currentHref }
        if (currentIndex <= 0) {
            updateState { it.copy(showNoMoreBookmarks = true) }
            return
        }
        goToBookmark(sorted[currentIndex - 1])
    }

    private fun goToNextBookmark() {
        val currentHref = viewState.value.currentPosition?.href ?: return
        val sorted = viewState.value.bookmarks.sortedBy { it.sortOrder }
        val currentIndex = sorted.indexOfFirst { it.locatorHref == currentHref }
        if (currentIndex < 0 || currentIndex >= sorted.size - 1) {
            updateState { it.copy(showNoMoreBookmarks = true) }
            return
        }
        goToBookmark(sorted[currentIndex + 1])
    }

    private fun dismissNoMoreBookmarks() {
        updateState { it.copy(showNoMoreBookmarks = false) }
    }

    private fun goToBookmark(bookmark: BookmarkUiModel) {
        val position = PositionUiModel(
            createdAt = bookmark.createdAt,
            href = bookmark.locatorHref,
            type = bookmark.locatorType ?: "",
            title = bookmark.locatorTitle,
            progression = bookmark.progression,
            position = bookmark.position,
            totalProgression = bookmark.totalProgression,
            chapterIndex = bookmark.chapterIndex,
            totalChapters = viewState.value.currentPosition?.totalChapters,
        )
        bookController.goToPosition(position)
        updateState { it.copy(isBookmarksVisible = false) }
    }

    private fun observeBookmarks() {
        observeBookmarksUseCase(bookUuid)
            .onEach { bookmarks ->
                updateState { state ->
                    state.copy(bookmarks = bookmarks.map { bookmark -> bookmark.toUiModel() })
                }
            }
            .launchIn(viewModelScope)
    }

    fun close() {
        // Leaving the reader must never wait on persistence or the network. The toolbar
        // arrow is the only visible way out and blocking here made it look dead. The work
        // runs on a NonCancellable coroutine so it still completes after navigation clears
        // this ViewModel (same pattern as AudiobookPlayerViewModel.close()); the sync
        // outbox holds the reading position even if the checkpoint below never runs.
        if (hasRequestedClose) return
        hasRequestedClose = true
        currentBookTargetCheckpoint?.cancel()
        reportLastBookLaunchOutcome(
            outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Cancelled,
            stage = "terminal",
            reasonCode = "closed_before_content",
        )

        viewModelScope.launch(NonCancellable) {
            // Only save audio position if this is a ReadAloud book with actual media overlays
            if (viewState.value.isReadAloud) {
                saveCurrentAudioPositionSync()
            }
            routineSyncScheduler.close()
            cancelSleepTimer()

            // Track book closed event with reading duration and progress
            val currentState = viewState.value
            val endTime = nowMillis()
            val readingDurationMs = if (bookOpenedTimestamp > 0) {
                endTime - bookOpenedTimestamp
            } else {
                0L
            }
            val progressPercent = currentState.currentPosition?.totalProgression
                ?.let { (it * 100).toInt() } ?: 0
            val sessionReadingSpeedWpm = readingSpeedTracker.establishedReadingSpeedWpm.value
                ?: currentState.currentSettings?.readingSpeedWpm
                ?: 250

            analytics.logEvent(
                ReaderAnalyticsEvent.BookClosed(
                    bookUuid = bookUuid,
                    readingDurationMs = readingDurationMs,
                    progressPercent = progressPercent,
                )
            )

            // Save reading session for statistics (only if we have a valid session)
            if (bookOpenedTimestamp > 0 && readingDurationMs > 0 && currentState.bookTitle.isNotEmpty()) {
                saveReadingSessionUseCase(
                    bookUuid = bookUuid,
                    bookTitle = currentState.bookTitle,
                    bookType = currentState.bookType,
                    startTime = bookOpenedTimestamp,
                    endTime = endTime,
                    durationMs = readingDurationMs,
                    endProgression = currentState.currentPosition?.totalProgression,
                    readingSpeedWpm = sessionReadingSpeedWpm,
                )
            }

            // Update currently reading book if session was long enough (≥ 1 minute)
            if (
                readingDurationMs >= MINIMUM_READING_DURATION_MS &&
                currentState.bookTitle.isNotEmpty() &&
                !currentBookTargetSaveState.hasSucceeded
            ) {
                persistCurrentBookTarget(entryPoint = "reader_close")
            }

            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.READER_CHECKPOINT,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.URGENT,
                ),
            )
        }

        onClose()
    }

    /** Toggles the active ReadAloud or TTS narration implementation. */
    private fun togglePlayback() {
        if (viewState.value.isTtsReadAloud) {
            val selectedVoice = viewState.value.ttsVoices
                .firstOrNull { voice -> voice.id == viewState.value.selectedTtsVoiceId }
            if (selectedVoice?.needsDownload == true) {
                openVoiceSettings()
                return
            }
        }
        activeNarrationController?.togglePlayback()
    }

    private fun toggleAudioOnlyMode() {
        val isTurningOff = viewState.value.isAudioOnlyMode
        analytics.logEvent(
            ReaderAnalyticsEvent.AudioOnlyModeToggled(
                bookUuid = bookUuid,
                isEnabled = !isTurningOff,
            ),
        )
        updateState { it.copy(isAudioOnlyMode = !it.isAudioOnlyMode) }
        if (isTurningOff) {
            audioController.currentAudioLocator.value?.locator?.let { locator ->
                bookController.goToLocator(locator)
            }
        }
    }

    private fun seekTo(audioTimestampMs: Long) {
        audioController.seekToAudioPosition(audioTimestampMs)
        updateState { it.copy(currentAudioPositionMs = audioTimestampMs) }
    }

    private fun setPlaybackSpeed(speed: Float) {
        analytics.logEvent(
            ReaderAnalyticsEvent.SettingChanged("playback_speed", speed.toString())
        )
        val isTts = viewState.value.isTtsReadAloud
        activeNarrationController?.setPlaybackSpeed(speed)
        updatePublicationState { publicationState ->
            val updatedSettings = if (isTts) {
                publicationState.settings.copy(ttsRate = speed)
            } else {
                publicationState.settings.copy(playbackSpeed = speed)
            }
            publicationState.copy(settings = updatedSettings)
        }
        launchReaderSettingsUpdate { settings ->
            if (isTts) {
                settings.copy(ttsRate = speed)
            } else {
                settings.copy(playbackSpeed = speed)
            }
        }
    }

    private fun startSleepTimer(durationMs: Long) {
        analytics.logEvent(
            ReaderAnalyticsEvent.SleepTimerStarted(
                bookUuid = bookUuid,
                durationMs = durationMs,
            ),
        )
        sleepTimerJob?.cancel()
        updateState {
            it.copy(
                sleepTimerRemainingMs = durationMs,
                showSleepTimerWarningPrompt = false,
            )
        }
        sleepTimerJob = viewModelScope.launch {
            var remainingMs = durationMs
            var hasShownWarning = false
            while (remainingMs > 0L) {
                delay(SLEEP_TIMER_TICK_MS)
                if (viewState.value.isPlaying) {
                    remainingMs = (remainingMs - SLEEP_TIMER_TICK_MS).coerceAtLeast(0L)
                    updateState {
                        it.copy(
                            sleepTimerRemainingMs = remainingMs,
                            showSleepTimerWarningPrompt = if (
                                !hasShownWarning &&
                                remainingMs in 1L..SLEEP_TIMER_WARNING_THRESHOLD_MS
                            ) {
                                true
                            } else {
                                it.showSleepTimerWarningPrompt
                            },
                        )
                    }
                    if (remainingMs in 1L..SLEEP_TIMER_WARNING_THRESHOLD_MS) {
                        hasShownWarning = true
                    }
                }
            }
            if (viewState.value.isPlaying) {
                togglePlayback()
                saveCurrentAudioPosition()
            }
            updateState {
                it.copy(
                    sleepTimerRemainingMs = null,
                    showSleepTimerWarningPrompt = false,
                )
            }
            sleepTimerJob = null
        }
    }

    private fun cancelSleepTimer() {
        analytics.logEvent(ReaderAnalyticsEvent.SleepTimerCancelled(bookUuid = bookUuid))
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        updateState {
            it.copy(
                sleepTimerRemainingMs = null,
                showSleepTimerWarningPrompt = false,
            )
        }
    }

    private fun dismissSleepTimerWarning() {
        updateState { it.copy(showSleepTimerWarningPrompt = false) }
    }

    private fun setHighlightColor(colorArgb: Int) {
        analytics.logEvent(
            ReaderAnalyticsEvent.HighlightColorChanged(
                bookUuid = bookUuid,
                colorArgb = colorArgb,
            ),
        )
        updatePublicationState { publicationState ->
            publicationState.copy(
                settings = publicationState.settings.copy(highlightColor = colorArgb),
            )
        }
        launchReaderSettingsUpdate { settings -> settings.copy(highlightColor = colorArgb) }
    }

    private fun launchReaderSettingsUpdate(
        update: (ReaderSettingsDomainModel) -> ReaderSettingsDomainModel,
    ) {
        viewModelScope.launch {
            saveReaderSettingsUpdate(update)
        }
    }

    private suspend fun saveReaderSettingsUpdate(
        update: (ReaderSettingsDomainModel) -> ReaderSettingsDomainModel,
    ) {
        readerSettingsSaveMutex.withLock {
            val currentSettings = getReaderSettingsUseCase().first()
            val updatedSettings = update(currentSettings)
            if (updatedSettings != currentSettings) {
                saveReaderSettingsUseCase(updatedSettings)
            }
        }
    }

    /** Skips forward by 10 seconds for ReadAloud or by one sentence for TTS. */
    @Suppress("UNUSED_PARAMETER")
    private fun skipForward(milliseconds: Long) {
        analytics.logEvent(ReaderAnalyticsEvent.SkipForward(bookUuid = bookUuid))
        activeNarrationController?.skipForward()
    }

    /** Skips backward by 10 seconds for ReadAloud or by one sentence for TTS. */
    @Suppress("UNUSED_PARAMETER")
    private fun skipBackward(milliseconds: Long) {
        analytics.logEvent(ReaderAnalyticsEvent.SkipBackward(bookUuid = bookUuid))
        activeNarrationController?.skipBackward()
    }

    /**
     * Updates the current audio position from the navigator.
     * Called by the View when the navigator reports position changes.
     * Position is only updated if positionMs is not null (null means position not yet known).
     * Duration is always updated if available.
     */
    private fun updateAudioPosition(positionMs: Long?, totalDurationMs: Long?) {
        updateState {
            it.copy(
                currentAudioPositionMs = positionMs ?: it.currentAudioPositionMs,
                totalDurationMs = totalDurationMs ?: it.totalDurationMs,
            )
        }
    }

    /**
     * Updates the playing state from the navigator.
     * Called when the audio controller reports playback state changes.
     */
    private fun updatePlayingState(isPlaying: Boolean) {
        if (isPlaying && !wasPlaying) {
            analytics.logEvent(ReaderAnalyticsEvent.PlaybackStarted(bookUuid = bookUuid))
        } else if (!isPlaying && wasPlaying) {
            analytics.logEvent(ReaderAnalyticsEvent.PlaybackPaused(bookUuid = bookUuid))
        }
        wasPlaying = isPlaying

        updateState { it.copy(isPlaying = isPlaying) }
        if (!isPlaying) {
            saveCurrentAudioPosition()
        }
    }

    private fun observeNarrationPlaybackState(controller: NarrationController) {
        controller.isPlaying
            .distinctUntilChanged()
            .onEach { isPlaying -> updatePlayingState(isPlaying) }
            .launchIn(viewModelScope)

        controller.isLoading
            .distinctUntilChanged()
            .onEach { isLoading ->
                updateState { state -> state.copy(isNarrationLoading = isLoading) }
            }
            .launchIn(viewModelScope)

        controller.isPlaybackStartPending
            .distinctUntilChanged()
            .onEach { isPending ->
                updateState { state -> state.copy(isNarrationStartPending = isPending) }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Saves the current audio position to persistence.
     * This is called when playback is paused.
     */
    private fun saveCurrentAudioPosition() {
        viewModelScope.launch {
            saveCurrentAudioPositionSync()
        }
    }

    /**
     * Saves the current audio position to persistence synchronously.
     * This is called when the reader is closed to ensure the position is saved
     * before navigation occurs.
     */
    private suspend fun saveCurrentAudioPositionSync() {
        val currentState = viewState.value
        val audioPositionMs = currentState.currentAudioPositionMs
        val currentPosition = currentState.currentPosition

        if (audioPositionMs <= 0) {
            return
        }
        if (currentPosition == null) {
            return
        }


        val now = now().toString()
        val positionDomainModel = PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = nowMillis(),
            createdAt = currentPosition.createdAt,
            updatedAt = now,
            locatorHref = currentPosition.href,
            locatorType = currentPosition.type,
            locatorTitle = currentPosition.title,
            locatorTarget = null,
            audioTimestampMs = audioPositionMs,
            chapterIndex = currentPosition.chapterIndex,
            progression = currentPosition.progression,
            totalChapters = currentPosition.totalChapters,
            totalDurationMs = currentState.totalDurationMs,
            totalProgression = currentPosition.totalProgression,
            position = currentPosition.position,
        )
        saveReadingProgressUseCase(positionDomainModel)
            .onSuccess { routineSyncScheduler.markDirty() }
    }

    override fun onCleared() {
        currentBookTargetCheckpoint?.cancel()
        routineSyncScheduler.close()
        super.onCleared()
        readerScope.close()
    }

    private fun scheduleCurrentBookTargetCheckpoint() {
        if (hasRequestedClose || bookOpenedTimestamp <= 0L) return

        currentBookTargetCheckpoint?.cancel()
        currentBookTargetCheckpoint = CurrentBookTargetCheckpoint(
            scope = viewModelScope,
            delayMillis = MINIMUM_READING_DURATION_MS,
            onCheckpointDue = {
                if (!hasRequestedClose) {
                    persistCurrentBookTarget(entryPoint = "reading_duration_threshold")
                }
            },
        ).also(CurrentBookTargetCheckpoint::start)
    }

    private fun persistCurrentBookTarget(entryPoint: String): Boolean {
        val currentState = viewState.value
        if (bookOpenedTimestamp <= 0L || currentState.bookTitle.isEmpty()) return false

        val succeeded = persistCurrentBookTarget(
            analytics = analytics,
            target = CurrentlyReadingDomainModel(
                serverId = serverId,
                bookUuid = bookUuid,
                bookType = currentState.bookType,
                bookTitle = currentState.bookTitle,
                coverUrl = currentState.bookCoverUrl,
                totalProgression = currentState.currentPosition?.totalProgression,
            ),
            entryPoint = entryPoint,
            isRetry = currentBookTargetSaveState.isRetry,
            persist = setCurrentlyReadingUseCase::invoke,
        )
        currentBookTargetSaveState.recordResult(succeeded)
        return succeeded
    }

    private companion object {
        /** Delay before refreshing chapter page info to allow WebView to re-layout */
        private const val CHAPTER_PAGE_INFO_REFRESH_DELAY_MS = 300L

        /** Interval for updating the current time display (1 minute) */
        private const val TIME_UPDATE_INTERVAL_MS = 60_000L

        /** Minimum reading duration to update "currently reading" book (1 minute) */
        private const val MINIMUM_READING_DURATION_MS = 60_000L

        private const val SLEEP_TIMER_TICK_MS = 1_000L

        private const val SLEEP_TIMER_WARNING_THRESHOLD_MS = 60_000L
    }
}
