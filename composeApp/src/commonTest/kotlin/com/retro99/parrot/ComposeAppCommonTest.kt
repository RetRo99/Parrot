package com.retro99.parrot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.retro99.parrot.navigation.HomeExposureGate

class ComposeAppCommonTest {

    @Test
    fun example() {
        assertEquals(3, 1 + 2)
    }

    @Test
    fun homeExposureGateRejectsStaleAndDuplicateVisibilityCallbacks() {
        val gate = HomeExposureGate()

        assertFalse(gate.shouldReport(entryId = 1, visibleEntryId = 2))
        assertTrue(gate.shouldReport(entryId = 1, visibleEntryId = 1))
        assertFalse(gate.shouldReport(entryId = 1, visibleEntryId = 1))
        assertTrue(gate.shouldReport(entryId = 2, visibleEntryId = 2))
    }
}
