package com.retro99.reader.ui.reader

import androidx.lifecycle.viewModelScope
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ContinueReadingOpenOperation
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.NavigationAnalyticsEvent
import com.retro99.analytics.api.NavigationAnalyticsEvent.ContinueReadingOpenOutcome
import com.retro99.analytics.api.NavigationAnalyticsEvent.ContinueReadingOpenReasonCode
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.analytics.api.beginContinueReadingOpen
import com.retro99.analytics.api.completeContinueReadingOpen
import com.retro99.analytics.api.continueReadingOpenOperation as createContinueReadingOpenOperation
import com.retro99.analytics.api.diagnosticContext
import com.retro99.base.formatCurrentTime
import com.retro99.base.now
import com.retro99.base.nowMillis
import com.retro99.base.result.AppError
import com.retro99.base.result.log
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.BookmarkDomainModel
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderInitializationData
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.usecase.AddBookmarkUseCase
import com.retro99.reader.domain.usecase.DeleteBookmarkUseCase
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.FindLinkedResumeUseCase
import com.retro99.reader.domain.usecase.InitializeReaderUseCase
import com.retro99.reader.domain.usecase.ObserveAppliedPositionUseCase
import com.retro99.reader.domain.usecase.PropagateToLinkedCopiesUseCase
import com.retro99.reader.domain.usecase.ObserveBookmarksUseCase
import com.retro99.reader.domain.usecase.ReorderBookmarksUseCase
import com.retro99.reader.domain.usecase.ResolveLinkedResumeUseCase
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
import com.retro99.reader.ui.navigator.TtsPlaybackOperation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatform.getKoin
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@KoinViewModel
class ReaderViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val bookType: BookType,
    @InjectedParam private val isLastBookOnLaunch: Boolean,
    @InjectedParam private val onClose: (ReaderCloseSource) -> Unit,
    @InjectedParam private val onSettingsClick: () -> Unit,
    @InjectedParam private val readerOpenEntryPoint: String?,
    @InjectedParam private val readerOpenCorrelationId: String?,
    @InjectedParam private val linkedResumeResolved: Boolean,
    @InjectedParam private val onComparePositions: () -> Unit,
    @Provided private val initializeReaderUseCase: InitializeReaderUseCase,
    @Provided private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val findLinkedResumeUseCase: FindLinkedResumeUseCase,
    @Provided private val resolveLinkedResumeUseCase: ResolveLinkedResumeUseCase,
    @Provided private val observeAppliedPositionUseCase: ObserveAppliedPositionUseCase,
    @Provided private val propagateToLinkedCopiesUseCase: PropagateToLinkedCopiesUseCase,
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
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
) : BaseViewModel<ReaderViewState, ReaderIntent>(
    ReaderViewState(
        bookUuid = bookUuid,
        bookType = bookType,
        hasAcceptedSupertonicTerms = supertonicTermsStore.hasAcceptedCurrentTerms(),
    )
) {

    private var continueReadingOpenOperation = createContinueReadingOpenOperation(
        entryPoint = readerOpenEntryPoint,
        mediaType = bookType.value,
        correlationId = readerOpenCorrelationId,
    )
    private var continueReadingOpenResolved = false

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

    /** Element id of the sentence the device voice last read; used to hand over to narration. */
    private var ttsCurrentElementId: String? = null

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

    /** Job for the current EPUB text search. */
    private var bookSearchJob: Job? = null
    private var bookSearchGeneration = 0L
    private var searchAccent = 0
    private var searchSoft = 0
    private var searchOnAccent = 0
    private var searchEink = false
    private var searchBoundaries: List<SearchChapterBoundary>? = null

    /** Job for downloading and loading the selected neural voice model. */
    private var ttsPreparationJob: Job? = null

    /** Job for preparing sentence elements and enabling double-tap TTS playback. */
    private var ttsSentencePlaybackJob: Job? = null
    private var isObservingTtsPlaybackOperations = false

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

    /** Attribution follows the active last-book launch attempt, including an explicit Reader retry. */
    private var lastBookLaunchSourceScreen: String = "home"
    private var lastBookLaunchEntryPoint: String = "app_launch"

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

    private var positionSaveFailureCorrelationId: String? = null
    private var positionSaveFailureReported = false
    private var positionSaveAttemptReported = false
    private var positionSaveSuccessReported = false

    private val positionSaveCoordinator: ReaderPositionSaveCoordinator<PositionDomainModel> by lazy {
        ReaderPositionSaveCoordinator(
            scope = viewModelScope,
            save = { position -> saveReadingProgressUseCase(position) },
            onAttempted = ::reportPositionSaveAttempt,
            onSucceeded = ::onPositionSaveSucceeded,
            onFailed = ::onPositionSaveFailed,
            onCancelled = ::onPositionSaveCancelled,
        )
    }

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
            ReaderIntent.ToggleBookSearch -> toggleBookSearch()
            is ReaderIntent.SearchBook -> searchBook(intent.query, intent.submitOnly)
            ReaderIntent.SubmitBookSearch -> searchBook(viewState.value.bookSearchQuery, submitted = true)
            ReaderIntent.ClearBookSearchRecents -> {
                saveUserPreferenceUseCase(PreferencesKey.RecentBookSearches(serverId, bookUuid), emptyList<RecentBookSearch>())
                updateState { it.copy(bookSearchRecents = emptyList()) }
            }
            ReaderIntent.CloseBookSearch -> closeBookSearch()
            ReaderIntent.PreviousSearchResult -> stepSearchResult(-1)
            ReaderIntent.NextSearchResult -> stepSearchResult(1)
            ReaderIntent.ReturnToSearchOrigin -> viewState.value.searchOrigin?.let { origin ->
                navigateKeepingAudio { bookController.goToPosition(origin); closeBookSearch() }
            }
            ReaderIntent.ToggleFindBar -> updateState { it.copy(isFindBarVisible = !it.isFindBarVisible) }
            is ReaderIntent.UpdateSearchDecorations -> {
                searchAccent = intent.accent; searchSoft = intent.soft; searchOnAccent = intent.onAccent; searchEink = intent.eink
                refreshSearchDecorations()
            }
            is ReaderIntent.GoToSearchResult -> navigateKeepingAudio(searchResult = intent.result) { goToSearchResult(intent.result) }
            is ReaderIntent.SeekToChapterProgress -> seekToChapterProgress(intent.progression)
            is ReaderIntent.JumpToBookProgress -> navigateKeepingAudio {
                jumpToBookProgress(intent.progression)
            }
            is ReaderIntent.StartListening -> startListening(intent.source, intent.autoPlay)
            is ReaderIntent.SwitchListenSource -> switchListenSource(intent.source)
            ReaderIntent.ToggleListenSheet -> toggleListenSheet()
            ReaderIntent.StopListening -> stopListening()
            is ReaderIntent.UpdateSettings -> updateSettings(intent.settings)
            ReaderIntent.ToggleSettings -> toggleSettings()
            ReaderIntent.Close -> close()
            ReaderIntent.OnSettingsClicked -> onSettingsClick()
            ReaderIntent.UseLocalPosition -> resolveConflictWithLocal()
            ReaderIntent.UseRemotePosition -> resolveConflictWithRemote()
            ReaderIntent.ContinueLinkedResume -> continueLinkedResume()
            ReaderIntent.StayLinkedResume -> stayLinkedResume()
            ReaderIntent.CompareLinkedPositions -> {
                // Comparing isn't an answer: nothing is recorded.
                updateState { state -> state.copy(linkedResumeOffer = null) }
                onComparePositions()
            }
            ReaderIntent.GoToNextPage -> goToNextPage()
            ReaderIntent.GoToPreviousPage -> goToPreviousPage()
            ReaderIntent.TogglePlayback -> togglePlayback()
            is ReaderIntent.SelectTtsVoice -> selectTtsVoice(intent.voiceId)
            is ReaderIntent.DownloadNeuralVoicePackage -> {
                downloadNeuralVoicePackage(intent.voicePackage)
            }

            is ReaderIntent.UpdateNeuralVoicePackage -> {
                updateNeuralVoicePackage(intent.voicePackage)
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
            ReaderIntent.CancelTtsVoicePreparation -> cancelTtsVoicePreparation()
            is ReaderIntent.AcceptSupertonicTermsAndSelect -> {
                supertonicTermsStore.acceptCurrentTerms()
                updateState { state -> state.copy(hasAcceptedSupertonicTerms = true) }
                selectTtsVoice(intent.voiceId)
            }
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
            is ReaderIntent.GoToChapter -> navigateKeepingAudio {
                goToChapter(intent.href, intent.currentPosition)
            }
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
            ReaderIntent.RetryTtsPlayback -> retryTtsPlayback()
            ReaderIntent.DismissTtsPlaybackFailed -> dismissTtsPlaybackFailed()
            ReaderIntent.DismissBookmarkSaveFailed -> dismissBookmarkSaveFailed()
            ReaderIntent.RetryPositionSave -> retryPositionSave()
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
            is ReaderIntent.GoToBookmark -> navigateKeepingAudio { goToBookmark(intent.bookmark) }
        }
    }

    private fun dismissNoAudioMessage() {
        updateState { it.copy(showNoAudioMessage = false) }
    }

    private fun dismissTtsPlaybackFailed() {
        updateState { it.copy(showTtsPlaybackFailed = false) }
    }

    private fun retryTtsPlayback() {
        updateState { it.copy(showTtsPlaybackFailed = false) }
        togglePlayback()
    }

    private fun dismissBookmarkSaveFailed() {
        updateState { it.copy(showBookmarkSaveFailed = false) }
    }

    private fun retryPositionSave() {
        if (positionSaveCoordinator.retry()) {
            updateState { it.copy(showPositionSaveFailed = false) }
        }
    }

    private fun retry() {
        updateState { it.copy(error = null) }
        beginContinueReadingOpenRetry()
        if (isLastBookOnLaunch) {
            lastBookLaunchSourceScreen = "reader"
            lastBookLaunchEntryPoint = "reader_retry"
            lastBookLaunchOutcomeGate.beginAttempt()
            analytics.logEvent(
                NavigationAnalyticsEvent.LastBookLaunchAttempted(
                    bookType = bookType.name.lowercase(),
                    stage = "retry",
                    screen = "reader",
                    sourceScreen = lastBookLaunchSourceScreen,
                    entryPoint = lastBookLaunchEntryPoint,
                ),
            )
            analytics.logBreadcrumb(
                DiagnosticContext(
                    screen = "reader",
                    sourceScreen = lastBookLaunchSourceScreen,
                    entryPoint = lastBookLaunchEntryPoint,
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
        searchPageTurned()
        bookController.goToNextPage()
    }

    private fun goToPreviousPage() {
        searchPageTurned()
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
                    !viewState.value.isReadAloud &&
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
                refreshSearchDecorations()
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
                    completeContinueReadingOpenFailure(error, stage = "initialization")
                    if (!isContinueReadingCancellation(error)) {
                        analytics.logEvent(
                            ReaderAnalyticsEvent.BookOpenFailed(
                                bookUuid = bookUuid,
                                bookType = bookType.name,
                                errorMessage = error.message ?: "Unknown reader initialization error",
                            ),
                        )
                    }
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
        // Checked while the publication opens; it waits at most 2 seconds for servers.
        val startupPrompt = viewModelScope.async {
            readerStartupPrompt(linkedResumeResolved, conflict, ::findLinkedResume)
        }

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

            // One prompt at most: a newer linked copy replaces the same-copy conflict, and
            // book detail's answer (linkedResumeResolved) replaces both.
            val prompt = startupPrompt.await()

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
                    bookAuthor = data.bookAuthor,
                    bookCoverUrl = data.bookCoverUrl,
                    publicationState = publicationState,
                    bookType = bookType,
                    positionConflict = prompt.positionConflict,
                    linkedResumeOffer = prompt.linkedResumeOffer,
                    error = null,
                    currentAudioPositionMs = position?.audioTimestampMs ?: 0L,
                    tableOfContents = publication.tableOfContents,
                    bookLanguage = publication.language,
                )
            }
            completeContinueReadingOpen(outcome = ContinueReadingOpenOutcome.Succeeded)
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
            observeAppliedPositions()
            observeReadingTimeInfo()
            observeReadingSpeedPersistence()
            observeSettingsChanges()
            observeCustomFontChanges()
            observeBookmarks()
            // Initialize audio after publication is in state
            if (publication.hasMediaOverlays) {
                initAudio()
                // Device voice is offered alongside narration; narration stays the default.
                initTts(keepNarrationActive = true)
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
            completeContinueReadingOpenFailure(error, stage = "publication")
            if (!isContinueReadingCancellation(error)) {
                analytics.logEvent(
                    ReaderAnalyticsEvent.BookOpenFailed(
                        bookUuid = data.bookUuid,
                        bookType = bookType.name,
                        errorMessage = error.message ?: "Unknown publication error",
                    )
                )
            }
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
        val isContinueReadingCancellation = isContinueReadingCancellation(error)
        val resolvedReasonCode = when {
            isContinueReadingCancellation -> ContinueReadingOpenReasonCode.ReaderOpenCancelled.value
            error is AppError.AuthError && stage == "initialization" -> "server_not_authenticated"
            else -> reasonCode
        }
        val context = DiagnosticContext(
            screen = "reader",
            sourceScreen = when {
                continueReadingOpenOperation != null -> "home"
                isLastBookOnLaunch -> lastBookLaunchSourceScreen
                else -> null
            },
            entryPoint = continueReadingOpenOperation?.entryPoint?.value
                ?: lastBookLaunchEntryPoint.takeIf { isLastBookOnLaunch },
            action = when {
                continueReadingOpenOperation != null -> "open_continue_reading"
                isLastBookOnLaunch -> "open_last_book"
                else -> "open_book"
            },
            operation = if (continueReadingOpenOperation != null) "continue_reading_open" else "reader_open",
            stage = stage,
            outcome = if (isContinueReadingCancellation) "cancelled" else "failed",
            reasonCode = resolvedReasonCode,
            mediaType = bookType.name.lowercase(),
            correlationId = continueReadingOpenOperation?.correlationId,
        )
        if (isLastBookOnLaunch) {
            reportLastBookLaunchOutcome(
                outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Failed,
                stage = "terminal",
                reasonCode = resolvedReasonCode,
                error = error,
            )
        } else if (error.shouldReportException) {
            error.log(analytics, context)
        } else {
            analytics.logBreadcrumb(context)
        }
    }

    private fun isContinueReadingCancellation(error: AppError): Boolean =
        continueReadingOpenOperation != null && error is AppError.AuthError && error.isCancellation

    private fun beginContinueReadingOpenRetry() {
        val previous = continueReadingOpenOperation ?: return
        continueReadingOpenOperation = analytics.beginContinueReadingOpen(
            entryPoint = previous.entryPoint,
            mediaType = previous.mediaType,
            isRetry = true,
        )
        continueReadingOpenResolved = false
    }

    private fun completeContinueReadingOpenFailure(error: AppError, stage: String) {
        val outcome = if (error is AppError.AuthError && error.isCancellation) {
            ContinueReadingOpenOutcome.Cancelled
        } else {
            ContinueReadingOpenOutcome.Failed
        }
        completeContinueReadingOpen(
            outcome = outcome,
            reasonCode = continueReadingOpenFailureReason(error, stage),
        )
    }

    private fun completeContinueReadingOpen(
        outcome: ContinueReadingOpenOutcome,
        reasonCode: ContinueReadingOpenReasonCode? = null,
    ) {
        val operation = continueReadingOpenOperation ?: return
        if (continueReadingOpenResolved) return
        analytics.completeContinueReadingOpen(operation, outcome, reasonCode)
        continueReadingOpenResolved = true
    }

    private fun continueReadingOpenFailureReason(
        error: AppError,
        stage: String,
    ): ContinueReadingOpenReasonCode = when {
        error is AppError.AuthError && error.isCancellation -> ContinueReadingOpenReasonCode.ReaderOpenCancelled
        error is AppError.AuthError && stage == "initialization" -> ContinueReadingOpenReasonCode.ServerNotAuthenticated
        error is AppError.AuthError -> ContinueReadingOpenReasonCode.PublicationOpenFailed
        error is AppError.NetworkError && error.isConnectivity -> ContinueReadingOpenReasonCode.NetworkUnavailable
        error is AppError.NetworkError && error.isTimeout -> ContinueReadingOpenReasonCode.RequestTimeout
        error is AppError.NetworkError && stage == "initialization" -> ContinueReadingOpenReasonCode.ReaderInitializationFailed
        error is AppError.NetworkError -> ContinueReadingOpenReasonCode.PublicationOpenFailed
        stage == "initialization" -> ContinueReadingOpenReasonCode.ReaderInitializationFailed
        else -> ContinueReadingOpenReasonCode.PublicationOpenFailed
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
                sourceScreen = lastBookLaunchSourceScreen,
                entryPoint = lastBookLaunchEntryPoint,
            ),
        )
        val context = DiagnosticContext(
            screen = "reader",
            sourceScreen = lastBookLaunchSourceScreen,
            entryPoint = lastBookLaunchEntryPoint,
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

    private fun initTts(keepNarrationActive: Boolean = false) {
        observeTtsPlaybackOperations()
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
            if (!keepNarrationActive) activeNarrationController = ttsController
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
            observeTtsSentenceProgress()
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
            if (settings.ttsEnabled && !keepNarrationActive) {
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

    private fun observeTtsPlaybackOperations() {
        if (isObservingTtsPlaybackOperations) return
        isObservingTtsPlaybackOperations = true
        ttsController.playbackOperations
            .onEach { operation ->
                val action = operation.action.analyticsValue
                val outcome = when (operation) {
                    is TtsPlaybackOperation.Attempted -> "attempted"
                    is TtsPlaybackOperation.Succeeded -> "succeeded"
                    is TtsPlaybackOperation.Failed -> "failed"
                    is TtsPlaybackOperation.Cancelled -> "cancelled"
                }
                val stage = if (operation is TtsPlaybackOperation.Attempted) "start" else "terminal"
                val durationMs = when (operation) {
                    is TtsPlaybackOperation.Attempted -> null
                    is TtsPlaybackOperation.Succeeded -> operation.durationMs
                    is TtsPlaybackOperation.Failed -> operation.durationMs
                    is TtsPlaybackOperation.Cancelled -> operation.durationMs
                }
                val reasonCode = when (operation) {
                    is TtsPlaybackOperation.Failed -> operation.reasonCode.analyticsValue
                    is TtsPlaybackOperation.Cancelled -> operation.reasonCode.analyticsValue
                    else -> null
                }
                analytics.logEvent(
                    ReaderAnalyticsEvent.TtsPlaybackOperation(
                        action = action,
                        outcome = outcome,
                        isRetry = operation.isRetry,
                        durationMs = durationMs,
                        reasonCode = reasonCode,
                    ),
                )
                val context = DiagnosticContext(
                    screen = "reader",
                    sourceScreen = "reader",
                    entryPoint = action,
                    action = "start_tts_playback",
                    operation = "tts_playback",
                    stage = stage,
                    outcome = outcome,
                    reasonCode = reasonCode,
                    mediaType = "ebook",
                    correlationId = operation.correlationId,
                )
                when (operation) {
                    is TtsPlaybackOperation.Attempted -> {
                        updateState { it.copy(showTtsPlaybackFailed = false) }
                        analytics.logBreadcrumb(context)
                    }
                    is TtsPlaybackOperation.Succeeded -> analytics.logBreadcrumb(context)
                    is TtsPlaybackOperation.Failed -> {
                        if (operation.error != null) {
                            analytics.logException(operation.error, context)
                        } else {
                            analytics.logBreadcrumb(context)
                        }
                        updateState { it.copy(showTtsPlaybackFailed = true) }
                    }
                    is TtsPlaybackOperation.Cancelled -> analytics.logBreadcrumb(context)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun selectTtsVoice(voiceId: String?) {
        val selectedVoice = viewState.value.ttsVoices
            .firstOrNull { voice -> voice.id == voiceId }
        if (
            selectedVoice?.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC &&
            !currentViewState().hasAcceptedSupertonicTerms
        ) {
            return
        }
        if (selectedVoice?.needsDownload == true) {
            // Picking a voice that is not downloaded fetches its package, then selects it.
            updateState { state -> state.copy(pendingTtsVoiceId = selectedVoice.id) }
            prepareTtsVoice(selectedVoice.id, onPrepared = { selectTtsVoice(selectedVoice.id) })
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
                pendingTtsVoiceId = null,
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

    private fun cancelTtsVoicePreparation() {
        updateState { state ->
            state.copy(pendingTtsVoiceId = null, failedTtsVoicePackage = null)
        }
        ttsPreparationJob?.cancel()
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

    private fun updateNeuralVoicePackage(voicePackage: NeuralVoicePackage) {
        val voiceId = currentViewState().ttsVoices
            .firstOrNull { voice -> voice.neuralVoicePackage == voicePackage }
            ?.id
            ?: return
        prepareTtsVoice(voiceId, updateToLatest = true)
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
        val pendingVoiceId = currentViewState().pendingTtsVoiceId
            ?.takeIf { pending -> pending.neuralVoicePackage() == voicePackage }
        prepareTtsVoice(
            voiceId,
            onPrepared = pendingVoiceId?.let { pending -> { selectTtsVoice(pending) } },
        )
    }

    private fun prepareTtsVoice(
        voiceId: String,
        onPrepared: (() -> Unit)? = null,
        updateToLatest: Boolean = false,
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
                if (voice.needsDownload || updateToLatest) {
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
                val isPrepared = ttsController.prepareVoice(voiceId, updateToLatest) { progress ->
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
                    pendingTtsVoiceId = null,
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

    private fun observeTtsSentenceProgress() {
        ttsController.currentSentence
            .onEach { sentence ->
                ttsCurrentElementId = sentence?.elementId ?: ttsCurrentElementId
                if (sentence != null) {
                    updateState { state -> state.copy(ttsSentenceIndex = sentence.index) }
                }
            }
            .launchIn(viewModelScope)
        ttsController.sentenceCount
            .onEach { count -> updateState { state -> state.copy(ttsSentenceCount = count) } }
            .launchIn(viewModelScope)
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

    private suspend fun findLinkedResume(): LinkedResumeOffer? {
        return try {
            findLinkedResumeUseCase(serverId, bookUuid)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            analytics.logException(exception, "ReaderViewModel: Failed to check linked copies")
            null
        }
    }

    /** "Continue": move to the other copy's place, and save it as this copy's own. */
    private fun continueLinkedResume() {
        val offer = viewState.value.linkedResumeOffer ?: return
        val position = offer.translated.position
        viewModelScope.launch {
            updateState { state ->
                state.copy(
                    linkedResumeOffer = null,
                    currentAudioPositionMs = position.audioTimestampMs
                        ?: state.currentAudioPositionMs,
                )
            }
            resolveLinkedResumeUseCase.continueFrom(offer)
            if (position.locatorHref != null) {
                bookController.goToPosition(position.toUiModel())
            } else {
                position.totalProgression?.let { progression ->
                    bookController.goToTotalProgression(progression)
                }
            }
            if (viewState.value.isReadAloud) {
                audioController.setInitialAudioPosition(position.audioTimestampMs)
            }
        }
    }

    /** Applying a position to this copy from the positions panel moves the reader there. */
    private fun observeAppliedPositions() {
        observeAppliedPositionUseCase(serverId, bookUuid, afterMillis = nowMillis())
            .onEach { position ->
                updateState { state ->
                    state.copy(linkedResumeOffer = null, positionConflict = null)
                }
                if (position.locatorHref != null) {
                    bookController.goToPosition(position.toUiModel())
                } else {
                    position.totalProgression?.let { progression ->
                        bookController.goToTotalProgression(progression)
                    }
                }
                if (viewState.value.isReadAloud) {
                    audioController.setInitialAudioPosition(position.audioTimestampMs)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun stayLinkedResume() {
        val offer = viewState.value.linkedResumeOffer ?: return
        updateState { state -> state.copy(linkedResumeOffer = null) }
        viewModelScope.launch { resolveLinkedResumeUseCase.stayHere(offer) }
    }

    private fun updatePosition(position: PositionUiModel) {
        // Nothing is saved until the person has answered a prompt, so an unanswered prompt
        // can't make this copy look like the latest reading.
        if (viewState.value.positionConflict != null) return
        if (viewState.value.linkedResumeOffer != null) return

        updatePublicationState { it.copy(position = position) }

        val currentState = viewState.value
        val audioTimestamp = currentState.currentAudioPositionMs.takeIf { it > 0 }
        positionSaveCoordinator.submit(createPositionDomainModel(position, audioTimestamp))
    }

    private fun createPositionDomainModel(
        position: PositionUiModel,
        audioTimestampMs: Long?,
    ): PositionDomainModel {
        val currentState = viewState.value
        return PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = nowMillis(),
            createdAt = position.createdAt,
            updatedAt = now().toString(),
            locatorHref = position.href,
            locatorType = position.type,
            locatorTitle = position.title,
            locatorTarget = null,
            audioTimestampMs = audioTimestampMs,
            chapterIndex = position.chapterIndex,
            progression = position.progression,
            totalChapters = position.totalChapters,
            totalDurationMs = currentState.totalDurationMs,
            totalProgression = position.totalProgression,
            position = position.position,
            cssSelector = position.cssSelector,
            textAnchor = position.textAnchor,
        )
    }

    private fun reportPositionSaveAttempt(isRetry: Boolean, entryPoint: String) {
        val mediaType = bookType.name.lowercase()
        if (isRetry) {
            analytics.logEvent(ReaderAnalyticsEvent.ReaderPositionSaveRetryAttempted(mediaType))
            positionSaveFailureCorrelationId?.let { correlationId ->
                analytics.logBreadcrumb(
                    positionSaveDiagnosticContext(
                        stage = "retry",
                        outcome = "started",
                        entryPoint = entryPoint,
                        correlationId = correlationId,
                    ),
                )
            }
        } else if (entryPoint == "reader_close" || !positionSaveAttemptReported) {
            // Position writes may occur on every page turn. Record one representative normal
            // attempt per Reader session, plus the final close checkpoint. Every failure and
            // explicit retry remains distinct.
            if (entryPoint != "reader_close") positionSaveAttemptReported = true
            analytics.logEvent(
                ReaderAnalyticsEvent.ReaderPositionSaveAttempted(
                    mediaType = mediaType,
                    entryPoint = entryPoint,
                ),
            )
            analytics.logBreadcrumb(
                positionSaveDiagnosticContext(
                    stage = "started",
                    outcome = "started",
                    entryPoint = entryPoint,
                ),
            )
        }
    }

    private fun onPositionSaveSucceeded(
        isRetry: Boolean,
        recoveredFailure: Boolean,
        entryPoint: String,
    ) {
        routineSyncScheduler.markDirty()
        if (isRetry || recoveredFailure || entryPoint == "reader_close" || !positionSaveSuccessReported) {
            if (entryPoint != "reader_close") positionSaveSuccessReported = true
            analytics.logEvent(
                ReaderAnalyticsEvent.ReaderPositionSaveSucceeded(
                    mediaType = bookType.name.lowercase(),
                    entryPoint = entryPoint,
                    isRetry = isRetry,
                ),
            )
            analytics.logBreadcrumb(
                positionSaveDiagnosticContext(
                    stage = "terminal",
                    outcome = "succeeded",
                    entryPoint = entryPoint,
                ),
            )
            positionSaveFailureCorrelationId = null
            positionSaveFailureReported = false
            updateState { it.copy(showPositionSaveFailed = false) }
        }
    }

    private fun onPositionSaveFailed(error: AppError, isRetry: Boolean, entryPoint: String) {
        val reasonCode = positionSaveFailureReasonCode(error)
        val correlationId = positionSaveFailureCorrelationId ?: createPositionSaveCorrelationId().also {
            positionSaveFailureCorrelationId = it
        }
        analytics.logEvent(
            ReaderAnalyticsEvent.ReaderPositionSaveFailed(
                mediaType = bookType.name.lowercase(),
                reasonCode = reasonCode,
                isRetry = isRetry,
                entryPoint = entryPoint,
            ),
        )
        val context = positionSaveDiagnosticContext(
            stage = if (isRetry) "retry" else "persist",
            outcome = "failed",
            reasonCode = reasonCode,
            entryPoint = entryPoint,
            correlationId = correlationId,
        )
        if (error.shouldReportException && !positionSaveFailureReported) {
            error.log(analytics, context)
            positionSaveFailureReported = true
        } else if (!error.shouldReportException || isRetry) {
            // Retries are user-initiated and bounded; retain their terminal outcome without
            // creating a second non-fatal for the same unresolved failure episode.
            analytics.logBreadcrumb(context)
        }
        updateState { it.copy(showPositionSaveFailed = true) }
    }

    private fun onPositionSaveCancelled(isRetry: Boolean, entryPoint: String) {
        if (!isRetry) return
        analytics.logEvent(ReaderAnalyticsEvent.ReaderPositionSaveRetryCancelled(bookType.name.lowercase()))
        positionSaveFailureCorrelationId?.let { correlationId ->
            analytics.logBreadcrumb(
                positionSaveDiagnosticContext(
                    stage = "terminal",
                    outcome = "cancelled",
                    entryPoint = entryPoint,
                    correlationId = correlationId,
                ),
            )
        }
        updateState { it.copy(showPositionSaveFailed = true) }
    }

    private fun positionSaveDiagnosticContext(
        stage: String,
        outcome: String,
        reasonCode: String? = null,
        entryPoint: String = if (stage == "retry") "retry" else "position_change",
        correlationId: String? = positionSaveFailureCorrelationId,
    ) = DiagnosticContext(
        screen = "reader",
        entryPoint = entryPoint,
        action = if (stage == "retry") "retry_reading_position_save" else "save_reading_position",
        operation = "reader_position_save",
        stage = stage,
        outcome = outcome,
        reasonCode = reasonCode,
        mediaType = bookType.name.lowercase(),
        correlationId = correlationId,
    )

    private fun positionSaveFailureReasonCode(error: AppError): String = when (error) {
        is AppError.DatabaseError -> "database_write_failed"
        is AppError.NetworkError -> when {
            error.isConnectivity -> "network_unavailable"
            error.isTimeout -> "network_timeout"
            else -> "network_failed"
        }
        is AppError.ApiError -> "api_failed"
        is AppError.AuthError -> "authentication_failed"
        is AppError.NotFoundError -> "repository_unavailable"
        is AppError.UnknownError -> "unexpected_failure"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun createPositionSaveCorrelationId(): String = Uuid.random().toString()

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
        if (willBeVisible) loadChapterTicks()
    }

    private fun toggleBookSearch() {
        val show = !viewState.value.isBookSearchVisible
        if (!show) {
            bookSearchJob?.cancel()
            bookSearchGeneration++
        }
        updateState { state ->
            state.copy(
                isBookSearchVisible = show,
                isBookSearchLoading = if (show) state.isBookSearchLoading else false,
                bookSearchAvailable = bookController.isSearchable,
                bookSearchIgnoresCaseAndAccents = bookController.searchIgnoresCaseAndAccents,
                bookSearchReadingOrder = bookController.searchReadingOrder(),
                bookSearchReferencePosition = if (state.selectedSearchIndex != null) state.bookSearchReferencePosition
                    ?: state.searchOrigin ?: state.currentPosition else state.currentPosition,
                bookSearchRecents = getUserPreferenceUseCase<List<RecentBookSearch>>(
                    PreferencesKey.RecentBookSearches(serverId, bookUuid)).orEmpty(),
            )
        }
        if (!show && viewState.value.selectedSearchIndex == null) bookController.clearSearchDecorations()
    }

    private fun searchBook(query: String, submitOnly: Boolean = false, submitted: Boolean = false) {
        val normalizedQuery = query.trim()
        bookSearchJob?.cancel()
        val generation = ++bookSearchGeneration
        bookController.clearSearchDecorations()
        val shouldSearch = normalizedQuery.length >= 2 && (!submitOnly || submitted) && bookController.isSearchable
        updateState {
            it.copy(
                bookSearchQuery = query,
                bookSearchSessionId = generation,
                bookSearchResults = emptyList(),
                isBookSearchLoading = shouldSearch,
                bookSearchFailed = false,
                bookSearchCount = 0,
                bookSearchChapterCounts = emptyMap(),
                bookSearchComplete = false,
                bookSearchAvailable = bookController.isSearchable,
                selectedSearchIndex = null,
                isFindBarVisible = false,
                searchOrigin = null,
            )
        }
        if (!shouldSearch) return
        bookSearchJob = viewModelScope.launch {
            try {
                if (!submitted) delay(300L)
                val boundaries = searchBoundaries ?: bookController.searchChapterBoundaries().also { searchBoundaries = it }
                if (generation != bookSearchGeneration) return@launch
                updateState { it.copy(bookSearchBoundaries = boundaries) }
                val resolver = SearchChapterResolver(viewState.value.tableOfContents, viewState.value.bookSearchReadingOrder, boundaries)
                bookController.search(normalizedQuery).collect { batch ->
                    val chapterCounts = withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        batch.results.groupingBy { result ->
                            context.ensureActive()
                            resolver.resolve(result).key
                        }.eachCount()
                    }
                    if (generation == bookSearchGeneration) {
                        val previousSize = viewState.value.bookSearchResults.size
                        updateState { state ->
                            state.copy(
                                bookSearchResults = state.bookSearchResults + batch.results.take(
                                    (SEARCH_RESULT_LIMIT - state.bookSearchResults.size).coerceAtLeast(0)).map { it.copy(sessionId = generation) },
                                bookSearchCount = batch.runningCount,
                                bookSearchChapterCounts = state.bookSearchChapterCounts.toMutableMap().apply {
                                    chapterCounts.forEach { (chapter, count) ->
                                        this[chapter] = (this[chapter] ?: 0) + count
                                    }
                                },
                                bookSearchComplete = batch.isComplete,
                                isBookSearchLoading = !batch.isComplete,
                            )
                        }
                        if (viewState.value.bookSearchResults.size != previousSize) refreshSearchDecorations()
                        if (batch.isComplete && viewState.value.selectedSearchIndex != null) {
                            val current = viewState.value
                            val recents = current.bookSearchRecents.map { recent ->
                                if (recent.query == normalizedQuery) recent.copy(count = batch.runningCount, complete = true) else recent
                            }
                            saveUserPreferenceUseCase(PreferencesKey.RecentBookSearches(serverId, bookUuid), recents)
                            updateState { it.copy(bookSearchRecents = recents) }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateState { state ->
                    if (generation == bookSearchGeneration) {
                        state.copy(isBookSearchLoading = false, bookSearchFailed = error !is BookNotSearchableException,
                            bookSearchAvailable = error !is BookNotSearchableException,
                            bookSearchNoTextLayer = (error as? BookNotSearchableException)?.noTextLayer == true)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun goToSearchResult(result: ReaderSearchResult) {
        val state = viewState.value
        if (result.sessionId != state.bookSearchSessionId) return
        val recent = RecentBookSearch(state.bookSearchQuery.trim(), state.bookSearchCount, state.bookSearchComplete)
        val recents = (listOf(recent) + state.bookSearchRecents.filterNot { it.query == recent.query }).take(8)
        saveUserPreferenceUseCase(PreferencesKey.RecentBookSearches(serverId, bookUuid), recents)
        updateState { it.copy(isBookSearchVisible = false, selectedSearchIndex = result.index,
            bookSearchReferencePosition = it.bookSearchReferencePosition ?: it.currentPosition,
            isFindBarVisible = true, searchOrigin = if (it.selectedSearchIndex == null) it.currentPosition else it.searchOrigin,
            searchPageTurns = if (it.selectedSearchIndex == null) 0 else it.searchPageTurns, bookSearchRecents = recents) }
        bookController.goToSearchResult(result)
        refreshSearchDecorations()
    }

    private fun stepSearchResult(delta: Int) {
        val state = viewState.value
        val current = state.bookSearchResults.indexOfFirst { it.index == state.selectedSearchIndex }
        val result = state.bookSearchResults.getOrNull(current + delta) ?: return
        navigateKeepingAudio(searchResult = result) { goToSearchResult(result) }
    }

    private fun refreshSearchDecorations() {
        val state = viewState.value
        val selected = state.selectedSearchIndex ?: return
        bookController.decorateSearch(state.bookSearchResults, selected, searchAccent, searchSoft, searchOnAccent, searchEink)
    }

    private fun closeBookSearch() {
        bookSearchJob?.cancel()
        bookSearchGeneration++
        bookController.clearSearchDecorations()
        updateState { it.copy(isBookSearchVisible = false, isBookSearchLoading = false,
            selectedSearchIndex = null, isFindBarVisible = false, searchOrigin = null, bookSearchReferencePosition = null) }
    }

    private fun searchPageTurned() {
        updateState { state ->
            val turns = state.searchPageTurns + 1
            state.copy(searchPageTurns = turns, searchOrigin = if (turns >= 3) null else state.searchOrigin)
        }
    }

    private fun jumpToBookProgress(progression: Double) {
        val previous = viewState.value.currentPosition
        viewModelScope.launch {
            if (bookController.goToTotalProgression(progression)) {
                updateState { it.copy(isTocVisible = false, previousTocPosition = previous) }
            }
        }
    }

    private fun loadChapterTicks() {
        if (viewState.value.chapterTickProgressions.isNotEmpty()) return
        viewModelScope.launch {
            val ticks = bookController.chapterStartProgressions()
            if (ticks.isNotEmpty()) updateState { it.copy(chapterTickProgressions = ticks) }
        }
    }

    private fun seekToChapterProgress(progression: Double) {
        val position = viewState.value.currentPosition ?: return
        bookController.goToPosition(
            position.copy(
                progression = progression.coerceIn(0.0, 1.0),
                position = null,
                totalProgression = null,
            ),
        )
    }

    private fun startListening(source: ListenSource, autoPlay: Boolean) {
        when (source) {
            ListenSource.NARRATION -> {
                if (!viewState.value.isReadAloud) return
                updateState { it.copy(isListening = true) }
                if (autoPlay && !viewState.value.isPlaying) {
                    activeNarrationController?.togglePlayback()
                }
            }

            ListenSource.DEVICE_VOICE -> {
                if (!viewState.value.isTtsReadAloud) return
                // Books with narration use device voice on demand without changing the saved setting.
                if (!viewState.value.isReadAloud && viewState.value.currentSettings?.ttsEnabled != true) {
                    setTtsEnabled(true)
                }
                updateState { it.copy(isListening = true) }
                if (autoPlay && !viewState.value.isPlaying) {
                    activeNarrationController?.togglePlayback()
                }
            }
        }
    }

    /**
     * Hands playback to the other audio system on a book that has both. The outgoing engine is
     * stopped first, the sync coordinator is re-pointed at the incoming one, and playback resumes
     * from the current sentence if audio was playing.
     */
    private fun switchListenSource(target: ListenSource) {
        val state = viewState.value
        if (!state.canSwitchListenSource || state.listenSource == target) return

        val wasPlaying = state.isPlaying
        val resumeFragmentId = ttsCurrentElementId
        val resumeHref = state.currentPosition?.href
        when (state.listenSource) {
            ListenSource.NARRATION -> audioController.pauseAudio()
            ListenSource.DEVICE_VOICE -> ttsController.stop()
        }
        when (target) {
            ListenSource.NARRATION -> {
                disableTtsSentencePlayback()
                activeNarrationController = audioController
                syncCoordinator.startNarration(viewModelScope, audioController)
            }

            ListenSource.DEVICE_VOICE -> {
                activeNarrationController = ttsController
                enableTtsSentencePlayback()
            }
        }
        updateState {
            it.copy(
                activeSource = target,
                isPlaying = false,
                isNarrationLoading = false,
                isNarrationStartPending = false,
            )
        }
        if (!wasPlaying) return

        when (target) {
            ListenSource.NARRATION ->
                audioController.playFromFragment(fragmentId = resumeFragmentId.orEmpty(), chapterHref = resumeHref)

            ListenSource.DEVICE_VOICE -> viewModelScope.launch {
                ttsSentencePlaybackJob?.join()
                togglePlayback()
            }
        }
    }

    private fun toggleListenSheet() {
        updateState { it.copy(isListenSheetVisible = !it.isListenSheetVisible) }
    }

    private fun stopListening() {
        if (viewState.value.listenSource == ListenSource.DEVICE_VOICE) {
            ttsController.stop()
        } else {
            audioController.pauseAudio()
            audioController.resetPlaybackState()
        }
        updateState { it.copy(isListening = false) }
    }

    /**
     * Runs a user-initiated jump (chapter, bookmark, search result, position). If audio is
     * playing it is stopped first and restarted once the reader lands, so narration resumes at
     * the synced timestamp for the new location and device voice at the first visible sentence.
     */
    private fun navigateKeepingAudio(searchResult: ReaderSearchResult? = null, navigate: () -> Unit) {
        val state = viewState.value
        if (!state.isListening || !state.isPlaying) {
            navigate()
            return
        }
        val before = state.currentPosition
        viewModelScope.launch {
            when (state.listenSource) {
                ListenSource.NARRATION -> audioController.pauseAudio()
                ListenSource.DEVICE_VOICE -> ttsController.stop()
            }
            navigate()
            withTimeoutOrNull(JUMP_SETTLE_TIMEOUT_MS) {
                bookController.currentLocator.first { locator ->
                    locator.href != before?.href || locator.progression != before.progression
                }
            }
            val sentence = searchResult?.let { result ->
                withTimeoutOrNull(2_000L) {
                    var id = bookController.searchSentenceId(result)
                    while (id == null) { delay(100L); id = bookController.searchSentenceId(result) }
                    id
                }
            }
            if (sentence != null) {
                if (state.listenSource == ListenSource.DEVICE_VOICE) playTtsFromSentence(sentence, searchResult?.href)
                else audioController.playFromFragment(sentence, searchResult?.href)
            } else togglePlayback()
        }
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

    fun close(closeSource: ReaderCloseSource = ReaderCloseSource.CloseButton) {
        // Leaving the reader must never wait on persistence or the network. The toolbar
        // arrow is the only visible way out and blocking here made it look dead. The work
        // runs on a NonCancellable coroutine so it still completes after navigation clears
        // this ViewModel (same pattern as AudiobookPlayerViewModel.close()); the sync
        // outbox holds the reading position even if the checkpoint below never runs.
        if (hasRequestedClose) return
        hasRequestedClose = true
        completeContinueReadingOpen(
            outcome = ContinueReadingOpenOutcome.Cancelled,
            reasonCode = ContinueReadingOpenReasonCode.ClosedBeforeContent,
        )
        currentBookTargetCheckpoint?.cancel()
        reportLastBookLaunchOutcome(
            outcome = NavigationAnalyticsEvent.LastBookLaunchOutcome.Cancelled,
            stage = "terminal",
            reasonCode = "closed_before_content",
        )

        viewModelScope.launch(NonCancellable) {
            // Persist the final in-memory locator (and audio offset when available) after any
            // in-flight page checkpoint. The close navigation itself remains non-blocking.
            val finalPosition = saveCurrentPositionForClose()
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

            // The reading session ended: move the other linked copies here too (slice 4).
            finalPosition?.let { position -> propagateToLinkedCopies(position) }

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

        onClose(closeSource)
    }

    /** Toggles the active ReadAloud or TTS narration implementation. */
    private fun togglePlayback() {
        if (viewState.value.listenSource == ListenSource.DEVICE_VOICE) {
            val selectedVoice = viewState.value.ttsVoices
                .firstOrNull { voice -> voice.id == viewState.value.selectedTtsVoiceId }
            if (selectedVoice?.needsDownload == true) {
                openVoiceSettings()
                return
            }
        }
        updateState { it.copy(isListening = true) }
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
        val isTts = viewState.value.listenSource == ListenSource.DEVICE_VOICE
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
            persistReaderSettingsUpdateFromReader(
                currentSettings = currentSettings,
                update = update,
                saveSettings = { settings -> saveReaderSettingsUseCase(settings) },
                analytics = analytics,
            )
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
        // Both engines can be observed on a book that has narration and device voice; only the
        // active one may drive the shared playback state.
        controller.isPlaying
            .distinctUntilChanged()
            .filter { activeNarrationController === controller }
            .onEach { isPlaying -> updatePlayingState(isPlaying) }
            .launchIn(viewModelScope)

        controller.isLoading
            .distinctUntilChanged()
            .filter { activeNarrationController === controller }
            .onEach { isLoading ->
                updateState { state -> state.copy(isNarrationLoading = isLoading) }
            }
            .launchIn(viewModelScope)

        controller.isPlaybackStartPending
            .distinctUntilChanged()
            .filter { activeNarrationController === controller }
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
            saveCurrentAudioPositionSync()?.let { position -> propagateToLinkedCopies(position) }
        }
    }

    /**
     * Saves the current audio position to persistence synchronously.
     * This is called when the reader is closed to ensure the position is saved
     * before navigation occurs.
     */
    /** @return the position submitted for saving, or null when nothing was saved. */
    private suspend fun saveCurrentAudioPositionSync(): PositionDomainModel? {
        val currentState = viewState.value
        val audioPositionMs = currentState.currentAudioPositionMs
        val currentPosition = currentState.currentPosition

        if (audioPositionMs <= 0 || currentState.listenSource != ListenSource.NARRATION) {
            return null
        }
        if (currentPosition == null) {
            return null
        }


        val position = createPositionDomainModel(currentPosition, audioPositionMs)
        positionSaveCoordinator.submit(position)
        return position
    }

    /** @return the final position saved, or null when there was none. */
    private suspend fun saveCurrentPositionForClose(): PositionDomainModel? {
        val currentState = viewState.value
        val currentPosition = currentState.currentPosition ?: return null
        val audioPositionMs = currentState.currentAudioPositionMs
            .takeIf { currentState.isReadAloud && currentState.listenSource == ListenSource.NARRATION && it > 0 }
        val position = createPositionDomainModel(currentPosition, audioPositionMs)
        positionSaveCoordinator.saveForClose(position)
        return position
    }

    /**
     * Moves the other linked copies to where reading stopped, when the translation is
     * reliable (P5). Never blocks or fails the reader.
     */
    private suspend fun propagateToLinkedCopies(position: PositionDomainModel) {
        if (viewState.value.linkedResumeOffer != null || viewState.value.positionConflict != null) {
            return
        }
        try {
            propagateToLinkedCopiesUseCase(
                serverId = serverId,
                bookUuid = bookUuid,
                position = position.copy(observedAt = Clock.System.now().toString()),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            analytics.logException(exception, "ReaderViewModel: Failed to update linked copies")
        }
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
        private const val JUMP_SETTLE_TIMEOUT_MS = 2_000L

        private const val SLEEP_TIMER_TICK_MS = 1_000L

        private const val SLEEP_TIMER_WARNING_THRESHOLD_MS = 60_000L
    }
}
