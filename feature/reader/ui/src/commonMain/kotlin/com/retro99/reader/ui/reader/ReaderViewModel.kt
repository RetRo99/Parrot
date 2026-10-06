package com.retro99.reader.ui.reader

import com.github.michaelbull.result.getOrElse

import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.viewModelScope
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.ReaderNavigationMethod
import com.retro99.analytics.api.ReaderNavigationTracker
import com.retro99.analytics.api.UsageOperation
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.logFeatureUsage
import com.retro99.analytics.api.trackUsageOperation
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.ProductOutcome
import com.retro99.analytics.api.ProductUsage
import com.retro99.analytics.api.ReaderOpenTracker
import com.retro99.analytics.api.SearchScope
import com.retro99.analytics.api.FeatureExposureTracker
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.UsageMode
import com.retro99.analytics.api.UsageEndReason
import com.retro99.analytics.api.UsageSession
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
import com.retro99.books.ui.components.ConflictSide
import com.retro99.books.ui.components.conflictSourceName
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderInitializationData
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapLanguages
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.FindLinkedResumeUseCase
import com.retro99.reader.domain.usecase.InitializeReaderUseCase
import com.retro99.reader.domain.usecase.ObserveAppliedPositionUseCase
import com.retro99.reader.domain.usecase.PropagateToLinkedCopiesUseCase
import com.retro99.reader.domain.usecase.ResolveLinkedResumeUseCase
import com.retro99.reader.domain.usecase.ResolveSavedBookUseCase
import com.retro99.reader.ui.reader.saved.ListeningSentence
import com.retro99.reader.ui.reader.saved.ReaderSavedContext
import com.retro99.reader.ui.reader.saved.ReaderSavedItems
import com.retro99.reader.ui.reader.saved.SavedAction
import com.retro99.base.ui.sharing.FileSharer
import com.retro99.saved.domain.usecase.DeleteSavedItemUseCase
import com.retro99.saved.domain.usecase.ObserveBookSavedItemsUseCase
import com.retro99.saved.domain.usecase.ObserveSavedSyncStateUseCase
import com.retro99.saved.domain.usecase.RestoreSavedItemUseCase
import com.retro99.saved.domain.usecase.SaveSavedItemsUseCase
import com.retro99.reader.domain.usecase.SaveReaderSettingsUseCase
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.domain.usecase.SetCurrentlyReadingUseCase
import com.retro99.reader.ui.di.InitialAudioPosition
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.PositionConflictUiModel
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
import com.retro99.statistics.domain.ActiveSessionTimer
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
    @Provided private val serverRegistry: com.retro99.server.api.ServerRegistry,
    @Provided private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val resolvePositionConflictUseCase: com.retro99.reader.domain.usecase.ResolvePositionConflictUseCase,
    @Provided private val getReadingProgressWithConflictUseCase: com.retro99.reader.domain.usecase.GetReadingProgressWithConflictUseCase,
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
    @Provided private val observeBookSavedItemsUseCase: ObserveBookSavedItemsUseCase,
    @Provided private val saveSavedItemsUseCase: SaveSavedItemsUseCase,
    @Provided private val deleteSavedItemUseCase: DeleteSavedItemUseCase,
    @Provided private val restoreSavedItemUseCase: RestoreSavedItemUseCase,
    @Provided private val observeSavedSyncStateUseCase: ObserveSavedSyncStateUseCase,
    @Provided private val resolveSavedBookUseCase: ResolveSavedBookUseCase,
    @Provided private val fileSharer: FileSharer,
    @Provided private val dictionaryService: com.retro99.dictionary.DictionaryService,
    @Provided private val pendingSavedJump: com.retro99.saved.domain.PendingSavedJump,
    @Provided private val publicationService: EpubPublicationService,
    @Provided private val analytics: Analytics,
    @Provided private val productUsage: ProductUsage,
    @Provided private val supertonicTermsStore: SupertonicTermsStore,
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val recapSessionRecorder: RecapSessionRecorder,
    @Provided private val recapSettings: RecapSettings,
    @Provided private val installationDeviceIdentity: com.retro99.server.api.InstallationDeviceIdentity,
) : BaseViewModel<ReaderViewState, ReaderIntent>(
    ReaderViewState(
        bookUuid = bookUuid,
        bookType = bookType,
        hasAcceptedSupertonicTerms = supertonicTermsStore.hasAcceptedCurrentTerms(),
    )
) {

    private val openTracker = ReaderOpenTracker(analytics, bookType.value,
        readerOpenEntryPoint ?: if (isLastBookOnLaunch) "app_launch" else "book_detail")
    private val usageSession = UsageSession(analytics, productUsage::meaningfulSession)
    private val featureExposure = FeatureExposureTracker(analytics, "reader")
    private var navigationTarget: ((PositionUiModel) -> Boolean)? = null
    private var navigationTimeout: kotlinx.coroutines.Job? = null
    private val navigationTracker = ReaderNavigationTracker(analytics)

    private fun beginNavigation(method: ReaderNavigationMethod, target: (PositionUiModel) -> Boolean) {
        finishNavigation(ProductOutcome.Cancelled)
        navigationTracker.begin(method, currentUsageMode())
        navigationTarget = target
        navigationTimeout = viewModelScope.launch {
            kotlinx.coroutines.delay(10_000)
            finishNavigation(ProductOutcome.Failed)
        }
    }

    private fun finishNavigation(outcome: ProductOutcome) {
        navigationTracker.complete(outcome)
        navigationTarget = null
        navigationTimeout?.cancel()
        navigationTimeout = null
    }
    private var contentReady = false
    private var completionInitialized = false
    private var searchStartedAt = TimeSource.Monotonic.markNow()
    private var searchReportedGeneration: Long? = null

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
    private var ttsCurrentSentence: com.retro99.reader.ui.tts.TtsSentence? = null
    /** Latest preview request, used to restore its row if stopping the previous preview emits IDLE. */
    private var latestTtsPreviewKey: String? = null

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
    private var contentsGroupsInitialized = false

    /** Job for the second scan that reveals matches past the spoiler boundary. */
    private var bookSearchAheadJob: Job? = null

    /** Furthest point reached in this book: the search spoiler boundary. */
    private var furthestReadMark: SearchBoundaryMark? = null

    /** True when the reveal was asked for while the first scan is still running. */
    private var revealAfterScan = false

    /** Steps to the first revealed match once the rest of the book has been scanned. */
    private var stepAfterReveal = false

    /** Job for downloading and loading the selected neural voice model. */
    private var ttsPreparationJob: Job? = null

    /** Job for preparing sentence elements and enabling double-tap TTS playback. */
    private var ttsSentencePlaybackJob: Job? = null
    private var isObservingTtsPlaybackOperations = false

    /** Serializes full reader-settings updates so concurrent controls cannot lose changes. */
    private val readerSettingsSaveMutex = Mutex()

    /** Wall-clock start for the saved session; active duration uses a monotonic timer. */
    private var bookOpenedTimestamp: Long = 0L
    private val statisticsTimer = ActiveSessionTimer()

    /** Recap session id, minted when the book opens; null until then. */
    private var recapSessionId: String? = null

    /** Feeds read text to the recap; null unless cloud recaps are on. */
    private var recapCapture: ReaderRecapCapture? = null

    /** False while the app is in the background (reader stopped). */
    private var isReaderVisible = true

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
        observeProductUsage()
        observeShowCurrentTimeSetting()
        furthestReadMark = getUserPreferenceUseCase<SearchBoundaryMark>(
            PreferencesKey.FurthestReadPosition(serverId, bookUuid),
        )
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
            is ReaderIntent.PromptVisible -> analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.Operation(
                intent.operation, UsageAction.Shown, "reader", ProductOutcome.Succeeded,
            ))
            is ReaderIntent.FeatureVisible -> {
                if (isReaderVisible) featureExposure.expose(intent.feature, intent.available)
            }
            ReaderIntent.ToggleBookSearch -> toggleBookSearch()
            is ReaderIntent.SearchBook -> searchBook(intent.query, intent.submitOnly)
            is ReaderIntent.ReaderVisibilityChanged -> setReaderVisible(intent.visible)
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
            is ReaderIntent.RevealSearchAhead -> revealSearchAhead(intent.stepNext)
            ReaderIntent.DismissSearchContinuePrompt -> updateState { it.copy(showSearchContinuePrompt = false) }
            is ReaderIntent.UpdateSearchDecorations -> {
                searchAccent = intent.accent; searchSoft = intent.soft; searchOnAccent = intent.onAccent; searchEink = intent.eink
                refreshSearchDecorations()
            }
            is ReaderIntent.GoToSearchResult -> navigateKeepingAudio(searchResult = intent.result) { goToSearchResult(intent.result) }
            is ReaderIntent.SeekToChapterProgress -> seekToChapterProgress(intent.progression)
            ReaderIntent.ReturnToJumpOrigin -> viewState.value.jumpOrigin?.let { origin ->
                navigateKeepingAudio {
                    bookController.goToPosition(origin)
                    updateState { it.copy(jumpOrigin = null, jumpOriginPageTurns = 0) }
                }
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
            ReaderIntent.DismissSettleBar -> updateState { it.copy(positionSettleBar = null) }
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
            is ReaderIntent.ToggleContentsGroup -> toggleContentsGroup(intent.flatIndex)
            is ReaderIntent.GoToChapter -> navigateKeepingAudio {
                goToChapter(intent.href, intent.currentPosition)
            }
            ReaderIntent.GoToNextChapter -> goToNextChapter()
            ReaderIntent.GoToPreviousChapter -> goToPreviousChapter()
            is ReaderIntent.GoToChapterAndPlay -> goToChapterAndPlay(intent.href, intent.currentPosition)
            ReaderIntent.GoToNextChapterAndPlay -> goToNextChapterAndPlay()
            ReaderIntent.GoToPreviousChapterAndPlay -> goToPreviousChapterAndPlay()
            is ReaderIntent.SetHighlightColor -> setHighlightColor(intent.colorArgb)
            ReaderIntent.Retry -> retry()
            ReaderIntent.DismissNoAudioMessage -> dismissNoAudioMessage()
            ReaderIntent.RetryTtsPlayback -> retryTtsPlayback()
            ReaderIntent.DismissTtsPlaybackFailed -> dismissTtsPlaybackFailed()
            ReaderIntent.RetryPositionSave -> retryPositionSave()
            ReaderIntent.ToggleBookmarks -> toggleBookmarks()
            is ReaderIntent.Saved -> handleSaved(intent.action)
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

    private fun retryPositionSave() {
        if (positionSaveCoordinator.retry()) {
            updateState { it.copy(showPositionSaveFailed = false) }
        }
    }

    private fun retry() {
        openTracker.retry()
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
        pageTurned()
        bookController.goToNextPage()
    }

    private fun goToPreviousPage() {
        pageTurned()
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
                val recapNavigated = navigationTarget != null
                val positionUiModel = locator.toPositionUiModel(
                    basePosition = currentState.currentPosition,
                    createdAt = now().toString(),
                )
                if (navigationTarget?.invoke(positionUiModel) == true) finishNavigation(ProductOutcome.Succeeded)
                updatePosition(positionUiModel)
                if (!contentReady && !hasRequestedClose) {
                    contentReady = true
                    openTracker.complete(ProductOutcome.Succeeded)
                    refreshProductUsage()
                }
                positionUiModel.totalProgression?.let { progress ->
                    productUsage.observeCompletion("$serverId:$bookUuid", progress, currentUsageMode(), initial = !completionInitialized)
                    completionInitialized = true
                }
                positionUiModel.toRecapPage(locator.chapterInfo?.currentPage)
                    ?.let { page -> recapCapture?.onPageShown(page, navigated = recapNavigated,
                        scrollMode = currentState.currentSettings?.scrollMode == true) }

                // Update chapter info from the enriched locator state
                // (word count is used internally by ReadingSpeedTracker via the locator flow)
                updateState { it.copy(chapterInfo = locator.chapterInfo) }
                refreshSearchDecorations()
                savedItems.onPageChanged(positionUiModel)
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
            try {
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
            } catch (exception: CancellationException) {
                openTracker.complete(ProductOutcome.Cancelled, "reader_open_cancelled")
                throw exception
            } catch (exception: Exception) {
                openTracker.complete(ProductOutcome.Failed, "unexpected_reader_open_failure")
                throw exception
            }
        }
    }

    private suspend fun openPublication(data: ReaderInitializationData) {
        val thisDeviceName = try {
            installationDeviceIdentity.selfReferenceName()
        } catch (exception: Exception) {
            ""
        }
        val conflictServerName = conflictSourceName(serverId, serverRegistry)
        val settings = data.initialSettings.toUiModel()
        val customFonts = getCustomReaderFontsUseCase().first()
        val bookType = data.bookType
        val (position, conflict) = data.progressResult.toUiData()
        restoredPositionSaveGuard.restored(position)
        // Checked while the publication opens; it waits at most 2 seconds for servers.
        val startupPrompt = viewModelScope.async {
            readerStartupPrompt(
                linkedResumeResolved,
                conflict,
                remoteName = conflictServerName,
                thisDeviceName = thisDeviceName,
                findLinkedResume = ::findLinkedResume,
            )
        }

        publicationService.openPublication(
            filePath = data.localEbookPath,
            serverId = data.serverId,
            bookUuid = data.bookUuid,
            bookType = bookType,
        ).onSuccess { publication ->
            // Track book opened event
            bookOpenedTimestamp = nowMillis()
            statisticsTimer.setActive(isReaderVisible)
            startRecapSession(data.serverId, data.bookUuid, position, publication.language)
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
            position?.let(::trackFurthestPosition)
            position?.totalProgression?.let { progress ->
                productUsage.observeCompletion("$serverId:$bookUuid", progress, UsageMode.Reading, initial = true)
                completionInitialized = true
            }

            updateState { state ->
                state.copy(
                    bookUuid = data.bookUuid,
                    bookTitle = data.bookTitle,
                    bookAuthor = data.bookAuthor,
                    bookCoverUrl = data.bookCoverUrl,
                    publicationState = publicationState,
                    bookType = bookType,
                    positionConflict = prompt.positionConflict,
                    positionSettleBar = prompt.positionSettleBar,
                    conflictServerName = conflictServerName,
                    thisDeviceName = thisDeviceName,
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
            savedItems.start(serverId, bookUuid)
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
        openTracker.complete(if (isContinueReadingCancellation) ProductOutcome.Cancelled else ProductOutcome.Failed, resolvedReasonCode)
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
                        usageSession.checkpoint(UsageEndReason.Error)
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
        stopTtsPreview()
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
        stopTtsPreview()
        updateState { state -> state.copy(isVoiceSettingsVisible = false) }
    }

    private fun setTtsRate(rate: Float) {
        analytics.logEvent(ReaderAnalyticsEvent.TtsRateChanged(bookUuid = bookUuid, rate = rate))
        stopTtsPreview()
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
        stopTtsPreview()
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
            stopTtsPreview()
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
        val previewKey = voiceId ?: TTS_SYSTEM_VOICE_KEY
        latestTtsPreviewKey = previewKey
        updateState { state ->
            state.copy(
                ttsPreviewingVoiceId = previewKey,
                isTtsPreviewPlaying = false,
            )
        }
        ttsController.previewVoice(voiceId, text)
    }

    private fun stopTtsPreview() {
        latestTtsPreviewKey = null
        updateState { state ->
            state.copy(
                ttsPreviewingVoiceId = null,
                isTtsPreviewPlaying = false,
            )
        }
        ttsController.stopPreview()
    }

    private fun observeTtsSentenceProgress() {
        ttsController.currentSentence
            .onEach { sentence ->
                ttsCurrentElementId = sentence?.elementId ?: ttsCurrentElementId
                if (sentence != null) ttsCurrentSentence = sentence
                if (sentence != null) {
                    updateState { state -> state.copy(ttsSentenceIndex = sentence.index) }
                }
            }
            .launchIn(viewModelScope)
        ttsController.sentenceCount
            .onEach { count -> updateState { state -> state.copy(ttsSentenceCount = count) } }
            .launchIn(viewModelScope)
        ttsController.finishedSentences
            .onEach { finished -> recapCapture?.onSentenceFinished(finished) }
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
                        viewState.copy(
                            ttsPreviewingVoiceId = latestTtsPreviewKey ?: viewState.ttsPreviewingVoiceId,
                            isTtsPreviewPlaying = false,
                        )
                    }

                    TtsPreviewState.SPEAKING -> updateState { viewState ->
                        viewState.copy(
                            ttsPreviewingVoiceId = latestTtsPreviewKey ?: viewState.ttsPreviewingVoiceId,
                            isTtsPreviewPlaying = true,
                        )
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
        resolveConflict(useLocal = true)
    }

    private fun resolveConflictWithRemote() {
        resolveConflict(useLocal = false)
    }

    /**
     * Applies one side of a conflict, whether the interactive dialog answered it or the
     * quiet bar's action did; the bar mirrors the message afterwards (spec §2).
     */
    private fun resolveConflict(useLocal: Boolean) {
        val settle = viewState.value.positionConflict == null
        val conflict = if (settle) {
            viewState.value.positionSettleBar?.candidates
        } else {
            viewState.value.positionConflict?.candidates
        } ?: return
        if (viewState.value.isResolvingConflict) return
        updateState {
            it.copy(
                isResolvingConflict = true,
                conflictResolutionError = null,
                resolvingConflictSide = if (useLocal) ConflictSide.Local else ConflictSide.Remote,
            )
        }
        viewModelScope.launch {
            val selected = if (useLocal) conflict.localPosition else conflict.remotePosition
            analytics.trackUsageOperation(
                UsageOperation.Conflict,
                if (useLocal) UsageAction.Local else UsageAction.Remote,
                "reader",
                outcome = { if (it.isOk) {
                    if (useLocal) ProductOutcome.Queued else ProductOutcome.Succeeded
                } else ProductOutcome.Failed },
            ) {
                if (useLocal) resolvePositionConflictUseCase.useLocal(conflict)
                else resolvePositionConflictUseCase.useRemote(conflict)
            }.onSuccess {
                // Keep the prompt up while navigating so its locator callback cannot
                // turn accepting a server snapshot into a fresh local reading write.
                val restored = selected.toUiModel()
                restoredPositionSaveGuard.restored(restored)
                updatePublicationState { it.copy(position = restored) }
                bookController.goToPosition(restored)
                updateState { it.copy(
                    positionConflict = null,
                    currentAudioPositionMs = selected.audioTimestampMs ?: 0L,
                ) }
                if (viewState.value.isReadAloud) {
                    audioController.setInitialAudioPosition(selected.audioTimestampMs)
                }
                // The quiet bar answers once and mirrors itself: "Kept your place" and
                // "Moved to N%" swap sides so the earlier answer can be undone (spec §2).
                if (settle) {
                    updateState { state ->
                        state.copy(positionSettleBar = state.positionSettleBar?.copy(movedToOther = !useLocal))
                    }
                }
            }.onFailure { error ->
                error.log(analytics, "ReaderViewModel: Failed to resolve position conflict")
                if (settle) {
                    // Keep the bar so the person can try again with fresh candidates:
                    // while the bar is up the reader keeps saving, and a busy generation
                    // invalidates the captured candidates but not the person's intent.
                    val refreshed = getReadingProgressWithConflictUseCase(serverId, bookUuid)
                        .getOrElse { null } as? ReadingProgressResult.Conflict
                    updateState { state ->
                        state.copy(
                            isResolvingConflict = false,
                            positionSettleBar = state.positionSettleBar?.let { bar ->
                                bar.copy(candidates = refreshed ?: bar.candidates)
                            },
                        )
                    }
                } else {
                    val refreshed = getReadingProgressWithConflictUseCase(serverId, bookUuid)
                        .getOrElse { null }?.toUiData()?.conflict
                    updateState { it.copy(
                        conflictResolutionError = error,
                        positionConflict = refreshed ?: refreshedFromCandidates(),
                    ) }
                }
            }
            updateState { it.copy(isResolvingConflict = false) }
        }
    }

    /** Keeps the same candidates visible after a failed attempt; nothing is approved. */
    private fun refreshedFromCandidates(): PositionConflictUiModel? =
        viewState.value.positionConflict?.let { conflict ->
            PositionConflictUiModel(
                localPosition = conflict.candidates.localPosition.toUiModel(),
                remotePosition = conflict.candidates.remotePosition.toUiModel(),
                candidates = conflict.candidates,
            )
        }

    private val restoredPositionSaveGuard = RestoredPositionSaveGuard()

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
            analytics.trackUsageOperation(
                UsageOperation.LinkedResume, UsageAction.Accept, "reader",
                outcome = { if (it.isOk) ProductOutcome.Succeeded else ProductOutcome.Failed },
            ) { resolveLinkedResumeUseCase.continueFrom(offer) }
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
                val restored = position.toUiModel()
                restoredPositionSaveGuard.restored(restored)
                updatePublicationState { it.copy(position = restored) }
                updateState { state ->
                    state.copy(
                        linkedResumeOffer = null, positionConflict = null, positionSettleBar = null,
                        currentAudioPositionMs = position.audioTimestampMs ?: state.currentAudioPositionMs,
                    )
                }
                if (position.locatorHref != null) {
                    bookController.goToPosition(restored)
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
        viewModelScope.launch {
            analytics.trackUsageOperation(
                UsageOperation.LinkedResume, UsageAction.Decline, "reader", outcome = { ProductOutcome.Succeeded },
            ) { resolveLinkedResumeUseCase.stayHere(offer) }
        }
    }

    private fun updatePosition(position: PositionUiModel) {
        // Nothing is saved until the person has answered a prompt, so an unanswered prompt
        // can't make this copy look like the latest reading.
        trackFurthestPosition(position)
        if (viewState.value.positionConflict != null) return
        if (viewState.value.linkedResumeOffer != null) return

        updatePublicationState { it.copy(position = position) }

        val currentState = viewState.value
        val audioTimestamp = currentState.currentAudioPositionMs.takeIf { it > 0 }
        if (!restoredPositionSaveGuard.shouldSave(position, audioTimestamp)) return
        positionSaveCoordinator.submit(createPositionDomainModel(position, audioTimestamp))
    }

    private fun PositionUiModel.toBoundaryMark() = SearchBoundaryMark(href, progression, totalProgression)

    /**
     * Remembers the furthest point reached in this book, so search never hides what has
     * already been read when the reader flips back to an earlier chapter.
     */
    private fun trackFurthestPosition(position: PositionUiModel) {
        val total = position.totalProgression ?: return
        val known = furthestReadMark?.totalProgression
        if (known != null && known >= total) return
        val mark = position.toBoundaryMark()
        furthestReadMark = mark
        saveUserPreferenceUseCase(PreferencesKey.FurthestReadPosition(serverId, bookUuid), mark)
    }

    /**
     * Spoiler boundary for this book's search: the furthest point reached, falling back to
     * the current position. Finished books and the "Hide search results ahead of my page"
     * setting being off mean no boundary — nothing is hidden.
     */
    private fun spoilerBoundary(): SearchBoundaryMark? {
        if (viewState.value.currentSettings?.hideSearchResultsAhead == false) return null
        val current = viewState.value.currentPosition?.toBoundaryMark()
        return resolveSearchBoundary(furthestReadMark, current)
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
        openContentsSheet(ContentsTab.CHAPTERS)
    }

    private fun toggleBookmarks() {
        openContentsSheet(ContentsTab.SAVED)
    }

    /**
     * The Chapters and Bookmarks tabs share one sheet. ToggleToc closes it from either tab
     * (that is what the close button and mini-player dispatch); ToggleBookmarks always
     * brings up the Bookmarks tab, opening or switching as needed.
     */
    private fun openContentsSheet(tab: ContentsTab) {
        val state = viewState.value
        val show = if (tab == ContentsTab.CHAPTERS) {
            !state.isContentsVisible
        } else {
            !state.isContentsVisible || state.contentsInitialTab != ContentsTab.SAVED
        }
        if (show) {
            analytics.logEvent(
                if (tab == ContentsTab.CHAPTERS) ReaderAnalyticsEvent.TocOpened(bookUuid = bookUuid)
                else ReaderAnalyticsEvent.BookmarksOpened(bookUuid = bookUuid),
            )
        }
        if (viewState.value.bookSearchReadingOrder.isEmpty()) {
            updateState { it.copy(bookSearchReadingOrder = bookController.searchReadingOrder()) }
        }
        if (!show) {
            updateState { it.copy(isContentsVisible = false) }
            return
        }
        updateState {
            val current = findTocLocation(
                it.tableOfContents,
                it.currentPosition?.href,
                it.currentPosition?.progression,
                it.bookSearchReadingOrder,
            )
            val expanded = it.contentsExpandedGroups.toMutableSet()
            if (!contentsGroupsInitialized && it.tableOfContents.isNotEmpty()) {
                contentsGroupsInitialized = true
                expanded += defaultExpandedGroups(it.tableOfContents, current?.flatIndex)
            }
            // The part you're in is always open when Contents opens.
            current?.let { location -> expanded += tocAncestors(it.tableOfContents, location.flatIndex) }
            it.copy(
                isContentsVisible = true,
                contentsInitialTab = tab,
                contentsExpandedGroups = expanded,
            )
        }
    }

    private fun toggleContentsGroup(flatIndex: Int) {
        updateState { state ->
            val expanded = state.contentsExpandedGroups.toMutableSet()
            if (!expanded.add(flatIndex)) expanded.remove(flatIndex)
            state.copy(contentsExpandedGroups = expanded)
        }
    }

    private fun toggleBookSearch() {
        val show = !viewState.value.isBookSearchVisible
        if (!show) {
            bookSearchJob?.cancel()
            bookSearchAheadJob?.cancel()
            bookSearchGeneration++
        }
        updateState { state ->
            state.copy(
                isBookSearchVisible = show,
                isBookSearchLoading = if (show) state.isBookSearchLoading else false,
                bookSearchAvailable = bookController.isSearchable,
                bookSearchIgnoresCaseAndAccents = bookController.searchIgnoresCaseAndAccents,
                bookSearchReadingOrder = bookController.searchReadingOrder(),
                showSearchContinuePrompt = false,
                bookSearchRecents = if (show) {
                    getUserPreferenceUseCase<List<RecentBookSearch>>(
                        PreferencesKey.RecentBookSearches(serverId, bookUuid),
                    ).orEmpty()
                } else {
                    state.bookSearchRecents
                },
            )
        }
        if (!show && viewState.value.selectedSearchIndex == null) bookController.clearSearchDecorations()
    }

    private fun searchBook(query: String, submitOnly: Boolean = false, submitted: Boolean = false) {
        val normalizedQuery = query.trim()
        bookSearchJob?.cancel()
        bookSearchAheadJob?.cancel()
        val generation = ++bookSearchGeneration
        bookController.clearSearchDecorations()
        val shouldSearch = normalizedQuery.length >= 2 && (!submitOnly || submitted) && bookController.isSearchable
        val boundary = if (shouldSearch) spoilerBoundary() else null
        revealAfterScan = false
        stepAfterReveal = false
        updateState {
            it.copy(
                bookSearchQuery = query,
                bookSearchSessionId = generation,
                bookSearchResults = emptyList(),
                isBookSearchLoading = shouldSearch,
                bookSearchFailed = false,
                bookSearchCount = 0,
                bookSearchCapped = false,
                bookSearchComplete = false,
                bookSearchAvailable = bookController.isSearchable,
                selectedSearchIndex = null,
                isFindBarVisible = false,
                searchOrigin = null,
                searchBoundary = boundary,
                searchAheadRevealed = false,
                searchAheadSplitIndex = null,
                isSearchAheadLoading = false,
                searchAheadComplete = false,
                showSearchContinuePrompt = false,
            )
        }
        if (!shouldSearch) return
        bookSearchJob = viewModelScope.launch {
            try {
                if (!submitted) delay(300L)
                searchStartedAt = TimeSource.Monotonic.markNow()
                val boundaries = searchBoundaries ?: bookController.searchChapterBoundaries().also { searchBoundaries = it }
                if (generation != viewState.value.bookSearchSessionId) return@launch
                updateState { it.copy(bookSearchBoundaries = boundaries) }
                bookController.search(normalizedQuery).collect { batch ->
                    if (generation != viewState.value.bookSearchSessionId) return@collect
                    // The scan stops at the spoiler boundary: nothing past it is searched,
                    // counted or kept until the reader asks for the rest of the book.
                    val split = splitAtBoundary(batch.results, boundary, viewState.value.bookSearchReadingOrder)
                    val capped = appendSearchResults(generation, split.kept)
                    if (split.passedBoundary || capped) throw SearchScanStopped()
                }
                completeSearchScan(generation)
            } catch (stopped: SearchScanStopped) {
                completeSearchScan(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == viewState.value.bookSearchSessionId) reportSearchResults(generation, ProductOutcome.Failed)
                updateState { state ->
                    if (generation == viewState.value.bookSearchSessionId) {
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

    /** The scan up to the boundary is done: mark it complete and start any pending reveal. */
    private fun completeSearchScan(generation: Long) {
        updateState { state ->
            if (generation == viewState.value.bookSearchSessionId) {
                state.copy(isBookSearchLoading = false, bookSearchComplete = true)
            } else {
                state
            }
        }
        reportSearchResults(generation)
        if (revealAfterScan) revealSearchAhead()
    }

    private fun reportSearchResults(generation: Long, outcome: ProductOutcome = ProductOutcome.Succeeded) {
        val state = viewState.value
        if (generation != state.bookSearchSessionId || searchReportedGeneration == generation) return
        searchReportedGeneration = generation
        analytics.logEvent(ProductAnalyticsEvent.SearchResultsShown(
            scope = SearchScope.Book,
            count = state.bookSearchResults.size,
            durationMs = searchStartedAt.elapsedNow().inWholeMilliseconds,
            outcome = outcome,
            isCapped = state.bookSearchCapped,
            isRestricted = state.searchBoundary != null,
        ))
    }

    /**
     * Reveals the matches past the spoiler boundary: scans the rest of the book and shows
     * those results under "After your page". The reveal applies to the current query only.
     */
    private fun revealSearchAhead(stepNext: Boolean = false) {
        val state = viewState.value
        val boundary = state.searchBoundary ?: return
        stepAfterReveal = stepAfterReveal || stepNext
        updateState { it.copy(showSearchContinuePrompt = false) }
        if (!state.bookSearchComplete) {
            // Still scanning up to the boundary; reveal once it finishes.
            revealAfterScan = true
            return
        }
        if (state.searchAheadRevealed) {
            if (stepAfterReveal) {
                stepAfterReveal = false
                stepSearchResult(1)
            }
            return
        }
        revealAfterScan = false
        val generation = state.bookSearchSessionId
        updateState {
            it.copy(
                searchAheadRevealed = true,
                searchAheadSplitIndex = it.bookSearchResults.size,
                isSearchAheadLoading = true,
                searchAheadComplete = false,
            )
        }
        bookSearchAheadJob = viewModelScope.launch {
            try {
                bookController.search(state.bookSearchQuery.trim()).collect { batch ->
                    if (generation != viewState.value.bookSearchSessionId) return@collect
                    val rest = batch.results.filter { result ->
                        searchHitIsBefore(result, boundary, viewState.value.bookSearchReadingOrder) != true
                    }
                    val capped = appendSearchResults(generation, rest)
                    if (stepAfterReveal) {
                        stepAfterReveal = false
                        stepToFirstRevealedResult()
                    }
                    if (capped) throw SearchScanStopped()
                }
                completeSearchAheadScan(generation)
            } catch (stopped: SearchScanStopped) {
                completeSearchAheadScan(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateState { state ->
                    if (generation == viewState.value.bookSearchSessionId) state.copy(isSearchAheadLoading = false) else state
                }
            }
        }
    }

    private fun completeSearchAheadScan(generation: Long) {
        updateState { state ->
            if (generation == viewState.value.bookSearchSessionId) {
                state.copy(isSearchAheadLoading = false, searchAheadComplete = true)
            } else {
                state
            }
        }
    }

    /** Appends kept matches with contiguous indices; true when the result limit was hit. */
    private fun appendSearchResults(generation: Long, results: List<ReaderSearchResult>): Boolean {
        if (results.isEmpty()) return false
        var capped = false
        updateState { state ->
            if (generation != viewState.value.bookSearchSessionId) return@updateState state
            val capacity = SEARCH_RESULT_LIMIT - state.bookSearchResults.size
            val kept = results.take(capacity)
            capped = kept.size < results.size
            val startIndex = state.bookSearchResults.size
            state.copy(
                bookSearchResults = state.bookSearchResults + kept.mapIndexed { offset, result ->
                    result.copy(index = startIndex + offset, sessionId = generation)
                },
                bookSearchCount = startIndex + kept.size,
                bookSearchCapped = state.bookSearchCapped || capped,
            )
        }
        refreshSearchDecorations()
        return capped
    }

    private fun stepToFirstRevealedResult() {
        val state = viewState.value
        val split = state.searchAheadSplitIndex ?: return
        val result = state.bookSearchResults.firstOrNull { it.index >= split } ?: return
        navigateKeepingAudio(searchResult = result) { goToSearchResult(result) }
    }

    private fun goToSearchResult(result: ReaderSearchResult) {
        val state = viewState.value
        if (result.sessionId != state.bookSearchSessionId) return
        analytics.logEvent(ProductAnalyticsEvent.SearchResultSelected(SearchScope.Book, result.index))
        // Recent searches store no match counts: while hidden, a count would leak the rest.
        val recent = RecentBookSearch(state.bookSearchQuery.trim(), 0, state.bookSearchComplete)
        val recents = (listOf(recent) + state.bookSearchRecents.filterNot { it.query == recent.query }).take(8)
        saveUserPreferenceUseCase(PreferencesKey.RecentBookSearches(serverId, bookUuid), recents)
        updateState { it.copy(isBookSearchVisible = false, selectedSearchIndex = result.index,
            isFindBarVisible = true, searchOrigin = if (it.selectedSearchIndex == null) it.currentPosition else it.searchOrigin,
            searchPageTurns = if (it.selectedSearchIndex == null) 0 else it.searchPageTurns, bookSearchRecents = recents) }
        bookController.goToSearchResult(result)
        refreshSearchDecorations()
    }

    private fun stepSearchResult(delta: Int) {
        val state = viewState.value
        val current = state.bookSearchResults.indexOfFirst { it.index == state.selectedSearchIndex }
        val result = state.bookSearchResults.getOrNull(current + delta)
        if (result == null) {
            // Stepping past the last read match offers to search the rest of the book.
            if (delta > 0 && state.searchBoundary != null && !state.searchAheadRevealed) {
                updateState { it.copy(showSearchContinuePrompt = true) }
            }
            return
        }
        updateState { it.copy(showSearchContinuePrompt = false) }
        navigateKeepingAudio(searchResult = result) { goToSearchResult(result) }
    }

    private fun refreshSearchDecorations() {
        val state = viewState.value
        val selected = state.selectedSearchIndex ?: return
        bookController.decorateSearch(state.bookSearchResults, selected, searchAccent, searchSoft, searchOnAccent, searchEink)
    }

    private fun closeBookSearch() {
        bookSearchJob?.cancel()
        bookSearchAheadJob?.cancel()
        bookSearchGeneration++
        bookController.clearSearchDecorations()
        updateState { it.copy(isBookSearchVisible = false, isBookSearchLoading = false,
            selectedSearchIndex = null, isFindBarVisible = false, searchOrigin = null,
            searchAheadRevealed = false, searchAheadSplitIndex = null, showSearchContinuePrompt = false) }
    }

    /**
     * Search and jump origins are temporary: they fade after a few page turns.
     */
    private fun pageTurned() {
        updateState { state ->
            val turns = state.searchPageTurns + 1
            val jumpTurns = state.jumpOriginPageTurns + 1
            state.copy(
                searchPageTurns = turns,
                searchOrigin = if (turns >= 3) null else state.searchOrigin,
                jumpOriginPageTurns = jumpTurns,
                jumpOrigin = if (jumpTurns >= 3) null else state.jumpOrigin,
                // The settle bar yields at the first page turn (spec §2).
                positionSettleBar = null,
            )
        }
    }

    private fun seekToChapterProgress(progression: Double) {
        val position = viewState.value.currentPosition ?: return
        val target = progression.coerceIn(0.0, 1.0)
        beginNavigation(ReaderNavigationMethod.ProgressSlider) {
            it.href.substringBefore('#') == position.href.substringBefore('#') &&
                it.progression?.let { progress -> kotlin.math.abs(progress - target) <= 0.05 } == true
        }
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
        analytics.logEvent(ProductAnalyticsEvent.ListeningSourceChanged(
            previous = if (state.listenSource == ListenSource.DEVICE_VOICE) UsageMode.Tts else UsageMode.ReadAloud,
            current = if (target == ListenSource.DEVICE_VOICE) UsageMode.Tts else UsageMode.ReadAloud,
        ))
        refreshProductUsage()
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
        beginNavigation(ReaderNavigationMethod.Toc) { it.href.substringBefore('#') == href.substringBefore('#') }
        bookController.goToChapter(href)
        updateState {
            it.copy(
                isContentsVisible = false,
                jumpOrigin = currentPosition ?: it.currentPosition,
                jumpOriginPageTurns = 0,
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

    /** Bookmarks, highlights and notes; see [ReaderSavedItems]. */
    private val savedItems: ReaderSavedItems by lazy {
        ReaderSavedItems(
            scope = viewModelScope,
            bookController = { bookController },
            state = { viewState.value.saved },
            update = { change -> updateState { state -> state.copy(saved = change(state.saved)) } },
            context = {
                val state = viewState.value
                ReaderSavedContext(
                    position = state.currentPosition,
                    bookTitle = state.bookTitle,
                    bookAuthor = state.bookAuthor.takeIf { author -> author.isNotBlank() },
                    isListening = state.isListening,
                    bookLanguage = state.bookLanguage,
                    chapterTitleFor = { href ->
                        state.tableOfContents.firstOrNull { item ->
                            normaliseTocHref(item.href) == normaliseTocHref(href)
                        }?.title
                    },
                )
            },
            listeningSentence = ::listeningSentence,
            navigate = ::goToSaved,
            openSearch = { query ->
                if (!viewState.value.isBookSearchVisible) toggleBookSearch()
                searchBook(query, submitted = true)
            },
            observeBookItems = observeBookSavedItemsUseCase,
            saveItems = saveSavedItemsUseCase,
            deleteItem = deleteSavedItemUseCase,
            restoreItem = restoreSavedItemUseCase,
            observeSyncState = observeSavedSyncStateUseCase,
            resolveBook = resolveSavedBookUseCase,
            fileSharer = fileSharer,
            pendingJump = pendingSavedJump,
            dictionary = dictionaryService,
            tts = { ttsController },
            onError = { error, message -> analytics.logException(error, message) },
        )
    }

    private fun handleSaved(action: SavedAction) {
        if (action == SavedAction.ToggleBookmark) {
            analytics.logEvent(
                if (viewState.value.saved.pageBookmark == null) ReaderAnalyticsEvent.BookmarkAdded(bookUuid = bookUuid)
                else ReaderAnalyticsEvent.BookmarkDeleted(bookUuid = bookUuid),
            )
        }
        savedItems.handle(action)
    }

    /** The sentence being read aloud, for a bookmark made while listening. */
    private fun listeningSentence(): ListeningSentence? {
        val state = viewState.value
        if (!state.isListening) return null
        return when (state.listenSource) {
            ListenSource.NARRATION -> audioController.currentAudioLocator.value?.locator?.let { locator ->
                ListeningSentence(
                    href = locator.href,
                    mediaType = locator.type,
                    elementId = locator.fragments?.firstOrNull(),
                    text = null,
                    audioMs = state.currentAudioPositionMs.takeIf { ms -> ms > 0 },
                )
            }
            ListenSource.DEVICE_VOICE -> ttsCurrentSentence?.let { sentence ->
                val position = state.currentPosition ?: return null
                ListeningSentence(
                    href = position.href,
                    mediaType = position.type,
                    elementId = sentence.elementId,
                    text = sentence.text,
                    audioMs = null,
                )
            }
        }
    }

    /**
     * Opens a saved item's place: on its own text when it is in this copy of the book,
     * otherwise at the same point of the book. Audio keeps going from there.
     */
    private fun goToSaved(result: ReaderSearchResult) {
        val inThisCopy = bookController.searchReadingOrder().any { href ->
            normaliseTocHref(href) == normaliseTocHref(result.href)
        }
        val origin = viewState.value.currentPosition
        navigateKeepingAudio(searchResult = result.takeIf { inThisCopy }) {
            beginNavigation(ReaderNavigationMethod.Bookmark) { position ->
                normaliseTocHref(position.href) == normaliseTocHref(result.href)
            }
            if (inThisCopy) {
                bookController.goToSearchResult(result)
            } else {
                result.totalProgression?.let { progression ->
                    viewModelScope.launch { bookController.goToTotalProgression(progression) }
                }
            }
            updateState {
                it.copy(
                    isContentsVisible = false,
                    jumpOrigin = origin,
                    jumpOriginPageTurns = 0,
                )
            }
        }
    }

    fun close(closeSource: ReaderCloseSource = ReaderCloseSource.CloseButton) {
        // Leaving the reader must never wait on persistence or the network. The toolbar
        // arrow is the only visible way out and blocking here made it look dead. The work
        // runs on a NonCancellable coroutine so it still completes after navigation clears
        // this ViewModel (same pattern as AudiobookPlayerViewModel.close()); the sync
        // outbox holds the reading position even if the checkpoint below never runs.
        if (hasRequestedClose) return
        finishNavigation(ProductOutcome.Cancelled)
        hasRequestedClose = true
        openTracker.complete(ProductOutcome.Cancelled, "closed_before_content")
        usageSession.finish(UsageEndReason.Closed)
        // Kept for the statistics row; endRecapSession() clears it.
        val closedRecapSessionId = recapSessionId
        val readingDurationMs = statisticsTimer.finish() ?: 0L
        val endTime = nowMillis()
        saveStatisticsSession(readingDurationMs, endTime, closedRecapSessionId)
        endRecapSession()
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
            val progressPercent = currentState.currentPosition?.totalProgression
                ?.let { (it * 100).toInt() } ?: 0

            analytics.logEvent(
                ReaderAnalyticsEvent.BookClosed(
                    bookUuid = bookUuid,
                    readingDurationMs = readingDurationMs,
                    progressPercent = progressPercent,
                )
            )

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
        // Playback turns pages on its own; those turns are not pages the reader read.
        readingSpeedTracker.setListening(isPlaying)
        if (bookOpenedTimestamp > 0L) statisticsTimer.setActive(isReaderVisible || isPlaying)
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
        if (currentState.positionConflict != null || currentState.linkedResumeOffer != null) return null
        val audioPositionMs = currentState.currentAudioPositionMs
        val currentPosition = currentState.currentPosition

        if (audioPositionMs <= 0 || currentState.listenSource != ListenSource.NARRATION) {
            return null
        }
        if (currentPosition == null) {
            return null
        }
        if (!restoredPositionSaveGuard.shouldSave(currentPosition, audioPositionMs)) return null


        val position = createPositionDomainModel(currentPosition, audioPositionMs)
        positionSaveCoordinator.submit(position)
        return position
    }

    /** @return the final position saved, or null when there was none. */
    private suspend fun saveCurrentPositionForClose(): PositionDomainModel? {
        val currentState = viewState.value
        if (currentState.positionConflict != null || currentState.linkedResumeOffer != null) return null
        val currentPosition = currentState.currentPosition ?: return null
        val audioPositionMs = currentState.currentAudioPositionMs
            .takeIf { currentState.isReadAloud && currentState.listenSource == ListenSource.NARRATION && it > 0 }
        if (!restoredPositionSaveGuard.shouldSave(currentPosition, audioPositionMs)) return null
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
        finishNavigation(ProductOutcome.Cancelled)
        openTracker.complete(ProductOutcome.Cancelled, "reader_cleared")
        usageSession.finish(UsageEndReason.Cleared)
        statisticsTimer.finish()?.let { durationMs ->
            saveStatisticsSession(durationMs, nowMillis(), recapSessionId)
        }
        // Left without close(): still end the recap session.
        endRecapSession()
        currentBookTargetCheckpoint?.cancel()
        routineSyncScheduler.close()
        super.onCleared()
        readerScope.close()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun startRecapSession(
        serverId: String,
        bookUuid: String,
        position: PositionUiModel?,
        bookLanguage: String?,
    ) {
        if (recapSessionId != null) return
        val sessionId = Uuid.random().toString()
        recapSessionId = sessionId
        recapSessionRecorder.onSessionStarted(
            sessionId = sessionId,
            serverId = serverId,
            bookId = bookUuid,
            startPosition = position.toRecapPosition(),
            chapter = position?.let { RecapChapter(it.chapterIndex, it.title) },
            language = RecapLanguages.resolve(bookLanguage, Locale.current.language),
        )
        viewModelScope.launch {
            try {
                startRecapCapture(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable consent means no consent: stop capturing.
                recapCapture?.stop()
            }
        }
    }

    /** Capture runs only with consent, and stops for good if it's withdrawn. */
    private suspend fun startRecapCapture(sessionId: String) {
        if (!recapSettings.isCloudRecapsEnabled()) return
        if (recapSessionId != sessionId || recapCapture != null) return
        val capture = ReaderRecapCapture(
            sessionId = sessionId,
            recorder = recapSessionRecorder,
            scope = viewModelScope,
            readVisibleText = { bookController.getVisibleTextRange() },
        )
        recapCapture = capture
        capture.setForeground(isReaderVisible)
        viewState
            .map { state ->
                val deviceVoice = state.listenSource == ListenSource.DEVICE_VOICE
                // Extraction and synthesis gaps are still read-aloud, not reading.
                val readAloudActive = deviceVoice &&
                    (state.isNarrationLoading || state.isNarrationStartPending)
                Triple(
                    state.isPlaying || readAloudActive,
                    deviceVoice,
                    state.positionConflict != null || state.linkedResumeOffer != null,
                )
            }
            .distinctUntilChanged()
            .onEach { (playing, deviceVoice, prompted) ->
                capture.setPlayback(playing, deviceVoice)
                capture.setBlocked(prompted)
            }
            .launchIn(viewModelScope)
        viewState.value.currentPosition
            ?.toRecapPage(viewState.value.chapterInfo?.currentPage)
            ?.let { capture.onPageShown(it) }
        recapSettings.observeCloudRecapsEnabled().first { enabled -> !enabled }
        capture.stop()
    }

    private fun setReaderVisible(visible: Boolean) {
        if (isReaderVisible && !visible) usageSession.checkpoint(UsageEndReason.Background)
        isReaderVisible = visible
        refreshProductUsage()
        if (bookOpenedTimestamp > 0L) {
            statisticsTimer.setActive(visible || viewState.value.isPlaying)
        }
        recapCapture?.setForeground(visible)
    }

    private fun currentUsageMode(): UsageMode = when {
        !viewState.value.isPlaying -> UsageMode.Reading
        viewState.value.listenSource == ListenSource.DEVICE_VOICE -> UsageMode.Tts
        else -> UsageMode.ReadAloud
    }

    private fun refreshProductUsage() {
        val state = viewState.value
        val blocked = state.positionConflict != null || state.linkedResumeOffer != null || state.error != null
        val loading = state.isListening && (state.isNarrationLoading || state.isNarrationStartPending)
        val active = contentReady && !hasRequestedClose && !blocked && !loading && (isReaderVisible || state.isPlaying)
        usageSession.update(
            mode = currentUsageMode().takeIf { active },
            foreground = isReaderVisible,
            buffering = contentReady && !blocked && loading,
            listeningMode = if (state.listenSource == ListenSource.DEVICE_VOICE) UsageMode.Tts else UsageMode.ReadAloud,
        )
    }

    private fun observeProductUsage() {
        viewState.map { state ->
            listOf(state.isPlaying, state.isNarrationLoading, state.isNarrationStartPending,
                state.isListening, state.positionConflict != null, state.linkedResumeOffer != null, state.error != null,
                state.listenSource == ListenSource.DEVICE_VOICE)
        }.distinctUntilChanged().onEach { refreshProductUsage() }.launchIn(viewModelScope)
        viewModelScope.launch {
            while (true) {
                delay(30_000L)
                usageSession.checkpoint()
            }
        }
    }

    private fun saveStatisticsSession(durationMs: Long, endTime: Long, recapId: String?) {
        val state = viewState.value
        if (bookOpenedTimestamp <= 0L || durationMs <= 0L || state.bookTitle.isEmpty()) return
        val speed = readingSpeedTracker.establishedReadingSpeedWpm.value
            ?: state.currentSettings?.readingSpeedWpm ?: 250
        viewModelScope.launch(NonCancellable) {
            saveReadingSessionUseCase(
                bookUuid = bookUuid,
                bookTitle = state.bookTitle,
                bookType = state.bookType,
                startTime = bookOpenedTimestamp,
                endTime = endTime,
                durationMs = durationMs,
                pagesRead = readingSpeedTracker.sessionPagesRead().takeIf { pages -> pages > 0 },
                endProgression = state.currentPosition?.totalProgression,
                readingSpeedWpm = speed,
                recapSessionId = recapId,
            ).onFailure { error -> error.log(analytics, "ReaderViewModel: Failed to save statistics") }
        }
    }

    private fun endRecapSession() {
        val sessionId = recapSessionId ?: return
        recapSessionId = null
        val summary = recapCapture?.stop()
        recapCapture = null
        recapSessionRecorder.onSessionEnded(
            sessionId = sessionId,
            endPosition = viewState.value.currentPosition.toRecapPosition(),
            lastSentence = summary?.lastSentence,
            activeReadingMs = summary?.activeReadingMs
                ?: (nowMillis() - bookOpenedTimestamp).coerceAtLeast(0L),
        )
    }

    private fun PositionUiModel.toRecapPage(chapterPage: Int?): RecapPage? {
        if (href.isEmpty()) return null
        return RecapPage(
            href = href.substringBefore('#'),
            chapter = RecapChapter(chapterIndex, title),
            position = toRecapPosition(),
            chapterPage = chapterPage,
        )
    }

    private fun PositionUiModel?.toRecapPosition(): RecapPosition = RecapPosition(
        href = this?.href?.takeIf { it.isNotEmpty() },
        progression = this?.progression,
        totalProgression = this?.totalProgression,
    )

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

/** Ends a scan early at the spoiler boundary or the result limit; not a failure. */
private class SearchScanStopped : Exception()
