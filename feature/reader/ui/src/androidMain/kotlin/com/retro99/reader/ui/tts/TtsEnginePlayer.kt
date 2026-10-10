package com.retro99.reader.ui.tts

import java.io.File

/**
 * The player calls [TtsReadAloudEngine] makes, and nothing more. The production
 * implementation wraps ExoPlayer ([ExoTtsEnginePlayer]); a host test supplies its own,
 * which is the only way to run the engine off a device: ExoPlayer, `MediaItem` and
 * `android.net.Uri` all need an Android runtime.
 */
interface TtsEnginePlayer {

    /** The raw player duration, including ExoPlayer's "unset" value; callers coerce. */
    val durationMs: Long

    val currentMediaId: String?

    val itemCount: Int

    fun hasNextItem(): Boolean

    fun addListener(listener: TtsEnginePlayerListener)

    fun removeListener(listener: TtsEnginePlayerListener)

    /** Playback speed stays at 1; only the pitch is set, as the engine always has. */
    fun setPlaybackPitch(pitch: Float)

    fun setItems(items: List<TtsEnginePlayerItem>)

    fun addItem(item: TtsEnginePlayerItem)

    fun clearItems()

    fun prepare()

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    fun release()
}

/** One queued sentence: its media id, its audio file and the notification metadata. */
data class TtsEnginePlayerItem(
    val mediaId: String,
    val file: File,
    val title: String,
    val artist: String,
    val displayTitle: String,
    val artworkData: ByteArray?,
)

/** The player callbacks the engine reacts to. */
interface TtsEnginePlayerListener {

    /**
     * The player moved to [mediaId], null when it moved to no item at all.
     * [isAutoAdvance] means the previous item played out, rather than a seek or a new
     * playlist.
     */
    fun onItemTransition(mediaId: String?, isAutoAdvance: Boolean)

    fun onSeeked(positionMs: Long)

    fun onIsPlayingChanged(isPlaying: Boolean)

    /** Playing intent changes even when no audio is audible (buffering or synthesis). */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) = Unit

    /** The last queued item played out. */
    fun onEnded()

    /** The current item is ready to play. */
    fun onReady()

    fun onError(error: Throwable)
}

/**
 * Hands the engine the player it should use: the media session's player when a
 * notification is wanted, a local one when it is not.
 */
interface TtsEnginePlayerProvider {

    /**
     * The media service's player, starting the service if it is not running yet; null
     * when the service cannot be started. The same player is always returned as the same
     * [TtsEnginePlayer] instance, so the engine can tell it is already attached.
     */
    suspend fun notificationPlayer(): TtsEnginePlayer?

    /** A fresh player owned by the engine, with the read-aloud audio attributes set. */
    fun createLocalPlayer(): TtsEnginePlayer
}
