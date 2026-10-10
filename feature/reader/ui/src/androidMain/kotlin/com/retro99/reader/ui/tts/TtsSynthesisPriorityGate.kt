package com.retro99.reader.ui.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class TtsSynthesisPriorityGate {
    private class Request(val signal: CompletableDeferred<Unit> = CompletableDeferred(), var granted: Boolean = false)
    private val mutex = Mutex()
    private var busy = false
    private val live = ArrayDeque<Request>()
    private val background = ArrayDeque<Request>()

    suspend fun <T> run(preparation: Boolean, block: suspend () -> T): T {
        val request = Request()
        mutex.withLock {
            if (!busy) {
                busy = true
                request.granted = true
                request.signal.complete(Unit)
            } else if (preparation) background.addLast(request) else live.addLast(request)
        }
        try {
            request.signal.await()
            return block()
        } finally {
            // Cancellation must remove a queued request or hand the granted permit on exactly once.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (request.granted) {
                        val next = if (live.isNotEmpty()) live.removeFirst() else background.removeFirstOrNull()
                        if (next == null) busy = false else {
                            next.granted = true
                            next.signal.complete(Unit)
                        }
                    } else {
                        live.remove(request)
                        background.remove(request)
                    }
                }
            }
        }
    }
}
