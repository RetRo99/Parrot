package com.retro99.ttsbench

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one file at a time. Tapping the playing key stops it; tapping another key switches.
 * Call from the main thread.
 */
class SamplePlayer {

    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)

    /** Key of the clip that is playing now, or null. */
    val playing: StateFlow<String?> = _playing

    fun toggle(key: String, file: File, speed: Float = 1f) {
        val wasPlaying = _playing.value == key
        stop()
        if (wasPlaying) return
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            next.setDataSource(file.absolutePath)
            next.setOnCompletionListener { finished ->
                if (player === finished) stop()
            }
            next.prepare()
            // Pitch stays at 1 so a stretched clip only changes tempo.
            if (speed != 1f) next.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            next.start()
            player = next
            _playing.value = key
        } catch (error: Exception) {
            Log.e(TAG, "Cannot play ${file.name}", error)
            next.release()
        }
    }

    fun stop() {
        player?.let { current ->
            runCatching { current.stop() }
            current.release()
        }
        player = null
        _playing.value = null
    }
}
