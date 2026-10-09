package com.retro99.parrot.di

import com.retro99.reader.ui.di.ReaderScope
import com.retro99.reader.ui.di.ReaderScopeLease
import com.retro99.reader.ui.reader.TtsPlaybackOperationReports
import org.koin.core.component.get
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The reader scope as the running app wires it: one scope per book, shared by every reader
 * screen open on that book, with the lease deciding when it closes (TTS-F10, QA-BUG-0100).
 */
class ReaderScopeWiringTest {

    @Test
    fun `one book has one reader scope and one record of what was reported`() =
        RealAppGraph().use { graph ->
            val first = graph.koin.getOrCreateScope<ReaderScope>("book-1")
            val second = graph.koin.getOrCreateScope<ReaderScope>("book-1")
            val other = graph.koin.getOrCreateScope<ReaderScope>("book-2")

            assertSame(first, second)
            assertSame(
                first.get<TtsPlaybackOperationReports>(),
                second.get<TtsPlaybackOperationReports>(),
            )
            assertNotEquals(
                first.get<TtsPlaybackOperationReports>(),
                other.get<TtsPlaybackOperationReports>(),
            )
        }

    @Test
    fun `the lease the readers share is one object from the graph`() =
        RealAppGraph().use { graph ->
            val lease = graph.koin.get<ReaderScopeLease>()
            assertSame(lease, graph.koin.get<ReaderScopeLease>())

            val held = lease.acquire("book-1") { graph.koin.getOrCreateScope<ReaderScope>("book-1") }
            assertSame(held, lease.acquire("book-1") { graph.koin.getOrCreateScope<ReaderScope>("book-1") })

            lease.release("book-1")
            assertTrue(!held.closed, "The first reader letting go closed the scope")
            lease.release("book-1")
            assertTrue(held.closed)
        }
}
