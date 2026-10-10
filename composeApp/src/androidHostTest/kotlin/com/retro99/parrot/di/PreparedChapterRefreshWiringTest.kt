package com.retro99.parrot.di

import android.content.Context
import android.content.ContextWrapper
import com.retro99.base.AppInitializer
import com.retro99.reader.ui.tts.TtsSynthesizer
import com.retro99.reader.ui.tts.TtsSynthesisResult
import com.retro99.reader.ui.tts.TtsVoice
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import org.koin.dsl.module
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The transfer engine settling has to reach the row, and only the real graph can show that
 * it does: the two queues are built inside prepared-chapter backup, so a unit test that
 * builds a queue by hand passes whether or not anything passed it the signal.
 *
 * Prepared-chapter backup is internal to the reader module, so it is reached by name and
 * read reflectively, exactly as [PreparedChapterJobWiringTest] reaches it by name.
 */
class PreparedChapterRefreshWiringTest {

    @Test
    fun `the queues the real graph builds are wired to the signal the row watches`() {
        RealAppGraph().use { graph ->
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val context = ((field.get(null) as Unsafe).allocateInstance(FileContext::class.java) as FileContext)
                .apply { root = graph.stagingRoot }
            graph.koin.loadModules(
                listOf(
                    module {
                        single<Context> { context }
                        single<TtsSynthesizer> { HostSynthesizer() }
                    },
                ),
            )

            val backup = assertNotNull(
                graph.koin.getAll<AppInitializer>().singleOrNull { it::class.qualifiedName == BACKUP_CLASS },
                "no $BACKUP_CLASS among the app initializers",
            )

            @Suppress("UNCHECKED_CAST")
            val settled = backup::class.java.getDeclaredMethod("getSettled")
                .apply { isAccessible = true }
                .invoke(backup) as StateFlow<Int>
            assertEquals(0, settled.value, "nothing has settled yet")

            // Both queues are lazy, so forcing them is what proves what they were given.
            for (name in listOf("queue", "downloads")) {
                val before = settled.value
                val lazyField = backup::class.java.getDeclaredField("$name\$delegate")
                    .apply { isAccessible = true }
                val queue = (lazyField.get(backup) as Lazy<*>).value
                val callback = assertNotNull(
                    queue!!::class.java.getDeclaredField("onTransferChanged")
                        .apply { isAccessible = true }
                        .get(queue) as? Function0<*>,
                    "$name was built without a refresh signal",
                )
                callback.invoke()
                assertTrue(
                    settled.value > before,
                    "$name's signal does not reach the flow the row watches",
                )
            }
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
        override suspend fun synthesize(
            text: String,
            voiceId: String?,
            rate: Float,
            pitch: Float,
            outputFile: File,
        ): TtsSynthesisResult = error("This graph test does not synthesize")
        override fun stop() = Unit
        override suspend fun release() = Unit
    }
}
