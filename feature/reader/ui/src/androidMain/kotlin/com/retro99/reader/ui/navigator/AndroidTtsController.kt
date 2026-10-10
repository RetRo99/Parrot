package com.retro99.reader.ui.navigator

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.reader.ui.publication.EpubPublication
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.reader.PreparedChapterVoice
import com.retro99.reader.ui.reader.preparedChapterVoice
import com.retro99.reader.ui.tts.PreparedChapterId
import com.retro99.reader.ui.tts.PreparedChapterState
import com.retro99.reader.ui.tts.PreparedVoiceSettings
import com.retro99.reader.ui.tts.TtsChapterPreparationForegroundService
import com.retro99.reader.ui.tts.TtsChapterPreparationInput
import com.retro99.reader.ui.tts.TtsChapterPreparationJob
import com.retro99.reader.ui.tts.TtsChapterPreparationRequest
import com.retro99.reader.ui.tts.TtsChapterPreparationState
import com.retro99.reader.ui.tts.TtsPreparationVoiceKind
import com.retro99.reader.ui.tts.TtsPreparedAudioStore
import com.retro99.reader.ui.tts.TtsPreparedChapterAudio
import com.retro99.reader.ui.tts.TtsPlaybackInfo
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsPreviewPlayer
import com.retro99.reader.ui.tts.TtsReadAloudEngine
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.reader.ui.tts.TtsSentenceChunker
import com.retro99.reader.ui.tts.TtsSpeechRate
import com.retro99.reader.ui.tts.TtsSynthesizer
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.reader.ui.tts.TtsVoicePreparationForegroundService
import com.retro99.reader.ui.tts.TtsVoicePreparationState
import com.retro99.reader.ui.tts.TtsVoicePreparationStateHolder
import com.retro99.reader.ui.tts.TtsModelManager
import com.retro99.reader.ui.tts.MediaPlaybackWordInterruption
import com.retro99.reader.ui.tts.ResolvedWord
import com.retro99.reader.ui.tts.SpeakWordCoordinator
import com.retro99.reader.ui.tts.SpeakWordFailure
import com.retro99.reader.ui.tts.SpeakWordState
import com.retro99.reader.ui.tts.SupertonicTermsStore
import com.retro99.reader.ui.tts.TtsAudioGenerator
import com.retro99.reader.ui.tts.TtsWordAudioSource
import com.retro99.reader.ui.tts.TtsWordPlayer
import com.retro99.reader.ui.tts.WordAudioInterruption
import com.retro99.reader.ui.tts.WordVoiceResolution
import com.retro99.reader.ui.tts.isSpeakableWordForm
import com.retro99.reader.ui.tts.prepareWordForSpeech
import com.retro99.reader.ui.tts.resolveWordVoice
import com.retro99.reader.ui.tts.neuralVoicePackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

@Scope(ReaderScope::class)
@Scoped(binds = [TtsController::class])
class AndroidTtsController(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
    private val epubPublication: EpubPublication,
    private val bookController: BookController,
    private val engine: TtsReadAloudEngine,
    private val synthesizer: TtsSynthesizer,
    private val audioGenerator: TtsAudioGenerator,
    private val modelManager: TtsModelManager,
    private val notificationPermissionHandler: NotificationPermissionHandler,
    private val preparationStateHolder: TtsVoicePreparationStateHolder,
    private val chapterPreparationJob: TtsChapterPreparationJob,
    private val preparedAudioStore: TtsPreparedAudioStore,
    private val previewPlayer: TtsPreviewPlayer,
    private val wordPlayer: TtsWordPlayer,
    private val mediaPlaybackController: MediaPlaybackController,
    private val supertonicTermsStore: SupertonicTermsStore,
    private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
) : TtsController {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var sentences: List<TtsSentence> = emptyList()
    private var sentencesChapterHref: String? = null

    /** Chapter of the sentences last handed to the engine. */
    private var engineChapterHref: String? = null
    private var lastLocator: LocatorState? = null
    private var rate: Float = 1f
    private var pitch: Float = 1f
    private var voiceId: String? = null
    private var isSentencePlaybackEnabled = false
    private var coverArtwork: ByteArray? = null
    private var isCoverArtworkLoaded = false
    private val readyChapterHref = MutableStateFlow<String?>(null)

    /** Bumped whenever something other than the job could change what is prepared. */
    private val preparedAudioRevision = MutableStateFlow(0)

    private val attempts = TtsPlaybackAttempts(
        scope = controllerScope,
        startupTimeoutMs = TTS_PLAYBACK_START_TIMEOUT_MS,
        engine = engine,
        synthesizer = synthesizer,
        selectedVoiceId = { voiceId },
        restartAtIndex = { index -> restartAtIndex(index) },
    )

    override val playbackOperations: SharedFlow<TtsPlaybackOperation> = attempts.operations

    override val isPlaying: Flow<Boolean> = engine.isPlaying

    override val isLoading: Flow<Boolean> = combine(
        attempts.isStartPending,
        engine.isLoading,
        previewPlayer.state,
    ) { isRequestPending, isEngineLoading, previewState ->
        previewState == TtsPreviewState.IDLE && (isRequestPending || isEngineLoading)
    }.distinctUntilChanged()

    override val isPlaybackStartPending: Flow<Boolean> = attempts.isStartPending

    override val chapterCompleted: Flow<String> = engine.chapterCompleted
        .mapNotNull { lastLocator?.href }

    override val currentSentence: Flow<TtsSentence?> = engine.currentSentence

    override val finishedSentences: Flow<FinishedTtsSentence> = engine.finishedSentences
        .map { sentence -> FinishedTtsSentence(engineChapterHref, sentence) }

    override val sentenceCount: Flow<Int> = engine.sentenceCount

    override val previewState: Flow<TtsPreviewState> = previewPlayer.state

    override val voicePreparationState: Flow<TtsVoicePreparationState> =
        preparationStateHolder.state

    private var previewJob: Job? = null
    private var resumeNarrationAfterPreview = false

    private val wordCoordinator = SpeakWordCoordinator(
        scope = controllerScope,
        audioSource = TtsWordAudioSource(audioGenerator = audioGenerator, engine = engine),
        player = wordPlayer,
        interruptions = listOf(
            ReadAloudWordInterruption(),
            MediaPlaybackWordInterruption(mediaPlaybackController),
        ),
    )

    override val wordState: Flow<SpeakWordState> = wordCoordinator.state

    override val wordFailures: Flow<SpeakWordFailure> = wordCoordinator.failures

    private inner class ReadAloudWordInterruption : WordAudioInterruption {

        // A gap between sentences is still narration to interrupt and resume (TTS-F26).
        override fun isPlayingNow(): Boolean = engine.isSessionRunning.value

        override fun pause() {
            this@AndroidTtsController.pause()
        }

        override fun resume() {
            this@AndroidTtsController.resume()
        }
    }

    init {
        engine.stopIfPlayingAnotherBook(epubPublication.bookUuid)

        controllerScope.launch {
            bookController.currentLocator.collect { locator ->
                val previousLocator = lastLocator
                lastLocator = locator
                if (shouldReloadTtsChapter(
                        previousHref = previousLocator?.href,
                        newHref = locator.href,
                        narratedHref = engineChapterHref.takeIf { engine.currentSentence.value != null },
                    )
                ) {
                    sentences = emptyList()
                    sentencesChapterHref = null
                    engine.stop()
                    if (isSentencePlaybackEnabled) {
                        loadChapterSentences()
                    }
                }
                readyChapterHref.value = locator.href
            }
        }

        controllerScope.launch {
            engine.currentSentence
                .combine(engine.currentSentenceDurationMs) { sentence, durationMs ->
                    sentence to durationMs
                }
                .distinctUntilChanged()
                .collect { (sentence, durationMs) ->
                    val elementId = sentence?.elementId ?: return@collect
                    if (durationMs <= 0L) return@collect
                    val locator = lastLocator ?: return@collect
                    bookController.applyHighlightWithPageTurn(
                        locator = locator.withFragment(elementId),
                        sentenceDurationMs = durationMs,
                    )
                }
        }

        controllerScope.launch {
            wordCoordinator.failures.collect { failure ->
                analytics.logEvent(
                    ReaderAnalyticsEvent.SpeakWordFailed(
                        voiceId = failure.voiceId,
                        isNeural = failure.isNeural,
                    ),
                )
            }
        }
    }

    override suspend fun hasReadableContent(): Boolean {
        return bookController.hasReadableContent()
    }

    override suspend fun availableVoices(): List<TtsVoice> {
        synthesizer.awaitReady()
        modelManager.refreshManifestIfStale()
        return synthesizer.availableVoices()
    }

    override fun selectVoice(voiceId: String?) {
        this.voiceId = voiceId
        restartForSettingsChange()
    }

    override fun previewVoice(voiceId: String?, text: String) {
        if (text.isBlank()) return
        // A gap between sentences counts as narration: the preview pauses it and the
        // preview's end resumes it, as it would mid-sentence (TTS-F26).
        resumeNarrationAfterPreview =
            resumeNarrationAfterPreview || engine.isSessionRunning.value
        if (engine.isSessionRunning.value) {
            engine.pause()
        }

        previewJob?.cancel()
        previewJob = null
        previewPlayer.stop()
        val nextPreviewJob = controllerScope.launch(start = CoroutineStart.LAZY) {
            val runningJob = currentCoroutineContext()[Job]
            try {
                val settings = getReaderSettingsUseCase().first()
                val started = previewPlayer.play(
                    chunks = TtsSentenceChunker.chunk(text),
                    voiceId = voiceId,
                    rate = TtsSpeechRate.coerce(settings.ttsRate),
                    pitch = settings.ttsPitch,
                )
                if (!started) return@launch

                val playbackStarted = withTimeoutOrNull(PREVIEW_START_TIMEOUT_MS) {
                    previewPlayer.state
                        .filter { state -> state == TtsPreviewState.SPEAKING }
                        .first()
                    true
                } ?: false
                if (playbackStarted) {
                    withTimeoutOrNull(PREVIEW_MAX_DURATION_MS) {
                        previewPlayer.state
                            .filter { state -> state == TtsPreviewState.IDLE }
                            .first()
                    }
                }
            } finally {
                if (previewJob === runningJob) {
                    previewJob = null
                    previewPlayer.stop()
                    resumeNarrationAfterPreview()
                }
            }
        }
        previewJob = nextPreviewJob
        nextPreviewJob.start()
    }

    override fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer.stop()
        resumeNarrationAfterPreview()
    }

    override suspend fun canSpeakWord(language: String): Boolean =
        currentWordVoice(language) is WordVoiceResolution.Usable

    override fun speakWord(word: String, language: String) {
        controllerScope.launch {
            if (!isSpeakableWordForm(word)) return@launch
            val resolution = currentWordVoice(language)
            if (resolution !is WordVoiceResolution.Usable) return@launch
            val settings = getReaderSettingsUseCase().first()
            wordCoordinator.speak(
                ResolvedWord(
                    text = prepareWordForSpeech(word),
                    voiceId = resolution.voiceId,
                    voiceName = resolution.name,
                    isNeural = resolution.isNeural,
                    rate = minOf(TtsSpeechRate.coerce(settings.ttsRate), MAX_WORD_RATE),
                ),
            )
        }
    }

    override fun stopWord() {
        wordCoordinator.stop()
    }

    override fun warmUpWordVoice(language: String) {
        controllerScope.launch {
            if (isPowerSaveMode(context)) return@launch
            val resolution = currentWordVoice(language)
            if (resolution !is WordVoiceResolution.Usable || !resolution.isNeural) return@launch
            synthesizer.warmUp(resolution.voiceId)
        }
    }

    /**
     * Resolves the word voice fresh right before every use, so the hard gates (pack
     * downloaded, Supertonic terms accepted, no preparation running) hold at the moment of
     * synthesis and a word request can never reach the pack install path.
     */
    private suspend fun currentWordVoice(language: String): WordVoiceResolution {
        if (!synthesizer.awaitReady()) return WordVoiceResolution.Hidden
        val settings = getReaderSettingsUseCase().first()
        return resolveWordVoice(
            selectedVoiceId = settings.ttsVoiceId,
            voices = synthesizer.availableVoices(),
            defaultSystemVoiceId = synthesizer.defaultVoice()?.id,
            hasAcceptedSupertonicTerms = supertonicTermsStore.hasAcceptedCurrentTerms(),
            preparingPackage = (preparationStateHolder.state.value as? TtsVoicePreparationState.Running)
                ?.voicePackage,
            language = language,
        )
    }

    override suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean {
        val voicePackage = voiceId.neuralVoicePackage()
        if (voicePackage == null) {
            return synthesizer.prepareVoice(voiceId, onProgress = onProgress)
        }
        val neuralVoiceId = requireNotNull(voiceId)

        val activePreparation = preparationStateHolder.state.value
        if (activePreparation is TtsVoicePreparationState.Running) {
            if (activePreparation.voicePackage != voicePackage) return false
            return awaitVoicePreparation(voicePackage, onProgress)
        }

        val voice = synthesizer.availableVoices().firstOrNull { candidate ->
            candidate.id == neuralVoiceId
        }
        if (voice?.needsDownload != true && !updateToLatest) {
            return synthesizer.prepareVoice(neuralVoiceId, onProgress = onProgress)
        }

        notificationPermissionHandler.ensurePermission()
        val initialProgress = TtsPreparationProgress.Downloading(
            downloadedBytes = 0L,
            totalBytes = voice?.downloadSizeBytes,
        )
        val shouldStartService = preparationStateHolder.begin(voicePackage, initialProgress)
        if (shouldStartService) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    TtsVoicePreparationForegroundService.createStartIntent(
                        context,
                        neuralVoiceId,
                        updateToLatest,
                    ),
                )
            } catch (error: Exception) {
                Log.e(TAG, "Failed to start neural voice preparation service", error)
                preparationStateHolder.markFailed(voicePackage)
            }
        }
        if (!shouldStartService) {
            val runningState = preparationStateHolder.state.value
            if (
                runningState !is TtsVoicePreparationState.Running ||
                runningState.voicePackage != voicePackage
            ) {
                return false
            }
        }
        return awaitVoicePreparation(voicePackage, onProgress)
    }

    private suspend fun awaitVoicePreparation(
        voicePackage: NeuralVoicePackage,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean {
        val finalState = preparationStateHolder.state
            .onEach { state ->
                if (
                    state is TtsVoicePreparationState.Running &&
                    state.voicePackage == voicePackage
                ) {
                    onProgress(state.progress)
                }
            }
            .first { state ->
                state == TtsVoicePreparationState.Idle ||
                        (
                                state is TtsVoicePreparationState.Complete &&
                                        state.voicePackage == voicePackage
                                ) ||
                        (
                                state is TtsVoicePreparationState.Failed &&
                                        state.voicePackage == voicePackage
                                )
            }
        return when (finalState) {
            is TtsVoicePreparationState.Complete -> true
            TtsVoicePreparationState.Idle -> throw CancellationException(
                "Neural voice preparation cancelled",
            )

            is TtsVoicePreparationState.Failed -> false
            is TtsVoicePreparationState.Running -> false
        }
    }

    override suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean {
        resumeNarrationAfterPreview = false
        stopPreview()
        stop()
        return synthesizer.deleteNeuralVoicePackage(voicePackage)
    }

    override suspend fun enableSentencePlayback(): Boolean {
        isSentencePlaybackEnabled = true
        return loadChapterSentences()
    }

    override fun disableSentencePlayback() {
        isSentencePlaybackEnabled = false
        attempts.cancelActive()
        sentences = emptyList()
        sentencesChapterHref = null
        engine.stop()
    }

    override fun playFromSentence(fragmentId: String, chapterHref: String?) {
        if (!isSentencePlaybackEnabled || fragmentId.isBlank()) return

        attempts.request(TtsPlaybackAction.SENTENCE_TAP) { attempt ->
            val requestedChapterHref = chapterHref ?: lastLocator?.href
            if (!awaitChapter(requestedChapterHref)) {
                return@request TtsPlaybackFailureReason.CHAPTER_UNAVAILABLE
            }
            if (sentencesChapterHref != requestedChapterHref) {
                sentences = emptyList()
                sentencesChapterHref = null
            }
            if (sentences.isEmpty() && !loadChapterSentences(requestedChapterHref)) {
                return@request TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
            }
            var sentenceIndex = sentences.indexOfFirst { sentence ->
                sentence.elementId == fragmentId
            }
            if (sentenceIndex < 0) {
                sentences = emptyList()
                sentencesChapterHref = null
                if (!loadChapterSentences(requestedChapterHref)) {
                    return@request TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
                }
                sentenceIndex = sentences.indexOfFirst { sentence ->
                    sentence.elementId == fragmentId
                }
            }
            if (sentenceIndex < 0) return@request TtsPlaybackFailureReason.CONTENT_UNAVAILABLE

            startPlayback(attempt, sentenceIndex)
        }
    }

    override fun playFromChapterStart(chapterHref: String) {
        if (!isSentencePlaybackEnabled) return

        attempts.request(TtsPlaybackAction.CHAPTER) { attempt ->
            if (!awaitChapter(chapterHref)) {
                return@request TtsPlaybackFailureReason.CHAPTER_UNAVAILABLE
            }
            if (sentencesChapterHref != chapterHref) {
                sentences = emptyList()
                sentencesChapterHref = null
            }
            startPlayback(attempt, sentenceIndex = 0)
        }
    }

    override fun togglePlayback() {
        // A session waiting for its next sentence is a playing one: the button pauses it,
        // it does not try to start it again (TTS-F26).
        if (engine.isSessionRunning.value) {
            engine.pause()
            return
        }
        attempts.onPlayPressed(
            resume = { attempt ->
                attempts.prepareStart(attempt)
                engine.resume()
                null
            },
            freshStart = { attempt -> startPlayback(attempt) },
        )
    }

    override fun pause() {
        engine.pause()
    }

    override fun resume() {
        engine.resume()
    }

    override fun stop() {
        attempts.cancelActive()
        engine.stop()
    }

    override fun setRate(rate: Float) {
        this.rate = TtsSpeechRate.coerce(rate)
        restartForSettingsChange()
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
        restartForSettingsChange()
    }

    private fun restartForSettingsChange() {
        // The voice, speed or pitch decides which prepared audio counts, so the row has to
        // be read again for the new setting.
        refreshPreparedAudio()
        attempts.onSettingsChanged()
    }

    /** Plays [index] again with the voice, speed and pitch in force right now. */
    private suspend fun restartAtIndex(index: Int) {
        engine.playFrom(
            index = index,
            voiceId = voiceId,
            rate = rate,
            pitch = pitch,
            completeChapterOnEnd = true,
            showPlaybackNotification = true,
        )
    }

    override fun skipToNextSentence() {
        engine.skipToNextSentence()
    }

    override fun skipToPreviousSentence() {
        engine.skipToPreviousSentence()
    }

    override fun close() {
        stopPreview()
        wordCoordinator.close()
        wordPlayer.close()
        previewPlayer.close()
        controllerScope.cancel()
    }

    private fun resumeNarrationAfterPreview() {
        if (!resumeNarrationAfterPreview) return
        resumeNarrationAfterPreview = false
        if (engine.currentSentence.value != null) {
            attempts.request(TtsPlaybackAction.PREVIEW_RESUME) {
                attempts.prepareStart(it)
                engine.resume()
                null
            }
        } else if (isSentencePlaybackEnabled) {
            attempts.request(TtsPlaybackAction.PREVIEW_RESUME) { attempt ->
                startPlayback(attempt)
            }
        }
    }

    /**
     * Starts where there is something to read: here, or in the next chapter that has text
     * (TTS-F14). The decision itself is [startAtFirstChapterWithText], so it can be tested.
     */
    private suspend fun startPlayback(
        attempt: TtsPlaybackAttempt,
        sentenceIndex: Int? = null,
    ): TtsPlaybackFailureReason? {
        val readingOrder = bookController.readingOrderHrefs()
        return startAtFirstChapterWithText(
            maxChapterMoves = readingOrder.size,
            hasSentencesHere = { sentences.isNotEmpty() || loadChapterSentences() },
            startHere = { startLoadedPlayback(attempt, sentenceIndex) },
            goToNextChapter = { goToNextChapter(readingOrder) },
            startAtChapterStart = { startLoadedPlayback(attempt, sentenceIndex = 0) },
        )
    }

    /** Moves one chapter forward in the spine; false at the end of the book. */
    private suspend fun goToNextChapter(readingOrder: List<String>): Boolean {
        val currentHref = lastLocator?.href ?: return false
        val currentIndex = readingOrder.indexOf(currentHref)
        if (currentIndex < 0) return false
        val nextHref = readingOrder.getOrNull(currentIndex + 1) ?: return false
        return awaitChapter(nextHref)
    }

    private suspend fun startLoadedPlayback(
        attempt: TtsPlaybackAttempt,
        sentenceIndex: Int? = null,
    ): TtsPlaybackFailureReason? {
        val settings = getReaderSettingsUseCase().first()
        voiceId = settings.ttsVoiceId
        rate = TtsSpeechRate.coerce(settings.ttsRate)
        pitch = settings.ttsPitch

        if (!notificationPermissionHandler.ensurePermission()) {
            return TtsPlaybackFailureReason.PERMISSION_DENIED
        }
        attempts.prepareStart(attempt)
        engine.setPlaybackOperationCorrelationId(attempt.correlationId)
        engine.setPlaybackInfo(createPlaybackInfo())
        engine.setSentences(sentences)
        engineChapterHref = sentencesChapterHref
        engine.playFrom(
            index = sentenceIndex ?: resolveStartIndex(),
            voiceId = voiceId,
            rate = rate,
            pitch = pitch,
            showPlaybackNotification = true,
        )
        return null
    }

    private suspend fun createPlaybackInfo(): TtsPlaybackInfo {
        if (!isCoverArtworkLoaded) {
            coverArtwork = epubPublication.cover()
            isCoverArtworkLoaded = true
        }
        return TtsPlaybackInfo(
            serverId = epubPublication.serverId,
            bookUuid = epubPublication.bookUuid,
            bookType = epubPublication.bookType,
            bookTitle = epubPublication.publication.metadata.title ?: DEFAULT_BOOK_TITLE,
            chapterTitle = lastLocator?.title,
            coverArtwork = coverArtwork,
        )
    }

    private suspend fun awaitChapter(chapterHref: String?): Boolean {
        if (chapterHref == null) return true
        if (lastLocator?.href != chapterHref) {
            bookController.goToChapter(chapterHref)
        }
        return withTimeoutOrNull(CHAPTER_READY_TIMEOUT_MS) {
            readyChapterHref.first { readyHref -> readyHref == chapterHref }
            true
        } ?: false
    }

    private suspend fun loadChapterSentences(
        expectedChapterHref: String? = lastLocator?.href,
    ): Boolean {
        val chapterHref = expectedChapterHref
            ?: withTimeoutOrNull(CHAPTER_READY_TIMEOUT_MS) {
                readyChapterHref.first { href -> href != null }
            }
            ?: return false
        if (lastLocator?.href != chapterHref) return false

        val chapterSentences = bookController.getChapterSentences()
        if (chapterSentences.isEmpty()) return false
        if (lastLocator?.href != chapterHref) return false

        sentences = chapterSentences
        sentencesChapterHref = chapterHref
        if (isSentencePlaybackEnabled) {
            bookController.enableSentenceTapDetection()
        }
        return true
    }

    private suspend fun resolveStartIndex(): Int {
        val visibleId = bookController.getVisibleSentenceId() ?: return 0
        return sentences.indexOfFirst { sentence -> sentence.elementId == visibleId }
            .coerceAtLeast(0)
    }

    private fun LocatorState.withFragment(fragmentId: String): LocatorState =
        copy(fragments = listOf(fragmentId))

    override val chapterPreparation: Flow<TtsChapterPreparationState> = chapterPreparationJob.state

    /**
     * Re-read whenever the one preparation moves or the voice, speed or pitch changes: the
     * store is a few small files, and nothing else can change what is prepared for a chapter.
     */
    override fun preparedChapterAudio(chapterHref: String): Flow<TtsPreparedChapterAudio> =
        combine(chapterPreparationJob.state, preparedAudioRevision) { _, _ -> Unit }
            .map { withContext(Dispatchers.IO) { readPreparedChapterAudio(chapterHref) } }

    override suspend fun prepareChapter(chapterHref: String): TtsChapterPreparationRequest {
        val voice = runCatching { synthesizer.availableVoices() }.getOrDefault(emptyList())
            .firstOrNull { candidate -> candidate.id == voiceId }
        val usability = preparedChapterVoice(voice, supertonicTermsStore.hasAcceptedCurrentTerms())
        if (usability != PreparedChapterVoice.USABLE) {
            return TtsChapterPreparationRequest.VOICE_UNUSABLE
        }
        if (!notificationPermissionHandler.ensurePermission()) {
            return TtsChapterPreparationRequest.NOTIFICATIONS_DENIED
        }
        val texts = chapterTextsFor(chapterHref)
        if (texts.isEmpty()) return TtsChapterPreparationRequest.UNAVAILABLE
        val request = chapterPreparationJob.start(
            TtsChapterPreparationInput(
                id = preparedChapterId(chapterHref),
                settings = preparedVoiceSettings(),
                voiceKind = if (voiceId.neuralVoicePackage() != null) {
                    TtsPreparationVoiceKind.NEURAL
                } else {
                    TtsPreparationVoiceKind.SYSTEM
                },
                texts = texts,
            ),
        )
        if (request == TtsChapterPreparationRequest.STARTED) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    TtsChapterPreparationForegroundService.createStartIntent(context),
                )
            } catch (error: Exception) {
                // The work itself is already running in the app-wide job; only the
                // notification and the process guarantee are lost.
                Log.e(TAG, "Failed to start chapter preparation service", error)
            }
        }
        refreshPreparedAudio()
        return request
    }

    override fun cancelChapterPreparation() {
        chapterPreparationJob.cancel()
    }

    override suspend fun deletePreparedChapter(chapterHref: String) {
        withContext(Dispatchers.IO) {
            runCatching { preparedAudioStore.store.delete(preparedChapterId(chapterHref)) }
                .onFailure { error -> Log.e(TAG, "Failed to delete prepared chapter", error) }
        }
        refreshPreparedAudio()
    }

    override suspend fun preparedAudioBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { preparedAudioStore.store.totalSize() }.getOrDefault(0L)
    }

    override suspend fun deleteAllPreparedAudio() {
        withContext(Dispatchers.IO) {
            runCatching { preparedAudioStore.store.deleteAll() }
                .onFailure { error -> Log.e(TAG, "Failed to delete prepared audio", error) }
        }
        refreshPreparedAudio()
    }

    private fun refreshPreparedAudio() {
        preparedAudioRevision.value += 1
    }

    /** The chapter on screen, as the reader loaded it; never another chapter's text. */
    private suspend fun chapterTextsFor(chapterHref: String): List<String> {
        if (sentencesChapterHref == chapterHref && sentences.isNotEmpty()) {
            return sentences.map { sentence -> sentence.text }
        }
        if (lastLocator?.href != chapterHref) return emptyList()
        return bookController.getChapterSentences().map { sentence -> sentence.text }
    }

    private fun preparedChapterId(chapterHref: String) = PreparedChapterId(
        bookId = epubPublication.bookUuid,
        serverId = epubPublication.serverId,
        chapterHref = chapterHref,
    )

    private fun preparedVoiceSettings() = PreparedVoiceSettings(
        voiceId = voiceId,
        modelVersion = runCatching { synthesizer.activeModelVersion(voiceId) }.getOrNull(),
        rate = TtsSpeechRate.coerce(rate),
        pitch = pitch,
    )

    private fun readPreparedChapterAudio(chapterHref: String): TtsPreparedChapterAudio {
        val state = runCatching {
            preparedAudioStore.store.state(preparedChapterId(chapterHref), preparedVoiceSettings())
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read prepared chapter state", error)
            return TtsPreparedChapterAudio.NotPrepared
        }
        return when (state) {
            PreparedChapterState.NotPrepared -> TtsPreparedChapterAudio.NotPrepared
            is PreparedChapterState.Partial -> TtsPreparedChapterAudio.Partial(state.done, state.total)
            is PreparedChapterState.Ready -> TtsPreparedChapterAudio.Ready(state.bytes)
            is PreparedChapterState.OtherSettings -> TtsPreparedChapterAudio.OtherSettings(
                voiceId = state.settings.voiceId,
                rate = state.settings.rate,
                pitch = state.settings.pitch,
                done = state.done,
                total = state.total,
                complete = state.complete,
            )
        }
    }

    private companion object {
        // Covers synthesis/player startup after notification permission is resolved.
        const val TTS_PLAYBACK_START_TIMEOUT_MS = 30_000L
        const val PREVIEW_START_TIMEOUT_MS = 30_000L
        const val PREVIEW_MAX_DURATION_MS = 60_000L
        const val CHAPTER_READY_TIMEOUT_MS = 5_000L
        const val DEFAULT_BOOK_TITLE = "Reading Aloud"
        const val TAG = "AndroidTtsController"
        const val MAX_WORD_RATE = 1f
    }
}

/** Warm-up is skipped in battery saver; there is no in-app power-save flag. */
private fun isPowerSaveMode(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
