package com.retro99.parrot.di

import android.content.Context
import android.content.ContextWrapper
import com.retro99.reader.ui.tts.TtsAudioGenerator
import com.retro99.reader.ui.tts.TtsSentenceAudioSource
import com.retro99.reader.ui.tts.TtsSynthesizer
import com.retro99.reader.ui.tts.TtsSynthesisResult
import com.retro99.reader.ui.tts.TtsVoice
import java.io.File
import org.koin.dsl.module
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertSame

class PreparedGeneratorWiringTest {
    @Test
    fun `real generator graph includes prepared store and selected encoder bindings`() = RealAppGraph().use { graph ->
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val context = ((field.get(null) as Unsafe).allocateInstance(FileContext::class.java) as FileContext).apply {
            root = graph.stagingRoot
        }
        graph.koin.loadModules(listOf(module {
            single<Context> { context }
            single<TtsSynthesizer> { HostSynthesizer() }
        }))
        val generator = graph.koin.get<TtsAudioGenerator>()
        assertSame(generator, graph.koin.get<TtsSentenceAudioSource>())
        assertSame(generator, graph.koin.get<TtsAudioGenerator>())
    }

    private class FileContext : ContextWrapper(null) {
        lateinit var root: File
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private class HostSynthesizer : TtsSynthesizer {
        override fun isReady() = true
        override suspend fun awaitReady(timeoutMs: Long) = true
        override fun availableVoices() = emptyList<TtsVoice>()
        override fun defaultVoice(): TtsVoice? = null
        override suspend fun synthesize(text: String, voiceId: String?, rate: Float, pitch: Float, outputFile: File): TtsSynthesisResult =
            error("This graph test does not synthesize")
        override fun stop() = Unit
        override suspend fun release() = Unit
    }
}
