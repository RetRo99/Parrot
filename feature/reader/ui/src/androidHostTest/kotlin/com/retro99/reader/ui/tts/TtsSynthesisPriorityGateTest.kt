package com.retro99.reader.ui.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSynthesisPriorityGateTest {
    @Test fun `live overtakes queued preparation as soon as the in flight sentence ends`() = runTest {
        val gate = TtsSynthesisPriorityGate()
        val finish = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { gate.run(true) { order += "in flight"; finish.await(); order += "finished" } }
        runCurrent()
        launch { gate.run(true) { order += "queued preparation" } }
        launch { gate.run(false) { order += "live" } }
        runCurrent()
        assertEquals(listOf("in flight"), order)
        finish.complete(Unit); runCurrent()
        assertEquals(listOf("in flight", "finished", "live", "queued preparation"), order)
    }

    @Test fun `many queued preparation sentences never starve a live request`() = runTest {
        val gate = TtsSynthesisPriorityGate()
        val finish = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { gate.run(true) { finish.await() } }; runCurrent()
        repeat(20) { launch { gate.run(true) { order += "preparation $it" } } }
        launch { gate.run(false) { order += "live" } }; runCurrent()
        finish.complete(Unit); runCurrent()
        assertEquals("live", order.first())
        assertEquals(21, order.size)
    }

    @Test fun `without live requests preparation runs continuously in queue order`() = runTest {
        val gate = TtsSynthesisPriorityGate()
        val order = mutableListOf<Int>()
        repeat(3) { launch { gate.run(true) { order += it } } }
        runCurrent()
        assertEquals(listOf(0, 1, 2), order)
    }

    @Test fun `cancelled queued and in flight requests do not leak the sole permit`() = runTest {
        val gate = TtsSynthesisPriorityGate()
        val finish = CompletableDeferred<Unit>()
        val running = launch { gate.run(true) { finish.await() } }; runCurrent()
        val queued = launch { gate.run(false) {} }; runCurrent()
        queued.cancel(); running.cancel(); runCurrent()
        var served = 0
        gate.run(false) { served++ }
        gate.run(true) { served++ }
        assertEquals(2, served)
    }
}
