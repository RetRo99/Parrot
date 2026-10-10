package com.retro99.reader.ui.di

import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TTS-F10: `getOrCreateScope<ReaderScope>(bookUuid)` is keyed by book, so two reader
 * screens for one book share one scope and one set of controllers. Whoever is cleared
 * first must not take them away from the other.
 */
class ReaderScopeLeaseTest {

    /** A minimal reader scope: one scoped controller, nothing of the real graph. */
    private val koin: Koin = koinApplication {
        modules(
            module {
                scope<ReaderScope> {
                    scoped { FakeController() }
                }
            },
        )
    }.koin

    @AfterTest
    fun tearDown() {
        koin.close()
    }

    @Test
    fun two_readers_of_one_book_share_one_controller() {
        val lease = ReaderScopeLease()
        val first = lease.acquire("book-1") { openScope("book-1") }
        val second = lease.acquire("book-1") { openScope("book-1") }

        assertSame(first, second)
        assertSame(first.get<FakeController>(), second.get<FakeController>())
    }

    @Test
    fun the_first_reader_letting_go_leaves_the_second_its_controller() {
        val lease = ReaderScopeLease()
        val first = lease.acquire("book-1") { openScope("book-1") }
        val second = lease.acquire("book-1") { openScope("book-1") }
        val controller = first.get<FakeController>()
        lease.addCloseable("book-1", controller)

        lease.release("book-1")

        assertFalse(controller.isClosed, "The surviving reader's controller was closed under it")
        // The second holder can still use its controller, and the scope is still open.
        controller.use()
        assertSame(controller, second.get<FakeController>())
        assertEquals(1, controller.uses)
    }

    @Test
    fun the_last_reader_letting_go_closes_the_controller_exactly_once() {
        val lease = ReaderScopeLease()
        lease.acquire("book-1") { openScope("book-1") }
        val second = lease.acquire("book-1") { openScope("book-1") }
        val controller = second.get<FakeController>()
        lease.addCloseable("book-1", controller)

        lease.release("book-1")
        lease.release("book-1")

        assertTrue(controller.isClosed)
        assertEquals(1, controller.closeCount)
        assertTrue(second.closed, "The reader scope outlived its last holder")
    }

    @Test
    fun a_release_with_no_holder_left_closes_nothing_twice() {
        val lease = ReaderScopeLease()
        val scope = lease.acquire("book-1") { openScope("book-1") }
        val controller = scope.get<FakeController>()
        lease.addCloseable("book-1", controller)

        lease.release("book-1")
        lease.release("book-1")

        assertEquals(1, controller.closeCount)
    }

    @Test
    fun one_book_letting_go_leaves_another_book_alone() {
        val lease = ReaderScopeLease()
        val one = lease.acquire("book-1") { openScope("book-1") }.get<FakeController>()
        val two = lease.acquire("book-2") { openScope("book-2") }.get<FakeController>()
        lease.addCloseable("book-1", one)
        lease.addCloseable("book-2", two)

        lease.release("book-1")

        assertTrue(one.isClosed)
        assertFalse(two.isClosed)
    }

    private fun openScope(bookUuid: String) = koin.getOrCreateScope<ReaderScope>(bookUuid)

    private class FakeController : AutoCloseable {
        var closeCount: Int = 0
            private set
        var uses: Int = 0
            private set

        val isClosed: Boolean get() = closeCount > 0

        fun use() {
            check(!isClosed) { "Used after close" }
            uses++
        }

        override fun close() {
            closeCount++
        }
    }
}
