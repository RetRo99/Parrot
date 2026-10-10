package com.retro99.parrot.di

import android.content.Context
import android.content.ContextWrapper
import com.retro99.reader.domain.tts.PreparedAudioStorage
import com.retro99.reader.ui.tts.AndroidPreparedAudioStorage
import java.io.File
import org.koin.dsl.module
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertIs

class PreparedAudioStorageWiringTest {
    @Test
    fun `settings reads the prepared total through the real android implementation`() {
        RealAppGraph().use { graph ->
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val context = ((field.get(null) as Unsafe).allocateInstance(FileContext::class.java) as FileContext).apply {
                root = graph.stagingRoot
            }
            graph.koin.loadModules(listOf(module { single<Context> { context } }))
            // The Settings view model resolves this by interface; a missing binding would
            // silently report no prepared audio at all.
            assertIs<AndroidPreparedAudioStorage>(graph.koin.get<PreparedAudioStorage>())
        }
    }

    private class FileContext : ContextWrapper(null) {
        lateinit var root: File
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
