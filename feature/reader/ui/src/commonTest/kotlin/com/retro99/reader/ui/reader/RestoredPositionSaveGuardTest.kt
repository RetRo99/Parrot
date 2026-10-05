package com.retro99.reader.ui.reader

import com.retro99.reader.ui.model.PositionUiModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoredPositionSaveGuardTest {
    private val position = PositionUiModel(
        createdAt = null, href = "chapter.xhtml", type = "application/xhtml+xml", title = "Chapter",
        progression = 0.2, position = 1, totalProgression = 0.4, chapterIndex = 0, totalChapters = 10,
    )
    private val guard = RestoredPositionSaveGuard()

    @Test fun `ordinary reading saves when no restoration is pending`() {
        assertTrue(guard.shouldSave(position, null))
    }
    @Test fun `restored locator and repeat callbacks do not become new reading`() {
        guard.restored(position)
        repeat(3) { assertFalse(guard.shouldSave(position, null)) }
    }
    @Test fun `closing at the selected position does not repost remote progress`() {
        guard.restored(position)
        assertFalse(guard.shouldSave(position, null))
    }
    @Test fun `metadata only changes do not count as new reading`() {
        guard.restored(position)
        assertFalse(guard.shouldSave(position.copy(title = "Other title", totalChapters = 11), null))
    }
    @Test fun `a page turn resumes normal saves even when returning to the restored page`() {
        guard.restored(position)
        assertTrue(guard.shouldSave(position.copy(progression = 0.3), null))
        assertTrue(guard.shouldSave(position, null))
    }
    @Test fun `different chapter at the same percentage is new reading`() {
        guard.restored(position)
        assertTrue(guard.shouldSave(position.copy(href = "other.xhtml"), null))
    }
    @Test fun `different selector at the same percentage is new reading`() {
        guard.restored(position.copy(cssSelector = "#one"))
        assertTrue(guard.shouldSave(position.copy(cssSelector = "#two"), null))
    }
    @Test fun `restored audio is not re-saved until playback or seeking advances`() {
        guard.restored(position.copy(audioTimestampMs = 100L))
        assertFalse(guard.shouldSave(position, 100L))
        assertTrue(guard.shouldSave(position, 101L))
    }
    @Test fun `zero audio is equivalent to an absent audio checkpoint`() {
        guard.restored(position)
        assertFalse(guard.shouldSave(position, 0L))
    }
    @Test fun `restoring text without observing narration does not erase its audio checkpoint`() {
        guard.restored(position.copy(audioTimestampMs = 100L))
        assertFalse(guard.shouldSave(position, null))
    }
    @Test fun `each new conflict choice resets the restoration guard`() {
        guard.restored(position)
        val chosen = position.copy(progression = 0.8)
        assertTrue(guard.shouldSave(chosen, null))
        guard.restored(chosen)
        assertFalse(guard.shouldSave(chosen, null))
    }
}
