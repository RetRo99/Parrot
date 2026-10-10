package com.retro99.reader.ui.tts

import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.navigator.TtsPlaybackFailureReason
import com.retro99.reader.ui.playback.MediaPlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.io.File
import kotlin.coroutines.CoroutineContext

data class TtsPlaybackInfo(
    val serverId: String,
    val bookUuid: String,
    val bookType: BookType,
    val bookTitle: String,
    val chapterTitle: String?,
    val coverArtwork: ByteArray?,
)

class TtsPlaybackStartException(
    val reasonCode: TtsPlaybackFailureReason,
    cause: Throwable? = null,
) : IllegalStateException("TTS playback start failed (${reasonCode.analyticsValue})", cause)

@Single
class TtsReadAloudEngine(
    private val synthesizer: TtsSynthesizer,
    private val audioGenerator: TtsSentenceAudioSource,
    private val mediaPlaybackController: MediaPlaybackController,
    private val playerProvider: TtsEnginePlayerProvider,
    /** The engine's own context; a host test supplies a test dispatcher here. */
    mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : AutoCloseable {

    data class PlaybackFailure(
        val correlationId: String?,
        val reasonCode: TtsPlaybackFailureReason,
        val error: Throwable,
    )

    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private var player: TtsEnginePlayer? = null
    private var ownsPlayer = false

    private var sentences: List<TtsSentence> = emptyList()
    private var currentIndex: Int = -1
    private var voiceId: String? = null
    private var rate: Float = 1f
    private var pitch: Float = 1f
    private var completeChapterOnEnd: Boolean = true
    private var showPlaybackNotification: Boolean = true
    private var playbackInfo: TtsPlaybackInfo? = null
    private var generation: Int = 0
    private var chapterTimeline = TtsChapterTimeline.EMPTY
    private var pendingSentenceProgress: Double? = null
    private var playbackOperationCorrelationId: String? = null

    private val heard = TtsHeardSentenceTracker()

    // Set while startSentence synthesises its target and the old playlist
    // may still be playing; the old one must not advance past it.
    private var pendingStartToken: Int? = null

    private val readyFiles = mutableMapOf<Int, File>()
    private val queuedSentenceIndices = linkedSetOf<Int>()
    private val prefetchJobs = mutableMapOf<Int, Job>()
    private var activeSynthesisJob: Job? = null

    private val _currentSentence = MutableStateFlow<TtsSentence?>(null)
    val currentSentence: StateFlow<TtsSentence?> = _currentSentence.asStateFlow()

    private val _sentenceCount = MutableStateFlow(0)
    val sentenceCount: StateFlow<Int> = _sentenceCount.asStateFlow()

    private val _currentSentenceDurationMs = MutableStateFlow(0L)
    val currentSentenceDurationMs: StateFlow<Long> = _currentSentenceDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isSessionRunning = MutableStateFlow(false)

    /**
     * Whether narration is running, which is not the same question as [isPlaying]: a slow
     * voice leaves gaps where the clip that was playing has ended and the next one is
     * still being synthesised, and for that second or two no audio is audible although
     * nothing was paused, stopped or finished (TTS-F26).
     *
     * True from an accepted start until [pause], [stop], a failure or the end of the
     * chapter, gaps included. Every decision about a running session — a settings change,
     * the play/pause button, the preview, the word speaker — asks this, not [isPlaying].
     */
    val isSessionRunning: StateFlow<Boolean> = _isSessionRunning.asStateFlow()

    /** The user asked for silence; only an explicit play or start lifts it. */
    private var isPauseRequested = false

    /** A sentence is on its way to the player: synthesis, then the play call. */
    private var isStartingSentence = false

    /** Audio has already been audible in this session, so a wait is a gap, not a start. */
    private var hasPlayedInSession = false

    /**
     * The player ran out of audio. It does not leave that state for a play call, and an
     * item appended behind it does not undo it either, so the engine has to start the
     * sentence again rather than wait for a player that will never move.
     */
    private var playerReachedEndOfQueue = false

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playbackFailures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 4)
    val playbackFailures: SharedFlow<PlaybackFailure> = _playbackFailures.asSharedFlow()

    private val _chapterCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val chapterCompleted: SharedFlow<Unit> = _chapterCompleted.asSharedFlow()

    /** Sentences whose audio played to the end; not skipped or stopped ones. */
    private val _finishedSentences = MutableSharedFlow<TtsSentence>(extraBufferCapacity = 16)
    val finishedSentences: SharedFlow<TtsSentence> = _finishedSentences.asSharedFlow()

    private val playerListener = object : TtsEnginePlayerListener {
        override fun onItemTransition(mediaId: String?, isAutoAdvance: Boolean) {
            if (!acceptsPlayerCallbacks || (isAutoAdvance && !isSessionRunning.value)) return
            if (mediaId != null && !mediaId.startsWith(TTS_MEDIA_ID_PREFIX)) {
                detachForExternalPlayback()
                return
            }

            val index = mediaId?.let(::sentenceIndexForMediaId) ?: return
            heard.onTransition(index, isAutoAdvance)?.let(::emitFinished)
            // The old playlist moving on mustn't steal the pending target.
            if (isAutoAdvance && pendingStartToken != null) return
            onSentenceStarted(index)
        }

        override fun onSeeked(positionMs: Long) {
            heard.onSeek(positionMs)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!acceptsPlayerCallbacks) return
            _isPlaying.value = isPlaying
            // Audio is audible: the start this session was waiting for has landed.
            if (isPlaying) {
                isStartingSentence = false
                hasPlayedInSession = true
            }
            updateSessionRunning()
        }

        override fun onEnded() {
            playerReachedEndOfQueue = true
            onSentenceCompleted()
        }

        override fun onReady() {
            if (!acceptsPlayerCallbacks) return
            val mediaItemIndex = player?.currentMediaId?.let(::sentenceIndexForMediaId)
            if (mediaItemIndex != null && mediaItemIndex != currentIndex) {
                onSentenceStarted(mediaItemIndex)
            }
            val durationMs = player?.durationMs?.coerceAtLeast(0L) ?: 0L
            if (currentIndex >= 0 && durationMs > 0L) {
                updateSentenceDuration(currentIndex, durationMs)
            }
            val startProgress = pendingSentenceProgress
            pendingSentenceProgress = null
            if (startProgress != null && startProgress > 0.0 && durationMs > 0L) {
                player?.seekTo((durationMs * startProgress).toLong())
            }
            _isLoading.value = false
        }

        override fun onError(error: Throwable) {
            if (!acceptsPlayerCallbacks) return
            _playbackFailures.tryEmit(
                PlaybackFailure(
                    correlationId = playbackOperationCorrelationId,
                    reasonCode = TtsPlaybackFailureReason.PLAYER_ERROR,
                    error = error,
                ),
            )
            stopInternal()
        }
    }

    init {
        scope.launch {
            mediaPlaybackController.nextTtsSentenceRequest.collect {
                skipToNextSentence()
            }
        }
        scope.launch {
            mediaPlaybackController.previousTtsSentenceRequest.collect {
                skipToPreviousSentence()
            }
        }
        scope.launch {
            mediaPlaybackController.ttsChapterPositionRequest.collect { positionMs ->
                seekToChapterPosition(positionMs)
            }
        }
    }

    fun availableVoices(): List<TtsVoice> = synthesizer.availableVoices()

    fun defaultVoice(): TtsVoice? = synthesizer.defaultVoice()

    val currentSentenceIndex: Int
        get() = currentIndex

    fun isPlayingBook(bookUuid: String): Boolean =
        playbackInfo?.bookUuid == bookUuid && currentIndex >= 0

    fun stopIfPlayingAnotherBook(bookUuid: String) {
        val activeBookUuid = playbackInfo?.bookUuid ?: return
        if (activeBookUuid != bookUuid && currentIndex >= 0) {
            stopInternal()
        }
    }

    fun setPlaybackInfo(info: TtsPlaybackInfo) {
        playbackInfo = info
    }

    fun setPlaybackOperationCorrelationId(correlationId: String) {
        playbackOperationCorrelationId = correlationId
    }

    fun setSentences(list: List<TtsSentence>) {
        generation++
        pendingStartToken = null
        heard.reset()
        sentences = list
        _sentenceCount.value = list.size
        currentIndex = -1
        chapterTimeline = TtsChapterTimeline.EMPTY
        pendingSentenceProgress = null
        readyFiles.clear()
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
        player?.run {
            stop()
            clearItems()
        }
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
        clearSessionState()
    }

    suspend fun playFrom(
        index: Int,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        completeChapterOnEnd: Boolean = true,
        showPlaybackNotification: Boolean = this.showPlaybackNotification,
    ) {
        if (sentences.isEmpty()) return
        // An explicit start, so whatever the user paused earlier no longer holds.
        isPauseRequested = false
        val effectiveRate = TtsSpeechRate.coerce(rate)
        val playbackTargetChanged =
            this.showPlaybackNotification != showPlaybackNotification
        if (playbackTargetChanged) {
            releaseCurrentPlayer(stopSharedPlayer = true)
        }

        val synthesisConfigChanged =
            this.voiceId != voiceId ||
                    this.rate != effectiveRate ||
                    (voiceId.neuralVoicePackage() == null && this.pitch != pitch)
        if (synthesisConfigChanged) {
            generation++
            readyFiles.clear()
            cancelPrefetch()
            cancelActiveSynthesis()
            player?.stop()
        }
        this.voiceId = voiceId
        this.rate = effectiveRate
        this.pitch = pitch
        this.completeChapterOnEnd = completeChapterOnEnd
        this.showPlaybackNotification = showPlaybackNotification
        if (synthesisConfigChanged || chapterTimeline.isEmpty) {
            chapterTimeline = TtsChapterTimeline.estimate(sentences, effectiveRate)
        }
        startSentence(index.coerceIn(0, sentences.lastIndex))
    }

    /**
     * Silence until an explicit play. A pause inside a synthesis gap also stops the
     * sentence being made from starting when it arrives (TTS-F26).
     */
    fun pause() {
        isPauseRequested = true
        updateSessionRunning()
        player?.pause()
    }

    fun resume() {
        if (currentIndex < 0) return
        isPauseRequested = false
        updateSessionRunning()
        // A sentence already on its way plays itself now the pause is lifted.
        if (isStartingSentence) return
        val playbackPlayer = player
        if (playbackPlayer == null || playerReachedEndOfQueue) {
            // The player ran out of audio and will not move for a play call; the
            // sentence is started again, from the clip that was already made.
            launchEngineStart { startSentence(currentIndex) }
            return
        }
        // The explicit resume owns the next playing callback, before audio is audible.
        isStartingSentence = true
        updateSessionRunning()
        playbackPlayer.play()
    }

    fun stop() {
        stopInternal()
    }

    fun skipToNextSentence() {
        val next = currentIndex + 1
        if (next > sentences.lastIndex) {
            if (currentIndex == sentences.lastIndex && completeChapterOnEnd) {
                stopInternal()
                _chapterCompleted.tryEmit(Unit)
            }
            return
        }
        launchEngineStart {
            playFrom(next, voiceId, rate, pitch, completeChapterOnEnd)
        }
    }

    fun skipToPreviousSentence() {
        val previous = currentIndex - 1
        if (previous < 0) return
        launchEngineStart {
            playFrom(previous, voiceId, rate, pitch, completeChapterOnEnd)
        }
    }

    /**
     * Starts a sentence the user did not ask for: auto-advance, a skip, a seek. There is
     * no playback attempt waiting on it, so a failure must be reported rather than
     * thrown: the engine's scope has no exception handler, and an uncaught throw here
     * killed the app (TTS-F01) and reported nothing (TTS-F02).
     *
     * A user request keeps going through [playFrom] directly, so
     * `AndroidTtsController.requestPlayback` still sees [TtsPlaybackStartException] and
     * still produces exactly one outcome for it.
     */
    private fun launchEngineStart(start: suspend () -> Unit) {
        // The session is running from here, through the gap this start has to cross.
        isStartingSentence = true
        updateSessionRunning()
        scope.launch {
            try {
                start()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportStartFailure(error)
            }
        }
    }

    private fun reportStartFailure(error: Exception) {
        val reasonCode = (error as? TtsPlaybackStartException)?.reasonCode
            ?: TtsPlaybackFailureReason.UNEXPECTED_ERROR
        stopInternal()
        _playbackFailures.tryEmit(
            PlaybackFailure(
                correlationId = playbackOperationCorrelationId,
                reasonCode = reasonCode,
                error = error,
            ),
        )
    }

    private suspend fun startSentence(
        index: Int,
        sentenceProgress: Double = 0.0,
    ) {
        val sentence = sentences.getOrNull(index)
        if (sentence == null) {
            stopInternal()
            return
        }

        val token = ++generation
        pendingStartToken = token
        isStartingSentence = true
        updateSessionRunning()
        cancelActiveSynthesis()
        cancelPrefetch(exceptIndex = index)
        currentIndex = index
        // Only a start with nothing audible yet is "preparing". A gap inside a running
        // session is not: the UI disables the play/pause button while this is true, which
        // left it dead for a second or two every sentence of a slow voice (TTS-F26).
        _isLoading.value = !hasPlayedInSession
        _currentSentenceDurationMs.value = 0L
        _currentSentence.value = sentence

        val synthesisJob = currentCoroutineContext()[Job]
        activeSynthesisJob = synthesisJob
        val file = try {
            getOrSynthesize(index)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (token != generation) return
            stopInternal()
            throw TtsPlaybackStartException(
                reasonCode = TtsPlaybackFailureReason.SYNTHESIS_FAILED,
                cause = error,
            )
        } finally {
            if (activeSynthesisJob === synthesisJob) {
                activeSynthesisJob = null
            }
        }
        if (token != generation) return

        if (file == null) {
            stopInternal()
            throw TtsPlaybackStartException(TtsPlaybackFailureReason.SYNTHESIS_FAILED)
        }

        val playbackPlayer = ensurePlayer()
        if (playbackPlayer == null) {
            stopInternal()
            throw TtsPlaybackStartException(TtsPlaybackFailureReason.PLAYER_UNAVAILABLE)
        }
        if (token != generation) return

        val playbackPitch = if (voiceId.neuralVoicePackage() != null) {
            pitch.coerceIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH)
        } else {
            DEFAULT_PLAYBACK_PITCH
        }
        playbackPlayer.setPlaybackPitch(playbackPitch)
        pendingSentenceProgress = sentenceProgress.coerceIn(0.0, 1.0)
        val playlist = buildPlaylist(index)
        queuedSentenceIndices.clear()
        queuedSentenceIndices.addAll(index until index + playlist.size)
        if (pendingStartToken == token) pendingStartToken = null
        heard.onPlaylistStarted(index, sentenceProgress)
        playbackPlayer.setItems(playlist)
        playerReachedEndOfQueue = false
        playbackPlayer.prepare()
        if (isPauseRequested) {
            // Paused while this sentence was being made: it waits, prepared and silent,
            // for the play press (TTS-F26).
            isStartingSentence = false
            updateSessionRunning()
            _isLoading.value = false
        } else {
            playbackPlayer.play()
        }
        prefetch(index + 1)
    }

    private suspend fun ensurePlayer(): TtsEnginePlayer? {
        return if (showPlaybackNotification) {
            ensureNotificationPlayer()
        } else {
            ensureLocalPlayer()
        }
    }

    private suspend fun ensureNotificationPlayer(): TtsEnginePlayer? {
        val servicePlayer = playerProvider.notificationPlayer() ?: return null

        attachPlayer(servicePlayer, ownsPlayer = false)
        val info = playbackInfo
        mediaPlaybackController.prepareForTtsPlayback(
            bookTitle = info?.bookTitle ?: DEFAULT_BOOK_TITLE,
            chapterTitle = info?.chapterTitle,
            coverArtwork = info?.coverArtwork,
        )
        mediaPlaybackController.updateTtsChapterTimeline(
            chapterTimeline = chapterTimeline,
            sentenceIndex = currentIndex,
        )
        if (info != null) {
            mediaPlaybackController.setCurrentPlayingBook(
                serverId = info.serverId,
                bookUuid = info.bookUuid,
                bookType = info.bookType,
                bookTitle = info.bookTitle,
            )
        }
        updatePlaybackTimeline()
        return servicePlayer
    }

    private fun ensureLocalPlayer(): TtsEnginePlayer {
        val currentPlayer = player
        if (currentPlayer != null && ownsPlayer) return currentPlayer

        releaseCurrentPlayer(stopSharedPlayer = true)
        val localPlayer = playerProvider.createLocalPlayer()
        attachPlayer(localPlayer, ownsPlayer = true)
        return localPlayer
    }

    private fun attachPlayer(nextPlayer: TtsEnginePlayer, ownsPlayer: Boolean) {
        if (player === nextPlayer) return
        releaseCurrentPlayer(stopSharedPlayer = false)
        player = nextPlayer
        this.ownsPlayer = ownsPlayer
        nextPlayer.addListener(playerListener)
    }

    private fun releaseCurrentPlayer(stopSharedPlayer: Boolean) {
        val currentPlayer = player ?: return
        currentPlayer.removeListener(playerListener)
        if (ownsPlayer) {
            currentPlayer.stop()
            currentPlayer.clearItems()
            currentPlayer.release()
        } else if (stopSharedPlayer) {
            mediaPlaybackController.stop()
        }
        player = null
        ownsPlayer = false
    }

    private fun createPlayerItem(file: File, index: Int): TtsEnginePlayerItem {
        val info = playbackInfo
        return TtsEnginePlayerItem(
            mediaId = "tts:${info?.bookUuid.orEmpty()}:$index",
            file = file,
            title = info?.chapterTitle ?: info?.bookTitle ?: DEFAULT_BOOK_TITLE,
            artist = info?.bookTitle ?: DEFAULT_APP_NAME,
            displayTitle = info?.chapterTitle ?: info?.bookTitle ?: DEFAULT_BOOK_TITLE,
            artworkData = info?.coverArtwork,
        )
    }

    private suspend fun getOrSynthesize(index: Int): File? {
        readyFiles[index]
            ?.takeIf { cached -> cached.exists() && cached.length() > 0L }
            ?.let { cached -> return cached }
        readyFiles.remove(index)
        prefetchJobs[index]?.join()
        return synthesizeIfMissing(index, failOnError = true)
    }

    private suspend fun synthesizeIfMissing(index: Int, failOnError: Boolean = false): File? {
        readyFiles[index]
            ?.takeIf { cached -> cached.exists() && cached.length() > 0L }
            ?.let { cached -> return cached }
        readyFiles.remove(index)

        val sentence = sentences.getOrNull(index) ?: return null
        val synthesisPitch = if (voiceId.neuralVoicePackage() != null) {
            DEFAULT_PLAYBACK_PITCH
        } else {
            pitch
        }
        val result = audioGenerator.synthesize(
            text = sentence.text,
            voiceId = voiceId,
            rate = rate,
            pitch = synthesisPitch,
        )
        val file = result.file
        if (result.status == TtsSynthesisStatus.SUCCESS && file != null && file.exists()) {
            readyFiles[index] = file
            result.durationMs?.let { durationMs ->
                updateSentenceDuration(index, durationMs)
            }
            return file
        }

        if (result.status == TtsSynthesisStatus.CANCELLED) {
            throw CancellationException("TTS synthesis cancelled")
        }
        if (failOnError) {
            throw TtsPlaybackStartException(TtsPlaybackFailureReason.SYNTHESIS_FAILED)
        }
        return null
    }

    private fun prefetch(from: Int) {
        if (sentences.isEmpty()) return
        if (prefetchHolds > 0) return
        val end = (from + PREFETCH_AHEAD).coerceAtMost(sentences.size)
        for (index in from until end) {
            if (index < 0) continue
            if (
                readyFiles[index]?.let { file -> file.exists() && file.length() > 0L } == true ||
                prefetchJobs.containsKey(index)
            ) {
                continue
            }
            readyFiles.remove(index)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val runningJob = currentCoroutineContext()[Job]
                val prefetchGeneration = generation
                try {
                    synthesizeIfMissing(index)
                    if (prefetchGeneration == generation) {
                        appendReadyFilesToPlaylist()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Prefetch is opportunistic; the foreground playback request retries
                    // synthesis and owns the user-visible outcome/diagnostic boundary.
                } finally {
                    if (prefetchJobs[index] === runningJob) {
                        prefetchJobs.remove(index)
                    }
                }
            }
            prefetchJobs[index] = job
            job.start()
        }
    }

    private var prefetchHolds = 0

    /**
     * Holds sentence prefetch and drops queued prefetch work past the next sentence, so a
     * one-word synthesis reaches the single synthesis permit ahead of read-aloud's backlog.
     * The sentence that plays next is never cancelled. Pair with [resumePrefetchAfterWord].
     */
    fun holdPrefetchForWord() {
        prefetchHolds++
        cancelPrefetch(exceptIndex = currentIndex + 1)
    }

    /** Lifts one hold and refills the backlog when the last hold lifts. */
    fun resumePrefetchAfterWord() {
        if (prefetchHolds == 0) return
        prefetchHolds--
        if (prefetchHolds > 0) return
        if (sentences.isNotEmpty() && currentIndex >= 0) {
            prefetch(currentIndex + 1)
        }
    }

    private fun cancelPrefetch(exceptIndex: Int? = null) {
        val iterator = prefetchJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (index != exceptIndex) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    private fun cancelActiveSynthesis() {
        activeSynthesisJob?.cancel()
        activeSynthesisJob = null
    }

    /**
     * The player ran out of audio. The engine moves on itself rather than leaving it to
     * the player: a clip appended in the window just before the end-of-queue callback
     * (5 ms, on the phone of 2026-10-09) is behind a player that has already finished and
     * will never be played, which left narration silent mid-chapter with no event at all
     * (TTS-F26). Starting the next sentence again costs nothing — its clip is already in
     * [readyFiles], and [buildPlaylist] re-queues it and everything ready after it.
     */
    private fun onSentenceCompleted() {
        // Player callbacks already posted before a stop or pause may still arrive.
        // The session, not just its index, owns permission to advance (TTS-F25).
        if (!isSessionRunning.value) return
        heard.onEnded()?.let(::emitFinished)
        // A seek or skip target is being prepared; it starts on its own.
        if (pendingStartToken != null) return

        val next = currentIndex + 1
        if (next <= sentences.lastIndex) {
            launchEngineStart {
                startSentence(next)
            }
        } else {
            val shouldCompleteChapter = completeChapterOnEnd
            stopInternal()
            if (shouldCompleteChapter) {
                _chapterCompleted.tryEmit(Unit)
            }
        }
    }

    private fun seekToChapterPosition(positionMs: Long) {
        if (sentences.isEmpty() || currentIndex < 0) return

        val target = chapterTimeline.locate(positionMs)
        val currentPlayer = player
        val currentDurationMs = currentPlayer?.durationMs ?: 0L
        if (
            target.sentenceIndex == currentIndex &&
            currentPlayer != null &&
            currentDurationMs > 0L
        ) {
            currentPlayer.seekTo((currentDurationMs * target.sentenceProgress).toLong())
            return
        }

        launchEngineStart {
            startSentence(
                index = target.sentenceIndex,
                sentenceProgress = target.sentenceProgress,
            )
        }
    }

    private fun stopInternal() {
        generation++
        pendingStartToken = null
        heard.reset()
        currentIndex = -1
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
        pendingSentenceProgress = null
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
        clearSessionState()
        val currentPlayer = player
        if (currentPlayer != null && ownsPlayer) {
            currentPlayer.stop()
            currentPlayer.clearItems()
        } else if (currentPlayer != null) {
            releaseCurrentPlayer(stopSharedPlayer = true)
        }
    }

    private fun detachForExternalPlayback() {
        generation++
        pendingStartToken = null
        heard.reset()
        currentIndex = -1
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
        pendingSentenceProgress = null
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
        clearSessionState()
        releaseCurrentPlayer(stopSharedPlayer = false)
    }

    override fun close() {
        stopInternal()
        scope.cancel()
        releaseCurrentPlayer(stopSharedPlayer = true)
    }

    private fun onSentenceStarted(index: Int) {
        currentIndex = index
        _currentSentence.value = sentences.getOrNull(index)
        _isLoading.value = false
        val durationMs = player?.durationMs?.coerceAtLeast(0L) ?: 0L
        if (durationMs > 0L) {
            updateSentenceDuration(index, durationMs)
        }
        updatePlaybackTimeline()
        prefetch(index + 1)
    }

    /** No session: nothing paused, nothing starting, no player state to carry over. */
    private fun clearSessionState() {
        isPauseRequested = false
        isStartingSentence = false
        playerReachedEndOfQueue = false
        hasPlayedInSession = false
        updateSessionRunning()
    }

    private fun updateSessionRunning() {
        _isSessionRunning.value =
            !isPauseRequested && (_isPlaying.value || isStartingSentence || hasPlayedInSession)
    }

    // A paused playlist may still become ready; a stopped one has no owner at all.
    private val acceptsPlayerCallbacks: Boolean
        get() = isSessionRunning.value || isPauseRequested

    private fun emitFinished(index: Int) {
        sentences.getOrNull(index)?.let(_finishedSentences::tryEmit)
    }

    private fun updateSentenceDuration(index: Int, durationMs: Long) {
        if (durationMs <= 0L) return
        chapterTimeline = chapterTimeline.withSentenceDuration(index, durationMs)
        if (index == currentIndex) {
            _currentSentenceDurationMs.value = durationMs
        }
        updatePlaybackTimeline()
    }

    private fun updatePlaybackTimeline() {
        if (showPlaybackNotification && currentIndex >= 0) {
            mediaPlaybackController.updateTtsChapterTimeline(
                chapterTimeline = chapterTimeline,
                sentenceIndex = currentIndex,
            )
        }
    }

    private fun buildPlaylist(startIndex: Int): List<TtsEnginePlayerItem> {
        val playlist = mutableListOf<TtsEnginePlayerItem>()
        var index = startIndex
        while (index <= sentences.lastIndex) {
            val file = readyFiles[index]
                ?.takeIf { candidate -> candidate.exists() && candidate.length() > 0L }
                ?: break
            playlist += createPlayerItem(file, index)
            index++
        }
        return playlist
    }

    private fun appendReadyFilesToPlaylist() {
        val playbackPlayer = player ?: return
        if (currentIndex < 0 || playbackPlayer.itemCount == 0) return

        var index = (queuedSentenceIndices.maxOrNull() ?: currentIndex) + 1
        while (index <= sentences.lastIndex) {
            val file = readyFiles[index]
                ?.takeIf { candidate -> candidate.exists() && candidate.length() > 0L }
                ?: break
            playbackPlayer.addItem(createPlayerItem(file, index))
            queuedSentenceIndices += index
            index++
        }
    }

    private fun sentenceIndexForMediaId(mediaId: String): Int? {
        if (!mediaId.startsWith(TTS_MEDIA_ID_PREFIX)) return null
        return mediaId.substringAfterLast(':').toIntOrNull()
    }

    private companion object {
        const val DEFAULT_BOOK_TITLE = "Reading Aloud"
        const val DEFAULT_APP_NAME = "Parrot"
        const val TTS_MEDIA_ID_PREFIX = "tts:"
        const val PREFETCH_AHEAD = 4
        const val DEFAULT_PLAYBACK_PITCH = 1f
        const val MIN_PLAYBACK_PITCH = 0.25f
        const val MAX_PLAYBACK_PITCH = 4f
    }
}
