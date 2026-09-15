package com.retro99.reader.ui.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.navigator.TtsPreviewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

@Scope(ReaderScope::class)
@Scoped
class TtsPreviewPlayer(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
    private val audioGenerator: TtsAudioGenerator,
) : AutoCloseable {

    private var player: ExoPlayer? = null

    private val mutableState = MutableStateFlow(TtsPreviewState.IDLE)
    val state: StateFlow<TtsPreviewState> = mutableState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                mutableState.value = TtsPreviewState.SPEAKING
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                stop()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            analytics.logException(error, "TTS voice preview playback failed")
            stop()
        }
    }

    suspend fun play(
        chunks: List<String>,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): Boolean {
        stop()
        if (chunks.isEmpty()) return false
        mutableState.value = TtsPreviewState.LOADING

        val synthesisPitch = if (voiceId.neuralVoicePackage() != null) {
            DEFAULT_PLAYBACK_PITCH
        } else {
            pitch
        }
        val files = chunks.mapIndexed { index, chunk ->
            val result = audioGenerator.synthesize(
                text = chunk,
                voiceId = voiceId,
                rate = rate,
                pitch = synthesisPitch,
            )
            val file = result.file
            if (result.status != TtsSynthesisStatus.SUCCESS || file == null || !file.exists()) {
                analytics.logException(
                    IllegalStateException(
                        "TTS preview synthesis failed: ${result.status} ${result.error}",
                    ),
                    "TTS preview synthesis failed for chunk $index",
                )
                stop()
                return false
            }
            file
        }

        val previewPlayer = ensurePlayer()
        previewPlayer.playbackParameters = PlaybackParameters(
            DEFAULT_PLAYBACK_SPEED,
            if (voiceId.neuralVoicePackage() != null) {
                pitch.coerceIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH)
            } else {
                DEFAULT_PLAYBACK_PITCH
            },
        )
        previewPlayer.setMediaItems(
            files.mapIndexed { index, file ->
                MediaItem.Builder()
                    .setMediaId("tts-preview:$index")
                    .setUri(Uri.fromFile(file))
                    .build()
            },
        )
        previewPlayer.prepare()
        previewPlayer.play()
        return true
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        mutableState.value = TtsPreviewState.IDLE
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { currentPlayer -> return currentPlayer }
        return ExoPlayer.Builder(context).build().also { previewPlayer ->
            previewPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            previewPlayer.addListener(playerListener)
            player = previewPlayer
        }
    }

    override fun close() {
        stop()
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    private companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1f
        const val DEFAULT_PLAYBACK_PITCH = 1f
        const val MIN_PLAYBACK_PITCH = 0.25f
        const val MAX_PLAYBACK_PITCH = 4f
    }
}
