package com.retro99.server.implementation

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistStateMutationTest {

    @Test
    fun restoresPriorStateWhenPersistenceFails() {
        val initial = mapOf("existing" to 1)
        val updated = initial + ("new" to 2)
        var state = initial

        assertFailsWith<IllegalStateException> {
            persistStateMutation(
                previousValue = initial,
                updatedValue = updated,
                update = { state = it },
                persist = { error("disk unavailable") },
            )
        }

        assertEquals(initial, state)
    }

    @Test
    fun restoresPriorStateAndPreservesCancellation() {
        val initial = mapOf("existing" to 1)
        val updated = initial + ("new" to 2)
        val cancellation = CancellationException("cancelled")
        var state = initial

        val caught = assertFailsWith<CancellationException> {
            persistStateMutation(
                previousValue = initial,
                updatedValue = updated,
                update = { state = it },
                persist = { throw cancellation },
            )
        }

        assertEquals(initial, state)
        assertEquals(cancellation, caught)
    }
}
