package com.retro99.reader.ui.tts

import java.io.File
import java.io.IOException
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

    override val durationMs: Long
        get() = reportedDurationMs

    override val currentMediaId: String?
        get() = items.getOrNull(itemPosition)?.mediaId

    override val itemCount: Int
        get() = items.size

    override fun hasNextItem(): Boolean = itemPosition < items.lastIndex

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
        commands += "setItems:${items.joinToString(",") { it.mediaId }}"
    }

    override fun addItem(item: TtsEnginePlayerItem) {
        items = items + item
        commands += "addItem:${item.mediaId}"
    }

    override fun clearItems() {
        items = emptyList()
        itemPosition = 0
        commands += "clearItems"
    }

    override fun prepare() {
        commands += "prepare"
    }

    override fun play() {
        commands += "play"
    }

    override fun pause() {
        commands += "pause"
    }

    override fun stop() {
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
            forEach { it.onEnded() }
        }
    }

    /** The player moved to something that is not read-aloud audio. */
    fun reportExternalItem() {
        forEach { it.onItemTransition("audiobook:1", isAutoAdvance = false) }
    }

    private fun forEach(block: (TtsEnginePlayerListener) -> Unit) {
        listeners.toList().forEach(block)
    }
}

internal class FakeTtsEnginePlayerProvider(
    private val player: FakeTtsEnginePlayer,
) : TtsEnginePlayerProvider {

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

    val requestedTexts = mutableListOf<String>()

    var durationMs: Long? = 1_000L

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult {
        requestedTexts += text
        yield()
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
