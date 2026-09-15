package com.retro99.reader.ui.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookType
import com.retro99.reader.ui.playback.ForegroundServiceController
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.setArtworkDataIfSmall
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
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File

data class TtsPlaybackInfo(
    val serverId: String,
    val bookUuid: String,
    val bookType: BookType,
    val bookTitle: String,
    val chapterTitle: String?,
    val coverArtwork: ByteArray?,
)

@Single
class TtsReadAloudEngine(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
    private val synthesizer: TtsSynthesizer,
    private val audioGenerator: TtsAudioGenerator,
    private val mediaPlaybackController: MediaPlaybackController,
    private val foregroundServiceController: ForegroundServiceController,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
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

    private val readyFiles = mutableMapOf<Int, File>()
    private val queuedSentenceIndices = linkedSetOf<Int>()
    private val prefetchJobs = mutableMapOf<Int, Job>()
    private var activeSynthesisJob: Job? = null

    private val _currentSentence = MutableStateFlow<TtsSentence?>(null)
    val currentSentence: StateFlow<TtsSentence?> = _currentSentence.asStateFlow()

    private val _currentSentenceDurationMs = MutableStateFlow(0L)
    val currentSentenceDurationMs: StateFlow<Long> = _currentSentenceDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _chapterCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val chapterCompleted: SharedFlow<Unit> = _chapterCompleted.asSharedFlow()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null && !mediaItem.mediaId.startsWith(TTS_MEDIA_ID_PREFIX)) {
                detachForExternalPlayback()
                return
            }

            val index = mediaItem?.let(::sentenceIndexForMediaItem) ?: return
            onSentenceStarted(index)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> onSentenceCompleted()
                Player.STATE_READY -> {
                    val mediaItemIndex = player?.currentMediaItem?.let(::sentenceIndexForMediaItem)
                    if (mediaItemIndex != null && mediaItemIndex != currentIndex) {
                        onSentenceStarted(mediaItemIndex)
                    }
                    val durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
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
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            analytics.logException(error, "TTS audio playback failed")
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

    fun setSentences(list: List<TtsSentence>) {
        generation++
        sentences = list
        currentIndex = -1
        chapterTimeline = TtsChapterTimeline.EMPTY
        pendingSentenceProgress = null
        readyFiles.clear()
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
        player?.run {
            stop()
            clearMediaItems()
        }
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
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

    fun pause() {
        player?.pause()
    }

    fun resume() {
        if (currentIndex < 0) return
        player?.play()
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
        scope.launch {
            playFrom(next, voiceId, rate, pitch, completeChapterOnEnd)
        }
    }

    fun skipToPreviousSentence() {
        val previous = currentIndex - 1
        if (previous < 0) return
        scope.launch {
            playFrom(previous, voiceId, rate, pitch, completeChapterOnEnd)
        }
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
        cancelActiveSynthesis()
        cancelPrefetch(exceptIndex = index)
        currentIndex = index
        _isLoading.value = true
        _currentSentenceDurationMs.value = 0L
        _currentSentence.value = sentence

        val synthesisJob = currentCoroutineContext()[Job]
        activeSynthesisJob = synthesisJob
        val file = try {
            getOrSynthesize(index)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (token == generation) {
                analytics.logException(error, "TTS synthesis failed for sentence $index")
                stopInternal()
            }
            return
        } finally {
            if (activeSynthesisJob === synthesisJob) {
                activeSynthesisJob = null
            }
        }
        if (token != generation) return

        if (file == null) {
            val next = index + 1
            if (next <= sentences.lastIndex) {
                startSentence(next)
            } else {
                stopInternal()
            }
            return
        }

        val playbackPlayer = ensurePlayer()
        if (playbackPlayer == null) {
            analytics.logException(
                IllegalStateException("TTS media service did not start"),
                "TTS playback could not acquire a player",
            )
            stopInternal()
            return
        }
        if (token != generation) return

        val playbackPitch = if (voiceId.neuralVoicePackage() != null) {
            pitch.coerceIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH)
        } else {
            DEFAULT_PLAYBACK_PITCH
        }
        playbackPlayer.playbackParameters = PlaybackParameters(
            DEFAULT_PLAYBACK_SPEED,
            playbackPitch,
        )
        pendingSentenceProgress = sentenceProgress.coerceIn(0.0, 1.0)
        val playlist = buildPlaylist(index)
        queuedSentenceIndices.clear()
        queuedSentenceIndices.addAll(index until index + playlist.size)
        playbackPlayer.setMediaItems(playlist)
        playbackPlayer.prepare()
        playbackPlayer.play()
        prefetch(index + 1)
    }

    private suspend fun ensurePlayer(): ExoPlayer? {
        return if (showPlaybackNotification) {
            ensureNotificationPlayer()
        } else {
            ensureLocalPlayer()
        }
    }

    private suspend fun ensureNotificationPlayer(): ExoPlayer? {
        var servicePlayer = mediaPlaybackController.currentPlayer
        if (servicePlayer == null) {
            val serviceReady = mediaPlaybackController.prepareServiceReady()
            if (!foregroundServiceController.startService()) return null
            servicePlayer = mediaPlaybackController.awaitServiceReady(serviceReady)
            if (servicePlayer == null) {
                foregroundServiceController.stopService()
                return null
            }
        }

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

    private fun ensureLocalPlayer(): ExoPlayer {
        val currentPlayer = player
        if (currentPlayer != null && ownsPlayer) return currentPlayer

        releaseCurrentPlayer(stopSharedPlayer = true)
        val localPlayer = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(createAudioAttributes(), true)
        }
        attachPlayer(localPlayer, ownsPlayer = true)
        return localPlayer
    }

    private fun attachPlayer(nextPlayer: ExoPlayer, ownsPlayer: Boolean) {
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
            currentPlayer.clearMediaItems()
            currentPlayer.release()
        } else if (stopSharedPlayer) {
            mediaPlaybackController.stop()
        }
        player = null
        ownsPlayer = false
    }

    private fun createMediaItem(file: File, index: Int): MediaItem {
        val info = playbackInfo
        val metadata = MediaMetadata.Builder()
            .setTitle(info?.chapterTitle ?: info?.bookTitle ?: DEFAULT_BOOK_TITLE)
            .setArtist(info?.bookTitle ?: DEFAULT_APP_NAME)
            .setDisplayTitle(info?.chapterTitle ?: info?.bookTitle ?: DEFAULT_BOOK_TITLE)
            .apply {
                setArtworkDataIfSmall(info?.coverArtwork, TAG)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId("tts:${info?.bookUuid.orEmpty()}:$index")
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun createAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

    private suspend fun getOrSynthesize(index: Int): File? {
        readyFiles[index]
            ?.takeIf { cached -> cached.exists() && cached.length() > 0L }
            ?.let { cached -> return cached }
        readyFiles.remove(index)
        prefetchJobs[index]?.join()
        return synthesizeIfMissing(index)
    }

    private suspend fun synthesizeIfMissing(index: Int): File? {
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

        analytics.logException(
            IllegalStateException("TTS synthesis failed: ${result.status} ${result.error}"),
            "TTS synthesis failed for sentence $index",
        )
        return null
    }

    private fun prefetch(from: Int) {
        if (sentences.isEmpty()) return
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

    private fun onSentenceCompleted() {
        if (player?.hasNextMediaItem() == true) return

        val next = currentIndex + 1
        if (next <= sentences.lastIndex) {
            scope.launch {
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
        val currentDurationMs = currentPlayer?.duration ?: 0L
        if (
            target.sentenceIndex == currentIndex &&
            currentPlayer != null &&
            currentDurationMs > 0L
        ) {
            currentPlayer.seekTo((currentDurationMs * target.sentenceProgress).toLong())
            return
        }

        scope.launch {
            startSentence(
                index = target.sentenceIndex,
                sentenceProgress = target.sentenceProgress,
            )
        }
    }

    private fun stopInternal() {
        generation++
        currentIndex = -1
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
        pendingSentenceProgress = null
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
        val currentPlayer = player
        if (currentPlayer != null && ownsPlayer) {
            currentPlayer.stop()
            currentPlayer.clearMediaItems()
        } else if (currentPlayer != null) {
            releaseCurrentPlayer(stopSharedPlayer = true)
        }
    }

    private fun detachForExternalPlayback() {
        generation++
        currentIndex = -1
        _isPlaying.value = false
        _isLoading.value = false
        _currentSentence.value = null
        _currentSentenceDurationMs.value = 0L
        pendingSentenceProgress = null
        queuedSentenceIndices.clear()
        cancelPrefetch()
        cancelActiveSynthesis()
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
        val durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
        if (durationMs > 0L) {
            updateSentenceDuration(index, durationMs)
        }
        updatePlaybackTimeline()
        prefetch(index + 1)
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

    private fun buildPlaylist(startIndex: Int): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()
        var index = startIndex
        while (index <= sentences.lastIndex) {
            val file = readyFiles[index]
                ?.takeIf { candidate -> candidate.exists() && candidate.length() > 0L }
                ?: break
            playlist += createMediaItem(file, index)
            index++
        }
        return playlist
    }

    private fun appendReadyFilesToPlaylist() {
        val playbackPlayer = player ?: return
        if (currentIndex < 0 || playbackPlayer.mediaItemCount == 0) return

        var index = (queuedSentenceIndices.maxOrNull() ?: currentIndex) + 1
        while (index <= sentences.lastIndex) {
            val file = readyFiles[index]
                ?.takeIf { candidate -> candidate.exists() && candidate.length() > 0L }
                ?: break
            playbackPlayer.addMediaItem(createMediaItem(file, index))
            queuedSentenceIndices += index
            index++
        }
    }

    private fun sentenceIndexForMediaItem(mediaItem: MediaItem): Int? {
        if (!mediaItem.mediaId.startsWith(TTS_MEDIA_ID_PREFIX)) return null
        return mediaItem.mediaId.substringAfterLast(':').toIntOrNull()
    }

    private companion object {
        const val TAG = "TtsReadAloudEngine"
        const val DEFAULT_BOOK_TITLE = "Reading Aloud"
        const val DEFAULT_APP_NAME = "Parrot"
        const val TTS_MEDIA_ID_PREFIX = "tts:"
        const val PREFETCH_AHEAD = 4
        const val DEFAULT_PLAYBACK_SPEED = 1f
        const val DEFAULT_PLAYBACK_PITCH = 1f
        const val MIN_PLAYBACK_PITCH = 0.25f
        const val MAX_PLAYBACK_PITCH = 4f
    }
}
