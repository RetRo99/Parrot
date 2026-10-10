package com.retro99.parrot.di

import com.retro99.reader.ui.tts.AndroidTtsPreparedAudioEncoder
import com.retro99.reader.ui.tts.TtsPreparedAudioEncoder
import kotlin.test.Test
import kotlin.test.assertSame

class PreparedAudioWiringTest {
    @Test
    fun `prepared encoder resolves from real app graph as a single behind its interface`() =
        RealAppGraph().use { graph ->
            val encoder = graph.koin.get<TtsPreparedAudioEncoder>()
            assertSame(encoder, graph.koin.get<AndroidTtsPreparedAudioEncoder>())
            assertSame(encoder, graph.koin.get<TtsPreparedAudioEncoder>())
        }
}
