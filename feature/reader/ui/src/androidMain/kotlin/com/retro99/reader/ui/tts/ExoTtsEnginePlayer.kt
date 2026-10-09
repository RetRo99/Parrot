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
import com.retro99.reader.ui.playback.ForegroundServiceController
import com.retro99.reader.ui.playback.MediaPlaybackController
import com.retro99.reader.ui.playback.setArtworkDataIfSmall
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** [TtsEnginePlayer] over a real ExoPlayer. */
internal class ExoTtsEnginePlayer(private val player: ExoPlayer) : TtsEnginePlayer {

    private val adapters = mutableMapOf<TtsEnginePlayerListener, Player.Listener>()

    override val durationMs: Long
        get() = player.duration

    override val currentMediaId: String?
        get() = player.currentMediaItem?.mediaId

    override val itemCount: Int
        get() = player.mediaItemCount

    override fun hasNextItem(): Boolean = player.hasNextMediaItem()

    override fun addListener(listener: TtsEnginePlayerListener) {
        val adapter = adapters.getOrPut(listener) { adapterFor(listener) }
        player.addListener(adapter)
    }

    override fun removeListener(listener: TtsEnginePlayerListener) {
        adapters.remove(listener)?.let(player::removeListener)
    }

    override fun setPlaybackPitch(pitch: Float) {
        player.playbackParameters = PlaybackParameters(DEFAULT_PLAYBACK_SPEED, pitch)
    }

    override fun setItems(items: List<TtsEnginePlayerItem>) {
        player.setMediaItems(items.map(::mediaItemFor))
    }

    override fun addItem(item: TtsEnginePlayerItem) {
        player.addMediaItem(mediaItemFor(item))
    }

    override fun clearItems() {
        player.clearMediaItems()
    }

    override fun prepare() {
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun release() {
        player.release()
    }

    private fun mediaItemFor(item: TtsEnginePlayerItem): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setDisplayTitle(item.displayTitle)
            .apply {
                setArtworkDataIfSmall(item.artworkData, TAG)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(Uri.fromFile(item.file))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun adapterFor(listener: TtsEnginePlayerListener): Player.Listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listener.onItemTransition(
                    mediaId = mediaItem?.mediaId,
                    // AUTO: the previous item played out; seeks and new playlists don't.
                    isAutoAdvance = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (
                    reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    listener.onSeeked(newPosition.positionMs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listener.onIsPlayingChanged(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> listener.onEnded()
                    Player.STATE_READY -> listener.onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener.onError(error)
            }
        }

    private companion object {
        /** Kept as the engine's tag so the artwork size warning logs where it always did. */
        const val TAG = "TtsReadAloudEngine"
        const val DEFAULT_PLAYBACK_SPEED = 1f
    }
}

/**
 * Produces the engine's players: the media service's one for notification playback, a
 * local one otherwise.
 */
@Single(binds = [TtsEnginePlayerProvider::class])
class ExoTtsEnginePlayerProvider(
    @Provided private val context: Context,
    private val mediaPlaybackController: MediaPlaybackController,
    private val foregroundServiceController: ForegroundServiceController,
) : TtsEnginePlayerProvider {

    /** One wrapper per service player, so re-acquiring it stays the same instance. */
    private var servicePlayerWrapper: Pair<ExoPlayer, TtsEnginePlayer>? = null

    override suspend fun notificationPlayer(): TtsEnginePlayer? {
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
        return wrap(servicePlayer)
    }

    override fun createLocalPlayer(): TtsEnginePlayer {
        val localPlayer = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(createAudioAttributes(), true)
        }
        return ExoTtsEnginePlayer(localPlayer)
    }

    private fun wrap(servicePlayer: ExoPlayer): TtsEnginePlayer {
        servicePlayerWrapper?.let { (player, wrapper) ->
            if (player === servicePlayer) return wrapper
        }
        return ExoTtsEnginePlayer(servicePlayer).also { wrapper ->
            servicePlayerWrapper = servicePlayer to wrapper
        }
    }

    private fun createAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
}
