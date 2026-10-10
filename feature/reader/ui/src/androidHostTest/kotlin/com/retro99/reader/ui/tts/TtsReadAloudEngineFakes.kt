package com.retro99.reader.ui.tts

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield

/**
 * A player that records what the engine asked of it and reports callbacks only when the
 * test says so, the way a real player does: ExoPlayer delivers its callbacks on the
 * application looper, after the call that caused them has returned.
 */
internal class FakeTtsEnginePlayer : TtsEnginePlayer {

    private val listeners = mutableListOf<TtsEnginePlayerListener>()

    /** Every command the engine sent, in order. */
    val commands = mutableListOf<String>()

    var items: List<TtsEnginePlayerItem> = emptyList()
        private set

    /** Index into [items] of the item the player is on. */
    var itemPosition: Int = 0
        private set

    var reportedDurationMs: Long = 1_000L

    var pitch: Float = 1f
        private set

    var seekedToMs: Long? = null
        private set

    var isReleased: Boolean = false
        private set

    /**
     * The queue ran out and the player is in ExoPlayer's `STATE_ENDED`. An item appended
     * afterwards does not undo it, and a [play] is answered with nothing at all — the
     * state the phone sat in at 20:36:13 on 2026-10-09.
     */
    var isAtEndOfQueue: Boolean = false
        private set

    /**
     * When true the player answers the engine's commands the way a real one does: a [play]
     * reports the item it moved to, readiness and `isPlaying`, a [pause] reports
     * `isPlaying=false`. Off by default, so tests written before this flag keep driving
     * every callback themselves.
     */
    var reportsPlaybackItself: Boolean = false

    /** Every item the engine ever queued, in order, across playlists. */
    val queuedItems = mutableListOf<TtsEnginePlayerItem>()

    override val durationMs: Long
        get() = reportedDurationMs

    override val currentMediaId: String?
        get() = items.getOrNull(itemPosition)?.mediaId

    override val itemCount: Int
        get() = items.size

    override fun hasNextItem(): Boolean = itemPosition < items.lastIndex

    /** How many listeners are attached, so a test can see the engine let the player go. */
    val listenerCount: Int
        get() = listeners.size

    override fun addListener(listener: TtsEnginePlayerListener) {
        if (listeners.none { it === listener }) listeners += listener
    }

    override fun removeListener(listener: TtsEnginePlayerListener) {
        listeners.removeAll { it === listener }
    }

    override fun setPlaybackPitch(pitch: Float) {
        this.pitch = pitch
        commands += "pitch:$pitch"
    }

    override fun setItems(items: List<TtsEnginePlayerItem>) {
        this.items = items.toList()
        itemPosition = 0
        isAtEndOfQueue = false
        queuedItems += items
        commands += "setItems:${items.joinToString(",") { it.mediaId }}"
    }

    override fun addItem(item: TtsEnginePlayerItem) {
        items = items + item
        queuedItems += item
        commands += "addItem:${item.mediaId}"
    }

    override fun clearItems() {
        items = emptyList()
        itemPosition = 0
        isAtEndOfQueue = false
        commands += "clearItems"
    }

    override fun prepare() {
        commands += "prepare"
    }

    override fun play() {
        if (isGone) {
            commands += "play-on-gone-player"
            return
        }
        commands += "play"
        if (!reportsPlaybackItself) return
        // A player whose queue has run out answers a play with nothing: STATE_ENDED is
        // only left by a seek or a new playlist.
        if (isAtEndOfQueue || items.isEmpty()) {
            commands += "play-ignored-ended"
            return
        }
        reportPlaylistItem()
        reportReady()
        reportPlaying(true)
    }

    override fun pause() {
        if (isGone) {
            commands += "pause-on-gone-player"
            return
        }
        commands += "pause"
        if (reportsPlaybackItself) reportPlaying(false)
    }

    override fun stop() {
        isAtEndOfQueue = false
        commands += "stop"
    }

    override fun seekTo(positionMs: Long) {
        seekedToMs = positionMs
        commands += "seekTo:$positionMs"
    }

    override fun release() {
        isReleased = true
        commands += "release"
    }

    // ---- what the real player reports back, driven by the test ----

    /** The player announced the item it is on, as ExoPlayer does for a new playlist. */
    fun reportPlaylistItem() {
        forEach { it.onItemTransition(currentMediaId, isAutoAdvance = false) }
    }

    fun reportReady() {
        forEach { it.onReady() }
    }

    fun reportPlaying(isPlaying: Boolean) {
        forEach { it.onIsPlayingChanged(isPlaying) }
    }

    fun reportOutsidePause() {
        forEach { it.onPlayWhenReadyChanged(false) }
        reportPlaying(false)
    }

    fun reportOutsideResume() {
        forEach { it.onPlayWhenReadyChanged(true) }
        reportPlaying(true)
    }

    fun reportSeeked(positionMs: Long) {
        forEach { it.onSeeked(positionMs) }
    }

    fun reportError(error: Throwable) {
        forEach { it.onError(error) }
    }

    /** The item the player is on played out: on to the next queued one, or the end. */
    fun finishCurrentItem() {
        if (hasNextItem()) {
            itemPosition++
            forEach { it.onItemTransition(currentMediaId, isAutoAdvance = true) }
        } else {
            reportEnded()
        }
    }

    /**
     * The audio that was playing ran out and the end-of-queue callback arrives — even if
     * an item was appended behind the player in the meantime, which is the ordering the
     * phone logged at 20:36:13 (an appended clip 5 ms before `state=ENDED`).
     */
    fun reportEnded() {
        isAtEndOfQueue = true
        forEach { it.onEnded() }
        if (reportsPlaybackItself) reportPlaying(false)
    }

    /**
     * The player was taken away under the engine: the media service was destroyed, or it
     * was released from outside. Afterwards it answers commands with nothing, the way a
     * released ExoPlayer does — it only logs "sending message to a Handler on a dead
     * thread" — and it reports no callback of its own.
     */
    fun reportPlayerGone() {
        forEach { it.onPlayerGone() }
        isGone = true
    }

    /** True once [reportPlayerGone] was called: every later command is a no-op. */
    var isGone: Boolean = false
        private set

    /** The player moved to something that is not read-aloud audio. */
    fun reportExternalItem() {
        forEach { it.onItemTransition("audiobook:1", isAutoAdvance = false) }
    }

    private fun forEach(block: (TtsEnginePlayerListener) -> Unit) {
        listeners.toList().forEach(block)
    }
}

internal class FakeTtsEnginePlayerProvider(
    player: FakeTtsEnginePlayer,
) : TtsEnginePlayerProvider {

    /**
     * The player the next acquisition hands out. A test whose player goes away swaps in a
     * fresh one, as the production provider does when the service is started again.
     */
    var player: FakeTtsEnginePlayer = player

    var localPlayersCreated: Int = 0
        private set

    var notificationPlayersAcquired: Int = 0
        private set

    override suspend fun notificationPlayer(): TtsEnginePlayer {
        notificationPlayersAcquired++
        return player
    }

    override fun createLocalPlayer(): TtsEnginePlayer {
        localPlayersCreated++
        return player
    }
}

/**
 * Synthesis the test controls: a sentence's text decides whether it succeeds, returns an
 * error result or throws. Always suspends once, as real synthesis does.
 */
internal class FakeSentenceAudioSource(private val directory: File) : TtsSentenceAudioSource {

    /** Texts whose synthesis returns [TtsSynthesisStatus.ERROR]. */
    val failingTexts = mutableSetOf<String>()

    /** Texts whose synthesis throws. */
    val throwingTexts = mutableSetOf<String>()

    /** Texts whose synthesis reports cancellation. */
    val cancelledTexts = mutableSetOf<String>()

    /** Texts whose synthesis never completes, as a wedged engine never does. */
    val neverCompletingTexts = mutableSetOf<String>()

    /**
     * Texts whose synthesis waits for [completeSynthesis], so a test can sit inside the
     * gap a slow voice leaves between sentences: the clip that is playing has ended and
     * the next one is still being made.
     */
    val deferredTexts = mutableSetOf<String>()

    val requestedTexts = mutableListOf<String>()

    /** Every request with the settings it was made with, in order. */
    val requests = mutableListOf<SynthesisRequest>()

    var durationMs: Long? = 1_000L

    data class SynthesisRequest(
        val text: String,
        val voiceId: String?,
        val rate: Float,
        val pitch: Float,
    )

    /** One gate per text: once opened, every later synthesis of that text runs through. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val awaitedTexts = mutableSetOf<String>()

    /** Lets the synthesis of [text] finish, whether it is waiting yet or not. */
    fun completeSynthesis(text: String) {
        gateFor(text).complete(Unit)
    }

    /** True while a synthesis of [text] is waiting for [completeSynthesis]. */
    fun isAwaitingCompletion(text: String): Boolean = text in awaitedTexts

    private fun gateFor(text: String): CompletableDeferred<Unit> =
        gates.getOrPut(text) { CompletableDeferred() }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult {
        requestedTexts += text
        requests += SynthesisRequest(text = text, voiceId = voiceId, rate = rate, pitch = pitch)
        yield()
        if (text in neverCompletingTexts) awaitCancellation()
        if (text in deferredTexts) {
            awaitedTexts += text
            try {
                gateFor(text).await()
            } finally {
                awaitedTexts -= text
            }
        }
        if (text in throwingTexts) throw IOException("synthesis failed for \"$text\"")
        if (text in failingTexts) {
            return TtsSynthesisResult(status = TtsSynthesisStatus.ERROR, error = "no audio")
        }
        if (text in cancelledTexts) {
            return TtsSynthesisResult(status = TtsSynthesisStatus.CANCELLED)
        }
        val file = File(directory, "${text.hashCode()}-$voiceId-$rate-$pitch.wav")
        file.writeBytes(ByteArray(WAV_BYTES))
        return TtsSynthesisResult(
            status = TtsSynthesisStatus.SUCCESS,
            file = file,
            durationMs = durationMs,
        )
    }

    private companion object {
        const val WAV_BYTES = 64
    }
}

internal class FakeTtsSynthesizer : TtsSynthesizer {

    private val voice = TtsVoice(id = "en-us", name = "Test voice", locale = "en-US")

    override fun isReady(): Boolean = true

    override suspend fun awaitReady(timeoutMs: Long): Boolean = true

    override fun availableVoices(): List<TtsVoice> = listOf(voice)

    override fun defaultVoice(): TtsVoice = voice

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult = TtsSynthesisResult(status = TtsSynthesisStatus.ERROR)

    override fun stop() = Unit

    override suspend fun release() = Unit
}
