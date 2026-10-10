package com.retro99.reader.ui.di

import org.koin.core.annotation.Single
import org.koin.core.scope.Scope

/**
 * Who is still using a book's reader scope.
 *
 * `getOrCreateScope<ReaderScope>(bookUuid)` is keyed by the book, so two reader screens for
 * one book share one scope and therefore one `BookController`, `AudioController` and
 * `TtsController` (TTS-F10). Registering those with the ViewModel's own `addCloseable` took
 * them away from the survivor as soon as either screen was cleared. This counts holders
 * instead: the scope and everything registered with it close once, when the last holder
 * lets go.
 *
 * Reader screens live on the main thread, so the bookkeeping is not synchronised.
 */
@Single
class ReaderScopeLease {

    private class Hold(val scope: Scope) {
        var holders: Int = 0
        val closeables = mutableListOf<AutoCloseable>()
    }

    private val holds = mutableMapOf<String, Hold>()

    /** Takes a hold on [bookUuid]'s scope, opening it with [openScope] for the first holder. */
    fun acquire(bookUuid: String, openScope: () -> Scope): Scope {
        val hold = holds.getOrPut(bookUuid) { Hold(openScope()) }
        hold.holders++
        return hold.scope
    }

    /** Hands something the scope owns to the lease; closed once, with the scope. */
    fun addCloseable(bookUuid: String, closeable: AutoCloseable) {
        val hold = holds[bookUuid] ?: return
        if (hold.closeables.none { it === closeable }) hold.closeables += closeable
    }

    /** Gives a hold back. Nothing closes until the last one. */
    fun release(bookUuid: String) {
        val hold = holds[bookUuid] ?: return
        hold.holders--
        if (hold.holders > 0) return
        holds.remove(bookUuid)
        hold.closeables.forEach { it.close() }
        hold.scope.close()
    }
}
