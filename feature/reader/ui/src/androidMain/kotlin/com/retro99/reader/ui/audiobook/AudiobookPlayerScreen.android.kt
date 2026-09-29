package com.retro99.reader.ui.audiobook

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.common.Player
import com.retro99.reader.ui.reader.KeepScreenOn
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
actual fun AudiobookPlayerScreen(
    serverId: String,
    bookUuid: String,
    readerOpenEntryPoint: String?,
    readerOpenCorrelationId: String?,
    onClose: () -> Unit,
) {
    val viewModel: AudiobookPlayerViewModel = koinViewModel {
        parametersOf(serverId, bookUuid, readerOpenEntryPoint, readerOpenCorrelationId, onClose)
    }
    val state by viewModel.viewState.collectAsState()

    BackHandler { viewModel.close() }

    KeepScreenOn(enabled = state.isPlaying)

    val audioPlayerState = AudioPlayerState(
        bookUuid = state.bookUuid,
        bookTitle = state.bookTitle,
        bookCoverUrl = state.bookCoverUrl,
        isPlaying = state.isPlaying,
        isBuffering = state.playbackState == Player.STATE_BUFFERING,
        isLoading = state.isLoading,
        currentPositionMs = state.currentPositionMs,
        totalDurationMs = state.totalDurationMs,
        currentTrackIndex = state.currentTrackIndex,
        trackCount = state.trackCount,
        trackTitles = state.trackTitles,
        playbackSpeed = state.playbackSpeed,
        error = state.error,
    )

    val callbacks = AudioPlayerCallbacks(
        onPlayPause = { viewModel.onIntent(AudiobookPlayerIntent.PlayPauseClicked) },
        onSkipForward = { viewModel.onIntent(AudiobookPlayerIntent.SkipForwardClicked) },
        onSkipBackward = { viewModel.onIntent(AudiobookPlayerIntent.SkipBackwardClicked) },
        onPreviousTrack = { viewModel.onIntent(AudiobookPlayerIntent.PreviousTrackClicked) },
        onNextTrack = { viewModel.onIntent(AudiobookPlayerIntent.NextTrackClicked) },
        onSeek = { positionMs -> viewModel.onIntent(AudiobookPlayerIntent.SeekTo(positionMs)) },
        onSelectTrack = { trackIndex -> viewModel.onIntent(AudiobookPlayerIntent.SelectTrack(trackIndex)) },
        onSpeedChange = { speed -> viewModel.onIntent(AudiobookPlayerIntent.PlaybackSpeedChanged(speed)) },
        onClose = { viewModel.close() },
    )

    AudioPlayerScreen(
        state = audioPlayerState,
        callbacks = callbacks,
        titleFallback = "Audiobook",
        onBack = { viewModel.close() },
    )
}
