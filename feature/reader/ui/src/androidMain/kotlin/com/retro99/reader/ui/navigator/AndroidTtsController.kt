package com.retro99.reader.ui.navigator

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.model.LocatorState
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.reader.ui.publication.EpubPublication
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsPlaybackStartException
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import java.util.UUID

@Scope(ReaderScope::class)
@Scoped(binds = [TtsController::class])
class AndroidTtsController(
    @Provided private val context: Context,
    private val epubPublication: EpubPublication,
    private val bookController: BookController,
    private val engine: TtsReadAloudEngine,
    private val synthesizer: TtsSynthesizer,
    private val modelManager: TtsModelManager,
    private val notificationPermissionHandler: NotificationPermissionHandler,
    private val preparationStateHolder: TtsVoicePreparationStateHolder,
    private val previewPlayer: TtsPreviewPlayer,
    private val getReaderSettingsUseCase: GetReaderSettingsUseCase,
) : TtsController {

    private data class PlaybackAttempt(
        val correlationId: String,
        val action: TtsPlaybackAction,
        val isRetry: Boolean,
        val startedAtMs: Long,
    )

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

    private val playbackRequestPending = MutableStateFlow(false)
    private val _playbackOperations = MutableSharedFlow<TtsPlaybackOperation>(extraBufferCapacity = 8)
    override val playbackOperations: SharedFlow<TtsPlaybackOperation> =
        _playbackOperations.asSharedFlow()

    private var activePlaybackAttempt: PlaybackAttempt? = null
    private val playbackLifecycle = TtsPlaybackOperationLifecycle(
        scope = controllerScope,
        startupTimeoutMs = TTS_PLAYBACK_START_TIMEOUT_MS,
        onStartupTimeout = {
            activePlaybackAttempt?.let { attempt ->
                finishPlaybackFailed(
                    attempt = attempt,
                    reasonCode = TtsPlaybackFailureReason.START_TIMEOUT,
                    error = IllegalStateException(
                        "TTS playback did not become active within the startup deadline",
                    ),
                )
            }
        },
    )
    private var previousPlaybackAttemptFailed = false

    override val isPlaying: Flow<Boolean> = engine.isPlaying

    override val isLoading: Flow<Boolean> = combine(
        playbackRequestPending,
        engine.isLoading,
        previewPlayer.state,
    ) { isRequestPending, isEngineLoading, previewState ->
        previewState == TtsPreviewState.IDLE && (isRequestPending || isEngineLoading)
    }.distinctUntilChanged()

    override val isPlaybackStartPending: Flow<Boolean> =
        playbackRequestPending.asStateFlow()

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

    init {
        engine.stopIfPlayingAnotherBook(epubPublication.bookUuid)

        controllerScope.launch {
            bookController.currentLocator.collect { locator ->
                val previousLocator = lastLocator
                lastLocator = locator
                if (previousLocator != null && previousLocator.href != locator.href) {
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
            engine.isPlaying.collect { isPlaying ->
                if (isPlaying) activePlaybackAttempt?.let(::finishPlaybackSucceeded)
            }
        }

        controllerScope.launch {
            engine.playbackFailures.collect { failure ->
                val attempt = activePlaybackAttempt
                if (attempt != null) {
                    finishPlaybackFailed(
                        attempt = attempt,
                        reasonCode = failure.reasonCode,
                        error = failure.error,
                    )
                } else {
                    previousPlaybackAttemptFailed = true
                    _playbackOperations.tryEmit(
                        TtsPlaybackOperation.Failed(
                            correlationId = failure.correlationId ?: UUID.randomUUID().toString(),
                            action = TtsPlaybackAction.ACTIVE_PLAYBACK,
                            isRetry = false,
                            durationMs = 0L,
                            reasonCode = failure.reasonCode,
                            error = failure.error,
                        ),
                    )
                }
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
        resumeNarrationAfterPreview =
            resumeNarrationAfterPreview || engine.isPlaying.value
        if (engine.isPlaying.value) {
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
        cancelActivePlaybackAttempt()
        sentences = emptyList()
        sentencesChapterHref = null
        engine.stop()
    }

    override fun playFromSentence(fragmentId: String, chapterHref: String?) {
        if (!isSentencePlaybackEnabled || fragmentId.isBlank()) return

        requestPlayback(TtsPlaybackAction.SENTENCE_TAP) { attempt ->
            val requestedChapterHref = chapterHref ?: lastLocator?.href
            if (!awaitChapter(requestedChapterHref)) {
                return@requestPlayback TtsPlaybackFailureReason.CHAPTER_UNAVAILABLE
            }
            if (sentencesChapterHref != requestedChapterHref) {
                sentences = emptyList()
                sentencesChapterHref = null
            }
            if (sentences.isEmpty() && !loadChapterSentences(requestedChapterHref)) {
                return@requestPlayback TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
            }
            var sentenceIndex = sentences.indexOfFirst { sentence ->
                sentence.elementId == fragmentId
            }
            if (sentenceIndex < 0) {
                sentences = emptyList()
                sentencesChapterHref = null
                if (!loadChapterSentences(requestedChapterHref)) {
                    return@requestPlayback TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
                }
                sentenceIndex = sentences.indexOfFirst { sentence ->
                    sentence.elementId == fragmentId
                }
            }
            if (sentenceIndex < 0) return@requestPlayback TtsPlaybackFailureReason.CONTENT_UNAVAILABLE

            startPlayback(attempt, sentenceIndex)
        }
    }

    override fun playFromChapterStart(chapterHref: String) {
        if (!isSentencePlaybackEnabled) return

        requestPlayback(TtsPlaybackAction.CHAPTER) { attempt ->
            if (!awaitChapter(chapterHref)) {
                return@requestPlayback TtsPlaybackFailureReason.CHAPTER_UNAVAILABLE
            }
            if (sentencesChapterHref != chapterHref) {
                sentences = emptyList()
                sentencesChapterHref = null
            }
            if (sentences.isEmpty() && !loadChapterSentences(chapterHref)) {
                return@requestPlayback TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
            }
            startPlayback(attempt, sentenceIndex = 0)
        }
    }

    override fun togglePlayback() {
        if (engine.isPlaying.value) {
            engine.pause()
            return
        }
        val isResuming = engine.currentSentence.value != null
        requestPlayback(if (isResuming) TtsPlaybackAction.RESUME else TtsPlaybackAction.CONTROLS) { attempt ->
            if (isResuming) {
                armPlaybackStartTimeout(attempt)
                engine.resume()
                null
            } else {
                startPlayback(attempt)
            }
        }
    }

    override fun stop() {
        cancelActivePlaybackAttempt()
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
        val index = engine.currentSentenceIndex
        if (index < 0) return

        if (engine.isPlaying.value) {
            controllerScope.launch {
                engine.playFrom(
                    index = index,
                    voiceId = voiceId,
                    rate = rate,
                    pitch = pitch,
                    completeChapterOnEnd = true,
                    showPlaybackNotification = true,
                )
            }
        } else {
            engine.stop()
        }
    }

    override fun skipToNextSentence() {
        engine.skipToNextSentence()
    }

    override fun skipToPreviousSentence() {
        engine.skipToPreviousSentence()
    }

    override fun close() {
        stopPreview()
        previewPlayer.close()
        controllerScope.cancel()
    }

    private fun resumeNarrationAfterPreview() {
        if (!resumeNarrationAfterPreview) return
        resumeNarrationAfterPreview = false
        if (engine.currentSentence.value != null) {
            requestPlayback(TtsPlaybackAction.PREVIEW_RESUME) {
                armPlaybackStartTimeout(it)
                engine.resume()
                null
            }
        } else if (isSentencePlaybackEnabled) {
            requestPlayback(TtsPlaybackAction.PREVIEW_RESUME) { attempt ->
                startPlayback(attempt)
            }
        }
    }

    private suspend fun startPlayback(
        attempt: PlaybackAttempt,
        sentenceIndex: Int? = null,
    ): TtsPlaybackFailureReason? {
        if (sentences.isEmpty() && !loadChapterSentences()) {
            return TtsPlaybackFailureReason.CONTENT_UNAVAILABLE
        }

        val settings = getReaderSettingsUseCase().first()
        voiceId = settings.ttsVoiceId
        rate = TtsSpeechRate.coerce(settings.ttsRate)
        pitch = settings.ttsPitch

        if (!notificationPermissionHandler.ensurePermission()) {
            return TtsPlaybackFailureReason.PERMISSION_DENIED
        }
        armPlaybackStartTimeout(attempt)
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

    private fun requestPlayback(
        action: TtsPlaybackAction,
        start: suspend (PlaybackAttempt) -> TtsPlaybackFailureReason?,
    ) {
        val attempt = beginPlaybackAttempt(action) ?: return
        playbackLifecycle.launchRequest {
            try {
                when (val reason = start(attempt)) {
                    null -> awaitPlaybackStart(attempt)
                    TtsPlaybackFailureReason.PERMISSION_DENIED,
                    TtsPlaybackFailureReason.OPERATION_CANCELLED ->
                        finishPlaybackCancelled(attempt, reason)

                    else -> finishPlaybackFailed(attempt, reason, error = null)
                }
            } catch (error: CancellationException) {
                finishPlaybackCancelled(attempt, TtsPlaybackFailureReason.OPERATION_CANCELLED)
                throw error
            } catch (error: Exception) {
                val reason = (error as? TtsPlaybackStartException)?.reasonCode
                    ?: TtsPlaybackFailureReason.UNEXPECTED_ERROR
                finishPlaybackFailed(attempt, reason, error)
            }
        }
    }

    private fun beginPlaybackAttempt(action: TtsPlaybackAction): PlaybackAttempt? {
        if (activePlaybackAttempt != null) return null
        val attempt = PlaybackAttempt(
            correlationId = UUID.randomUUID().toString(),
            action = action,
            isRetry = previousPlaybackAttemptFailed,
            startedAtMs = System.currentTimeMillis(),
        )
        activePlaybackAttempt = attempt
        playbackRequestPending.value = true
        _playbackOperations.tryEmit(
            TtsPlaybackOperation.Attempted(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
            ),
        )
        return attempt
    }

    private fun awaitPlaybackStart(attempt: PlaybackAttempt) {
        if (activePlaybackAttempt != attempt) return
        if (engine.isPlaying.value) {
            finishPlaybackSucceeded(attempt)
            return
        }
        if (playbackLifecycle.hasStartupTimeout) return
        armPlaybackStartTimeout(attempt)
    }

    private fun armPlaybackStartTimeout(attempt: PlaybackAttempt) {
        if (activePlaybackAttempt != attempt) return
        playbackLifecycle.armStartupTimeout()
    }

    private fun finishPlaybackSucceeded(attempt: PlaybackAttempt) {
        if (activePlaybackAttempt != attempt) return
        clearActivePlaybackAttempt()
        previousPlaybackAttemptFailed = false
        _playbackOperations.tryEmit(
            TtsPlaybackOperation.Succeeded(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
            ),
        )
    }

    private fun finishPlaybackFailed(
        attempt: PlaybackAttempt,
        reasonCode: TtsPlaybackFailureReason,
        error: Throwable?,
    ) {
        if (activePlaybackAttempt != attempt) return
        playbackLifecycle.cancelPendingRequest()
        engine.stop()
        clearActivePlaybackAttempt()
        previousPlaybackAttemptFailed = true
        _playbackOperations.tryEmit(
            TtsPlaybackOperation.Failed(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
                reasonCode = reasonCode,
                error = error,
            ),
        )
    }

    private fun finishPlaybackCancelled(
        attempt: PlaybackAttempt,
        reasonCode: TtsPlaybackFailureReason,
    ) {
        if (activePlaybackAttempt != attempt) return
        clearActivePlaybackAttempt()
        _playbackOperations.tryEmit(
            TtsPlaybackOperation.Cancelled(
                correlationId = attempt.correlationId,
                action = attempt.action,
                isRetry = attempt.isRetry,
                durationMs = elapsedSince(attempt.startedAtMs),
                reasonCode = reasonCode,
            ),
        )
    }

    private fun cancelActivePlaybackAttempt() {
        val attempt = activePlaybackAttempt ?: return
        playbackLifecycle.cancelPendingRequest()
        finishPlaybackCancelled(attempt, TtsPlaybackFailureReason.OPERATION_CANCELLED)
    }

    private fun clearActivePlaybackAttempt() {
        activePlaybackAttempt = null
        playbackLifecycle.cancelStartupTimeout()
        playbackRequestPending.value = false
    }

    private fun elapsedSince(startedAtMs: Long): Long =
        (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)

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

    private companion object {
        // Covers synthesis/player startup after notification permission is resolved.
        const val TTS_PLAYBACK_START_TIMEOUT_MS = 30_000L
        const val PREVIEW_START_TIMEOUT_MS = 30_000L
        const val PREVIEW_MAX_DURATION_MS = 60_000L
        const val CHAPTER_READY_TIMEOUT_MS = 5_000L
        const val DEFAULT_BOOK_TITLE = "Reading Aloud"
        const val TAG = "AndroidTtsController"
    }
}
