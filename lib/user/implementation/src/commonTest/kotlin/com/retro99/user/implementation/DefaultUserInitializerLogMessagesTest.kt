package com.retro99.user.implementation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultUserInitializerLogMessagesTest {
    @Test
    fun startupLogMessagesAreStaticAndDoNotInterpolateProfileValues() {
        val messages = listOf(
            DefaultUserInitializerLogMessages.NO_PROFILES_FOUND,
            DefaultUserInitializerLogMessages.DEFAULT_PROFILE_CREATED,
            DefaultUserInitializerLogMessages.NO_ACTIVE_PROFILE,
            DefaultUserInitializerLogMessages.EXISTING_PROFILE_ACTIVATED,
            DefaultUserInitializerLogMessages.INCONSISTENT_PROFILE_STATE,
            DefaultUserInitializerLogMessages.ACTIVE_PROFILE_ALREADY_SET,
        )

        assertTrue(messages.isNotEmpty())
        assertTrue(messages.all { it.isNotBlank() })
        assertFalse(messages.any { '$' in it })
        assertFalse(messages.any { PROFILE_ID_PATTERN.containsMatchIn(it) })
    }

    private companion object {
        val PROFILE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
