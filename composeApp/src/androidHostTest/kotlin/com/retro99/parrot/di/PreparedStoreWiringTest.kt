package com.retro99.parrot.di

import android.content.Context
import android.content.ContextWrapper
import com.retro99.reader.ui.tts.TtsPreparedAudioStore
import org.koin.dsl.module
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertSame

class PreparedStoreWiringTest {
    @Test
    fun `prepared store wrapper resolves as an app single from the real graph`() = RealAppGraph().use { graph ->
        // Host android.jar methods throw. Supply only the platform boundary; no store override.
        // The wrapper resolves Context but does not read filesDir until its lazy store is used.
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val context = (field.get(null) as Unsafe).allocateInstance(ContextWrapper::class.java) as Context
        graph.koin.loadModules(listOf(module { single<Context> { context } }))
        val store = graph.koin.get<TtsPreparedAudioStore>()
        assertSame(store, graph.koin.get<TtsPreparedAudioStore>())
    }
}
