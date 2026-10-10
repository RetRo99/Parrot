package com.retro99.parrot.di

import android.content.Context
import android.content.ContextWrapper
import com.retro99.base.AppInitializer
import com.retro99.reader.ui.tts.TtsChapterPreparationJob
import com.retro99.reader.ui.tts.TtsChapterPreparationState
import com.retro99.reader.ui.tts.TtsSynthesizer
import com.retro99.reader.ui.tts.TtsSynthesisResult
import com.retro99.reader.ui.tts.TtsVoice
import java.io.File
import org.koin.dsl.module
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PreparedChapterJobWiringTest {
    @Test
    fun `the chapter preparation job resolves from the real app graph as one app wide single`() =
        RealAppGraph().use { graph ->
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val context = ((field.get(null) as Unsafe).allocateInstance(FileContext::class.java) as FileContext).apply {
                root = graph.stagingRoot
            }
            graph.koin.loadModules(listOf(module {
                single<Context> { context }
                single<TtsSynthesizer> { HostSynthesizer() }
            }))
            val job = graph.koin.get<TtsChapterPreparationJob>()
            assertSame(job, graph.koin.get<TtsChapterPreparationJob>())
            assertEquals(TtsChapterPreparationState.Idle, job.state.value)
        }

    /**
     * Prepared-chapter backup is the one thing that hands a chapter to the
     * transfer engine, and it is reached as an [AppInitializer] so the sweep for
     * chapters prepared earlier runs at app start. It is internal to the reader
     * module, so it is named rather than typed here; what matters is that the
     * real graph can build it, with the cloud account repositories, the
     * transfer manager and the network signal it needs, and that it is one
     * app-wide instance.
     */
    @Test
    fun `prepared chapter backup resolves from the real app graph as one app wide initializer`() {
        RealAppGraph().use { graph ->
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val context = ((field.get(null) as Unsafe).allocateInstance(FileContext::class.java) as FileContext).apply {
                root = graph.stagingRoot
            }
            graph.koin.loadModules(listOf(module {
                single<Context> { context }
                single<TtsSynthesizer> { HostSynthesizer() }
            }))

            val backup = graph.koin.getAll<AppInitializer>()
                .singleOrNull { it::class.qualifiedName == BACKUP_CLASS }
            assertNotNull(backup, "no $BACKUP_CLASS among the app initializers")
            assertSame(
                backup,
                graph.koin.getAll<AppInitializer>().single { it::class.qualifiedName == BACKUP_CLASS },
            )
            // Creating it attaches it to the preparation job, so a chapter that
            // finishes preparing is offered for backup without the reader
            // screen having to arrange it.
            assertNotNull(graph.koin.get<TtsChapterPreparationJob>())
        }
    }

    private companion object {
        const val BACKUP_CLASS = "com.retro99.reader.ui.tts.TtsPreparedChapterBackup"
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
