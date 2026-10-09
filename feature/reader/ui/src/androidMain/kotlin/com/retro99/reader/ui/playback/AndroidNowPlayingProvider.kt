package com.retro99.reader.ui.playback

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import org.koin.core.annotation.Single

/**
 * Android implementation of NowPlayingProvider.
 * Wraps MediaPlaybackController to expose now-playing state to common UI components.
 */
@Single(binds = [NowPlayingProvider::class])
class AndroidNowPlayingProvider(
    private val mediaPlaybackController: MediaPlaybackController,
    private val readAloud: ReadAloudPlayback,
) : NowPlayingProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val nowPlayingInfo: StateFlow<NowPlayingInfo?> =
        mediaPlaybackController.nowPlayingBook
            .map { info ->
                info?.let {
                    NowPlayingInfo(
                        serverId = it.serverId,
                        bookUuid = it.bookUuid,
                        bookType = it.bookType,
                        bookTitle = it.bookTitle ?: "Playing...",
                        coverUrl = it.coverUrl,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val isPlaying: StateFlow<Boolean> = mediaPlaybackController.isPlaying

    override fun togglePlayPause() {
        if (mediaPlaybackController.isPlaying.value) {
            mediaPlaybackController.pause()
        } else {
            mediaPlaybackController.play()
        }
    }

    override fun stop() {
        mediaPlaybackController.stop()
        mediaPlaybackController.clearCurrentPlayingBook()
    }
}

