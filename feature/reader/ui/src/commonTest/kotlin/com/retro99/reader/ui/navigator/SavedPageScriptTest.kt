package com.retro99.reader.ui.navigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedPageScriptTest {
    @Test
    fun savedTapDecodesBothWebViewResultFormats() {
        assertEquals("highlight-id", SavedPageScript.parseSavedTap("\"highlight-id\""))
        assertEquals("highlight-id", SavedPageScript.parseSavedTap("\"\\\"highlight-id\\\"\""))
    }

    @Test
    fun emptyOrInvalidTapHasNoTarget() {
        for (raw in listOf(null, "null", "\"null\"", "\"\"", "undefined", "{}")) {
            assertNull(SavedPageScript.parseSavedTap(raw))
        }
    }
}
