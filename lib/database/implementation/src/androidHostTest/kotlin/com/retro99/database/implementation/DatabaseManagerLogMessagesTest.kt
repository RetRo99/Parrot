package com.retro99.database.implementation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseManagerLogMessagesTest {
    @Test
    fun profileLifecycleMessagesDoNotInterpolateProfileIds() {
        val messages = listOf(
            DatabaseManagerLogMessages.PROFILE_CHANGED,
            DatabaseManagerLogMessages.DATABASE_OPENED,
        )

        assertTrue(messages.all { it.isNotBlank() })
        assertFalse(messages.any { '$' in it })
        assertFalse(messages.any { PROFILE_ID_PATTERN.containsMatchIn(it) })
    }

    private companion object {
        val PROFILE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
