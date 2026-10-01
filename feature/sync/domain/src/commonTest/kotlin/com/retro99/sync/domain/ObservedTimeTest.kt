package com.retro99.sync.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservedTimeTest {

    @Test
    fun `reads an iso instant`() {
        assertEquals(1_000L, ObservedTime.toEpochMillis("1970-01-01T00:00:01Z"))
    }

    @Test
    fun `reads epoch milliseconds`() {
        assertEquals(1_759_312_800_000L, ObservedTime.toEpochMillis("1759312800000"))
    }

    @Test
    fun `normalizes epoch milliseconds to an instant`() {
        assertEquals("1970-01-01T00:00:01Z", ObservedTime.normalize("1000"))
    }

    @Test
    fun `unreadable values give null`() {
        assertNull(ObservedTime.toEpochMillis("yesterday"))
        assertNull(ObservedTime.toEpochMillis(null))
        assertEquals("yesterday", ObservedTime.normalize("yesterday"))
    }
}
