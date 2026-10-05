package com.retro99.reader.ui.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Small state of the dictionary "speak word" path: what the speaker button shows. */
sealed interface SpeakWordState {
    data object Idle : SpeakWordState

    /** Synthesizing; the engine may be loading first. [voiceName] explains the wait in the UI. */
    data class Preparing(val voiceName: String) : SpeakWordState

    data object Speaking : SpeakWordState
}

/** One failed word synthesis; the UI shows one line and analytics logs one event. */
data class SpeakWordFailure(
    val voiceId: String,
    val isNeural: Boolean,
)

/** A word resolved to a usable voice, ready to synthesize. */
data class ResolvedWord(
    val text: String,
    val voiceId: String,
    val voiceName: String,
    val isNeural: Boolean,
    val rate: Float,
)

/** Opaque handle to one synthesized word's audio; the platform player consumes it. */
interface WordAudioClip

/** Synthesizes one word's audio through the shared audio cache. */
interface WordAudioSource {
    /**
     * Returns the clip, or null on failure. May load a cold engine on demand; the caller has
     * already verified the voice is usable, so this must never start a pack download.
     */
    suspend fun synthesize(text: String, voiceId: String, rate: Float): WordAudioClip?
}

/** Plays one word clip at a time. */
interface WordPlayer {
    /** Plays the clip and returns when it ends or is stopped. */
    suspend fun play(clip: WordAudioClip)

    fun stop()
}

/**
 * Audio a word must speak over (device read-aloud, recorded narration, audiobook).
 * Implementations are paused only when playing and resumed only when this run paused them.
 */
interface WordAudioInterruption {
    fun isPlayingNow(): Boolean

    fun pause()

    fun resume()
}

/**
 * Speaks one selected word: synthesize first (narration keeps playing while the word is
 * prepared), then pause whatever is playing, play, and resume exactly what was paused in a
 * finally block. Cancellation stops only this word's work: it never calls the synthesizer's
 * global stop(), which would cancel read-aloud's in-flight sentence. A new [speak] replaces
 * the request in flight; [stop] cancels it.
 */
class SpeakWordCoordinator(
    private val scope: CoroutineScope,
    private val audioSource: WordAudioSource,
    private val player: WordPlayer,
    private val interruptions: List<WordAudioInterruption> = emptyList(),
) {

    private val _state = MutableStateFlow<SpeakWordState>(SpeakWordState.Idle)
    val state: StateFlow<SpeakWordState> = _state.asStateFlow()

    private val _failures = MutableSharedFlow<SpeakWordFailure>(extraBufferCapacity = 4)
    val failures: SharedFlow<SpeakWordFailure> = _failures.asSharedFlow()

    private var job: Job? = null

    fun speak(word: ResolvedWord) {
        val previous = job
        job = scope.launch {
            // Serialized so the replaced request finishes its cleanup (stop player, resume
            // paused audio, Idle) before this one flips the state again.
            previous?.cancelAndJoin()
            var paused: List<WordAudioInterruption> = emptyList()
            try {
                _state.value = SpeakWordState.Preparing(word.voiceName)
                val clip = audioSource.synthesize(text = word.text, voiceId = word.voiceId, rate = word.rate)
                if (clip == null) {
                    _failures.tryEmit(SpeakWordFailure(voiceId = word.voiceId, isNeural = word.isNeural))
                    return@launch
                }
                paused = interruptions.filter { interruption -> interruption.isPlayingNow() }
                paused.forEach { interruption -> interruption.pause() }
                _state.value = SpeakWordState.Speaking
                player.play(clip)
            } finally {
                player.stop()
                paused.forEach { interruption -> interruption.resume() }
                _state.value = SpeakWordState.Idle
            }
        }
    }

    fun stop() {
        val running = job ?: return
        job = null
        running.cancel()
    }

    fun close() = stop()
}
