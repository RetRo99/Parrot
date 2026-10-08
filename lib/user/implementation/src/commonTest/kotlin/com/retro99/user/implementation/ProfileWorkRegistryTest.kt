package com.retro99.user.implementation

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProfileWorkRegistryTest {
    @Test fun cancellation_fences_late_registration_and_reactivation_opens_a_fresh_context() = runTest {
        val work = ProfileWorkRegistryImpl()
        val cancelled = mutableListOf<String>()
        assertTrue(work.register("a", "source") { cancelled += "a" })
        assertTrue(work.register("b", "source") { cancelled += "b" })
        work.cancel("a")
        assertEquals(listOf("a"), cancelled)
        assertFalse(work.register("a", "late") { error("Must not register into a closed context") })
        work.activate("a")
        assertTrue(work.register("a", "source") { cancelled += "new-a" })
        work.cancel("a")
        assertEquals(listOf("a", "new-a"), cancelled)
        work.unregister("b", "source")
        work.cancel("b")
        assertEquals(listOf("a", "new-a"), cancelled)
    }
}
