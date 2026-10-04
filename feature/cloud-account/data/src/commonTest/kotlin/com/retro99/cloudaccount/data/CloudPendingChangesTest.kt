package com.retro99.cloudaccount.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudPendingChangesTest {
    @Test
    fun `only supported cloud entities count as unsynced changes`() {
        listOf(
            "reading_position",
            "reading_session",
            "library_book",
            "book_link",
            "book_link_decision",
            "reader_settings",
        ).forEach {
            assertTrue(isCloudMutation(it))
        }
        listOf("bookmark", "collection", "unknown").forEach {
            assertFalse(isCloudMutation(it))
        }
    }
}
