package com.retro99.reader.ui.navigator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class TtsChapterChangeTest {
    @Test
    fun `page arriving in the narrated chapter leaves sentences and playback alone`() {
        assertFalse(shouldReloadTtsChapter("one", "two", "two"))
    }

    @Test
    fun `user moving to a different chapter stops and reloads`() {
        assertTrue(shouldReloadTtsChapter("one", "two", "one"))
    }

    @Test
    fun `chapter change with nothing playing reloads sentences`() {
        assertTrue(shouldReloadTtsChapter("one", "two", null))
    }

    @Test
    fun `page moving away and back before a stop still recognises the narrated chapter`() {
        assertTrue(shouldReloadTtsChapter("two", "one", "two"))
        // The stop has not executed yet; arrival must compare with narration, not history.
        assertFalse(shouldReloadTtsChapter("one", "two", "two"))
    }

    @Test
    fun `chapter hand off arriving after narration starts does not stop it`() {
        assertFalse(shouldReloadTtsChapter("completed", "next", "next"))
    }

    @Test
    fun `skip ahead reloads before start and leaves the resulting narration alone`() = runTest {
        var page = "empty"
        var narrated: String? = null
        var reloads = 0
        var starts = 0
        val result = startAtFirstChapterWithText(
            maxChapterMoves = 2,
            hasSentencesHere = { page == "text" },
            startHere = { error("empty chapter started") },
            goToNextChapter = {
                if (shouldReloadTtsChapter(page, "text", narrated)) reloads++
                page = "text"
                true
            },
            startAtChapterStart = { narrated = page; starts++; null },
        )
        assertEquals(null, result)
        assertEquals(1, starts)
        assertEquals(1, reloads)
        assertFalse(shouldReloadTtsChapter("empty", page, narrated))
    }

    @Test
    fun `initial locator and same chapter never reload`() {
        assertFalse(shouldReloadTtsChapter(null, "one", null))
        assertFalse(shouldReloadTtsChapter("one", "one", null))
    }
}
