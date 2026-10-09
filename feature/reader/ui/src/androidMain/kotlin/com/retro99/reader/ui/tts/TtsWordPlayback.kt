package com.retro99.reader.ui.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.playback.MediaPlaybackController
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

class FileWordAudioClip(val file: File) : WordAudioClip

/**
 * Lean single-clip player for spoken words: a private ExoPlayer with no MediaSession and no
 * analytics, kept apart from [TtsPreviewPlayer] so the word path never feeds the Voices-sheet
 * state or the narration loading indicator.
 */
@Scope(ReaderScope::class)
@Scoped
class TtsWordPlayer(
    @Provided private val context: Context,
) : WordPlayer, AutoCloseable {

    private var player: ExoPlayer? = null
    private var pending: CompletableDeferred<Unit>? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finish()
        }

        override fun onPlayerError(error: PlaybackException) {
            finish()
        }
    }

    override suspend fun play(clip: WordAudioClip) {
        val file = (clip as? FileWordAudioClip)?.file ?: return
        if (!file.isFile) return
        val completion = CompletableDeferred<Unit>()
        pending = completion
        ensurePlayer().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setMediaId("tts-word")
                    .setUri(Uri.fromFile(file))
                    .build(),
            )
            prepare()
            play()
        }
        completion.await()
    }

    override fun stop() {
        player?.stop()
        player?.clearMediaItems()
        finish()
    }

    private fun finish() {
        pending?.complete(Unit)
        pending = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { currentPlayer -> return currentPlayer }
        return ExoPlayer.Builder(context).build().also { wordPlayer ->
            wordPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            wordPlayer.addListener(playerListener)
            player = wordPlayer
        }
    }

    override fun close() {
        stop()
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }
}

/**
 * Word synthesis through the shared audio cache. While a word is synthesized, sentence
 * prefetch is held and queued prefetch past the next sentence is dropped, so the word takes
 * the single synthesis permit ahead of read-aloud's backlog. The sentence that plays next is
 * never cancelled.
 */
class TtsWordAudioSource(
    private val audioGenerator: TtsAudioGenerator,
    private val engine: TtsReadAloudEngine,
) : WordAudioSource {

    override suspend fun synthesize(text: String, voiceId: String, rate: Float): WordAudioClip? {
        audioGenerator.findCached(text = text, voiceId = voiceId, rate = rate, pitch = WORD_PITCH)
            ?.let { cached -> return FileWordAudioClip(cached) }
        engine.holdPrefetchForWord()
        try {
            val result = audioGenerator.synthesize(
                text = text,
                voiceId = voiceId,
                rate = rate,
                pitch = WORD_PITCH,
            )
            val file = result.file
            if (result.status != TtsSynthesisStatus.SUCCESS || file == null || !file.exists()) {
                return null
            }
            val clip = withContext(Dispatchers.IO) { prepareWordClipFile(file) }
            return FileWordAudioClip(clip)
        } finally {
            engine.resumePrefetchAfterWord()
        }
    }

    private companion object {
        const val WORD_PITCH = 1f
    }
}

/**
 * The clip the word player plays, out of the file the audio cache holds. Trim the engine's
 * padding while the file is fresh: Supertonic pads a word with ~0.35 s + ~0.5 s of silence
 * (bench 2026-10-05).
 */
internal fun prepareWordClipFile(cacheFile: File): File = trimWavSilence(cacheFile)

/** Recorded narration and the audiobook share the service player, so one interruption covers both. */
class MediaPlaybackWordInterruption(
    private val mediaPlaybackController: MediaPlaybackController,
) : WordAudioInterruption {

    override fun isPlayingNow(): Boolean = mediaPlaybackController.isPlaying.value

    override fun pause() {
        mediaPlaybackController.pause()
    }

    override fun resume() {
        mediaPlaybackController.play()
    }
}
