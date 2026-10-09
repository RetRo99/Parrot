package com.retro99.reader.ui.playback

import com.retro99.reader.ui.tts.TtsReadAloudEngine
import org.koin.core.annotation.Single

/**
 * Stopping the device voice, for whoever stops playback without knowing what is making it.
 *
 * The read-aloud engine outlives the reader screen on purpose, and a stop of the media
 * session alone leaves it with a session of its own: its synthesis in flight, its sentence
 * index and its claim on the service player (QA-BUG-0049). One stop has to reach both.
 */
fun interface ReadAloudPlayback {
    fun stop()
}

@Single(binds = [ReadAloudPlayback::class])
class EngineReadAloudPlayback(
    private val engine: TtsReadAloudEngine,
) : ReadAloudPlayback {
    override fun stop() = engine.stop()
}
