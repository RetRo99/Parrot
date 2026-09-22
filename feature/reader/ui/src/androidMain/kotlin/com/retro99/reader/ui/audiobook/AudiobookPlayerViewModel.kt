package com.retro99.reader.ui.audiobook

import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import co.touchlab.kermit.Logger
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onSuccess
import com.retro99.base.nowMillis
import com.retro99.base.ui.BaseViewModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetBookByUuidUseCase
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReadingProgressResult
import com.retro99.reader.domain.usecase.GetReadingProgressWithConflictUseCase
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.playback.ForegroundServiceController
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.NotificationPermissionHandler
import com.retro99.sync.domain.RoutineSyncScheduler
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncScope
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import java.io.File

@KoinViewModel
class AudiobookPlayerViewModel(
    @InjectedParam private val serverId: String,
    @InjectedParam private val bookUuid: String,
    @InjectedParam private val onClose: () -> Unit,
    @Provided private val mediaPlaybackController: MediaPlaybackController,
    @Provided private val foregroundServiceController: ForegroundServiceController,
    @Provided private val notificationPermissionHandler: NotificationPermissionHandler,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val syncNowUseCase: SyncNowUseCase,
    @Provided private val getReadingProgressWithConflictUseCase: GetReadingProgressWithConflictUseCase,
    @Provided private val getBookByUuidUseCase: GetBookByUuidUseCase,
) : BaseViewModel<AudiobookPlayerViewState, AudiobookPlayerIntent>(
    AudiobookPlayerViewState(bookUuid = bookUuid)
) {

    private var player: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var positionUpdateJob: Job? = null
    private var hasRestoredPosition = false
    private var pendingAudioFiles: List<File> = emptyList()
    private val routineSyncScheduler = RoutineSyncScheduler(
        scope = viewModelScope,
        nowMillis = ::nowMillis,
        requestSync = {
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.ROUTINE_PROGRESS,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.ROUTINE,
                ),
            )
        },
    )

    init {
        loadBookInfoAndAudioFiles()
    }

    private fun loadBookInfoAndAudioFiles() {
        viewModelScope.launch {
            val bookResult = getBookByUuidUseCase(serverId, bookUuid).first()
            val book = bookResult.getOrElse { null }
            updateState {
                it.copy(
                    bookTitle = book?.title ?: "",
                    bookCoverUrl = book?.coverUrl,
                )
            }

            loadAudioFiles()

            if (mediaPlaybackController.isBookLoaded(bookUuid)) {
                reconnectToExistingPlayback()
            }
        }
    }

    private fun reconnectToExistingPlayback() {
        val servicePlayer = mediaPlaybackController.currentPlayer ?: return
        player = servicePlayer
        attachPlayerListener()

        updateState {
            it.copy(
                isLoading = false,
                isPlaying = servicePlayer.isPlaying,
                totalDurationMs = servicePlayer.duration.coerceAtLeast(0L),
                currentPositionMs = servicePlayer.currentPosition.coerceAtLeast(0L),
                currentTrackIndex = servicePlayer.currentMediaItemIndex,
                trackCount = servicePlayer.mediaItemCount,
                playbackState = servicePlayer.playbackState,
                playbackSpeed = servicePlayer.playbackParameters.speed,
            )
        }

        hasRestoredPosition = true

        mediaPlaybackController.updateNowPlayingBookInfo(
            bookUuid = bookUuid,
            bookTitle = viewState.value.bookTitle,
            coverUrl = viewState.value.bookCoverUrl,
        )

        startPositionUpdates()
    }

    private suspend fun loadAudioFiles() {
        val result = readerSettingsRepository.prepareEbook(bookUuid, "", BookType.AUDIOBOOK)
        val cachedPath = result.getOrElse { null }
        if (cachedPath == null) {
            updateState { it.copy(error = "Audio files not found", isLoading = false) }
            return
        }

        val dir = File(cachedPath)
        if (!dir.exists() || !dir.isDirectory) {
            updateState { it.copy(error = "Audio directory not found", isLoading = false) }
            return
        }

        val audioFiles = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (audioFiles.isEmpty()) {
            updateState { it.copy(error = "No audio files found", isLoading = false) }
            return
        }

        pendingAudioFiles = audioFiles

        val trackTitles = audioFiles.map { file ->
            file.nameWithoutExtension.replace(Regex("^\\d+\\s*"), "")
                .ifEmpty { file.name }
        }

        updateState {
            it.copy(
                trackTitles = trackTitles,
                trackCount = audioFiles.size,
                isLoading = false,
            )
        }
    }

    private suspend fun ensureServiceAndPlayer(): ExoPlayer? {
        player?.let { return it }

        if (pendingAudioFiles.isEmpty()) {
            updateState { it.copy(isLoading = true) }
            return null
        }

        if (mediaPlaybackController.isBookLoaded(bookUuid)) {
            val servicePlayer = mediaPlaybackController.currentPlayer
            if (servicePlayer != null) {
                player = servicePlayer
                attachPlayerListener()
                return servicePlayer
            }
        }

        val permissionGranted = notificationPermissionHandler.ensurePermission()
        if (!permissionGranted) {
            updateState {
                it.copy(
                    error = "Notification permission is required for background playback",
                    isLoading = false,
                )
            }
            return null
        }

        saveOtherBookProgressIfNeeded()

        val serviceReadyDeferred = mediaPlaybackController.prepareServiceReady()
        val serviceStarted = foregroundServiceController.startService()
        if (!serviceStarted) {
            updateState { it.copy(error = "Failed to start playback service", isLoading = false) }
            return null
        }

        val servicePlayer = mediaPlaybackController.awaitServiceReady(serviceReadyDeferred)
        if (servicePlayer == null) {
            foregroundServiceController.stopService()
            updateState { it.copy(error = "Playback service did not start in time", isLoading = false) }
            return null
        }

        player = servicePlayer
        attachPlayerListener()

        val mediaItems = pendingAudioFiles.map { file ->
            MediaItem.Builder()
                .setUri(file.toURI().toString())
                .setMediaId(file.name)
                .build()
        }
        servicePlayer.setMediaItems(mediaItems)

        mediaPlaybackController.setCurrentPlayingBook(
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.AUDIOBOOK,
            bookTitle = viewState.value.bookTitle,
            coverUrl = viewState.value.bookCoverUrl,
        )

        mediaPlaybackController.serviceInstance?.updateMetadata(
            bookTitle = viewState.value.bookTitle,
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.AUDIOBOOK,
        )

        servicePlayer.prepare()

        return servicePlayer
    }

    private suspend fun saveOtherBookProgressIfNeeded() {
        val currentBook = mediaPlaybackController.currentPlayingBook ?: return
        if (currentBook.bookUuid == bookUuid) return

        val p = mediaPlaybackController.currentPlayer ?: return

        val position = p.currentPosition.coerceAtLeast(0L)
        val trackIndex = p.currentMediaItemIndex
        val totalDuration = p.duration.coerceAtLeast(0L)
        val totalProgression = if (totalDuration > 0) {
            position.toDouble() / totalDuration.toDouble()
        } else {
            null
        }

        val progress = PositionDomainModel(
            bookUuid = currentBook.bookUuid,
            serverId = currentBook.serverId,
            timestamp = nowMillis(),
            createdAt = null,
            updatedAt = null,
            locatorHref = null,
            locatorType = null,
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = position,
            chapterIndex = trackIndex,
            progression = totalProgression,
            totalChapters = p.mediaItemCount,
            totalDurationMs = if (totalDuration > 0) totalDuration else null,
            totalProgression = totalProgression,
            position = null,
        )
        withContext(NonCancellable) {
            try {
                saveReadingProgressUseCase(progress)
            } catch (e: Exception) {
                Logger.e(e) { "Failed to save other book audiobook progress" }
            }
        }
    }

    private fun attachPlayerListener() {
        val p = player ?: return
        playerListener?.let { p.removeListener(it) }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState { it.copy(playbackState = playbackState) }
                if (playbackState == Player.STATE_READY) {
                    updateState {
                        it.copy(
                            isLoading = false,
                            totalDurationMs = p.duration.coerceAtLeast(0L),
                            trackCount = p.mediaItemCount,
                        )
                    }
                    if (!hasRestoredPosition) {
                        hasRestoredPosition = true
                        restoreProgress()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    saveProgress()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val trackIndex = p.currentMediaItemIndex
                updateState { it.copy(currentTrackIndex = trackIndex) }
                saveProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                Logger.e(error) { "Audiobook playback error" }
                updateState { it.copy(error = error.message ?: "Playback error", isLoading = false) }
            }
        }

        p.addListener(listener)
        playerListener = listener
    }

    private fun restoreProgress() {
        viewModelScope.launch {
            try {
                val totalDuration = player?.duration ?: return@launch
                if (totalDuration <= 0) return@launch

                val savedPosition = loadSavedPosition()
                savedPosition?.audioTimestampMs?.let { timestampMs ->
                    val trackIndex = savedPosition.chapterIndex ?: 0
                    val p = player ?: return@launch
                    if (trackIndex in 0 until p.mediaItemCount) {
                        p.seekTo(trackIndex, timestampMs)
                        updateState {
                            it.copy(
                                currentTrackIndex = trackIndex,
                                currentPositionMs = timestampMs,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to restore audiobook progress" }
            }
        }
    }

    private suspend fun loadSavedPosition(): PositionDomainModel? {
        return try {
            getReadingProgressWithConflictUseCase(serverId, bookUuid)
                .getOrElse { null }
                ?.let { result ->
                    when (result) {
                        is ReadingProgressResult.Resolved -> result.position
                        is ReadingProgressResult.Conflict -> result.localPosition
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                val p = player ?: break
                val position = p.currentPosition.coerceAtLeast(0L)
                updateState { it.copy(currentPositionMs = position) }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    private fun saveProgress() {
        val p = player ?: return

        saveProgress(buildProgress(p))
    }

    private fun buildProgress(p: ExoPlayer): PositionDomainModel {
        val position = p.currentPosition.coerceAtLeast(0L)
        val trackIndex = p.currentMediaItemIndex
        val totalDuration = p.duration.coerceAtLeast(0L)
        val totalProgression = if (totalDuration > 0) {
            position.toDouble() / totalDuration.toDouble()
        } else {
            null
        }

        return PositionDomainModel(
            bookUuid = bookUuid,
            serverId = serverId,
            timestamp = nowMillis(),
            createdAt = null,
            updatedAt = null,
            locatorHref = null,
            locatorType = null,
            locatorTitle = null,
            locatorTarget = null,
            audioTimestampMs = position,
            chapterIndex = trackIndex,
            progression = totalProgression,
            totalChapters = p.mediaItemCount,
            totalDurationMs = if (totalDuration > 0) totalDuration else null,
            totalProgression = totalProgression,
            position = null,
        )
    }

    private fun saveProgress(progress: PositionDomainModel) {
        viewModelScope.launch(NonCancellable) {
            try {
                saveReadingProgressUseCase(progress)
                    .onSuccess { routineSyncScheduler.markDirty() }
            } catch (e: Exception) {
                Logger.w(e) { "Failed to save audiobook progress" }
            }
        }
    }

    override fun onIntent(intent: AudiobookPlayerIntent) {
        when (intent) {
            AudiobookPlayerIntent.PlayPauseClicked -> {
                val p = player
                if (p != null) {
                    if (p.isPlaying) {
                        p.pause()
                    } else {
                        p.play()
                    }
                } else {
                    viewModelScope.launch {
                        val servicePlayer = ensureServiceAndPlayer()
                        servicePlayer?.play()
                    }
                }
            }

            AudiobookPlayerIntent.SkipForwardClicked -> {
                player?.seekForward()
            }

            AudiobookPlayerIntent.SkipBackwardClicked -> {
                player?.seekBack()
            }

            AudiobookPlayerIntent.NextTrackClicked -> {
                player?.seekToNextMediaItem()
            }

            AudiobookPlayerIntent.PreviousTrackClicked -> {
                val p = player ?: return
                if (p.currentPosition > TRACK_RESET_THRESHOLD_MS) {
                    p.seekTo(0)
                } else {
                    p.seekToPreviousMediaItem()
                }
            }

            is AudiobookPlayerIntent.SeekTo -> {
                player?.seekTo(intent.positionMs)
                updateState { it.copy(currentPositionMs = intent.positionMs) }
            }

            is AudiobookPlayerIntent.SelectTrack -> {
                val p = player ?: return
                if (intent.trackIndex in 0 until p.mediaItemCount) {
                    p.seekTo(intent.trackIndex, 0)
                    p.play()
                }
            }

            is AudiobookPlayerIntent.PlaybackSpeedChanged -> {
                player?.playbackParameters = PlaybackParameters(intent.speed)
                updateState { it.copy(playbackSpeed = intent.speed) }
            }
        }
    }

    fun close() {
        val p = player
        val isPlaybackActive = p != null && p.isPlaying
        val finalProgress = p?.let(::buildProgress)
        viewModelScope.launch(NonCancellable) {
            finalProgress?.let { progress -> saveProgressAndWait(progress) }
            routineSyncScheduler.close()
            syncNowUseCase(
                SyncRequest(
                    reason = SyncTriggerReason.READER_CHECKPOINT,
                    scope = SyncScope.Books(setOf(bookUuid)),
                    urgency = SyncUrgency.URGENT,
                ),
            )
        }

        if (!isPlaybackActive) {
            foregroundServiceController.stopService()
        }

        playerListener?.let { p?.removeListener(it) }
        playerListener = null
        player = null
        stopPositionUpdates()

        onClose()
    }

    override fun onCleared() {
        routineSyncScheduler.close()
        super.onCleared()
        stopPositionUpdates()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null

        val isPlaybackActive = mediaPlaybackController.isPlayingBook(bookUuid)
        if (!isPlaybackActive) {
            foregroundServiceController.stopService()
        }
        player = null
    }

    private suspend fun saveProgressAndWait(progress: PositionDomainModel) {
        try {
            saveReadingProgressUseCase(progress)
        } catch (e: Exception) {
            Logger.w(e) { "Failed to save final audiobook progress" }
        }
    }

    companion object {
        private const val POSITION_UPDATE_INTERVAL_MS = 500L
        private const val TRACK_RESET_THRESHOLD_MS = 3_000L
    }
}
