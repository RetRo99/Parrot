package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.books.ui.model.toUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookDetailDeviceNameTest {
    @Test
    fun `device name is carried to presentation without changing conflict`() {
        for (remote in listOf(.81, .94)) {
            val domain = BookProgressInfoDomainModel(
                bookUuid = "book", localProgression = .88, remoteProgression = remote,
                isEbookCached = true, isAudiobookCached = false, isReadaloudCached = false,
                remoteDeviceName = "Pixel Tablet",
            )
            val ui = domain.toUiModel()
            assertEquals("Pixel Tablet", ui.remoteDeviceName)
            assertEquals(.88, ui.displayProgression)
            assertEquals(remote, ui.remoteProgression)
            assertTrue(ui.hasConflict)
        }
    }
}
