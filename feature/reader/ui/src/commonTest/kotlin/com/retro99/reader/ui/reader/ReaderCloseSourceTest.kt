package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderCloseSourceTest {
    @Test
    fun closeButtonAndSystemBackKeepDistinctBoundedEntryPoints() {
        assertEquals("close_button", ReaderCloseSource.CloseButton.entryPoint)
        assertEquals("system_back", ReaderCloseSource.SystemBack.entryPoint)
    }
}
