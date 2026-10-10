package com.retro99.reader.ui.reader

import com.retro99.translations.StringRes
import kotlinx.coroutines.test.runTest
import resources.translations.reader_tts_prepared_chapter_estimate
import resources.translations.reader_tts_prepared_chapter_hint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The estimate before the press: where the count comes from and what the row does with it. */
class PreparedChapterTextTest {

    private fun row(chapterHref: String?, text: PreparedChapterText?) = preparedChapterRowUi(
        PreparedChapterRowState.NotPrepared,
        estimate = preparedChapterRowEstimate(chapterHref, text, PreparedVoiceKind.SUPERTONIC),
    )

    @Test fun `the not prepared row shows the estimate once the count is known`() {
        val ui = row("c1.xhtml", PreparedChapterText("c1.xhtml", sentences = 369, characters = 30_000))
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate, ui.status)
        assertEquals(listOf(15, "5 MB"), ui.statusArgs)
        assertEquals(listOf(PreparedChapterAction.PREPARE), ui.actions)
    }

    @Test fun `a chapter change shows the new chapter's estimate and never the old one`() {
        val old = PreparedChapterText("c1.xhtml", sentences = 369, characters = 30_000)
        val stale = row("c2.xhtml", old)
        assertEquals(StringRes.reader_tts_prepared_chapter_hint, stale.status)
        assertEquals(emptyList(), stale.statusArgs)

        val fresh = row("c2.xhtml", PreparedChapterText("c2.xhtml", sentences = 100, characters = 8_000))
        assertEquals(StringRes.reader_tts_prepared_chapter_estimate, fresh.status)
        assertEquals(listOf(4, "1 MB"), fresh.statusArgs)
    }

    @Test fun `a chapter with no readable sentences shows no estimate and no number`() {
        val ui = row("c1.xhtml", PreparedChapterText("c1.xhtml", sentences = 0, characters = 0))
        assertEquals(StringRes.reader_tts_prepared_chapter_hint, ui.status)
        assertEquals(emptyList(), ui.statusArgs)
    }

    @Test fun `while the count is still being read the row shows the plain line and never zero`() {
        val ui = row("c1.xhtml", text = null)
        assertEquals(StringRes.reader_tts_prepared_chapter_hint, ui.status)
        assertFalse(ui.statusArgs.any { it == 0 || it.toString().startsWith("0") })
    }

    @Test fun `no chapter on screen means no estimate`() {
        assertNull(
            preparedChapterRowEstimate(
                chapterHref = null,
                text = PreparedChapterText("c1.xhtml", 10, 500),
                voiceKind = PreparedVoiceKind.SYSTEM,
            ),
        )
    }

    @Test fun `sentences read aloud has loaded are counted without reading the page again`() = runTest {
        val loaded = listOf("One.", "Two two.", "Three.")
        var pageReads = 0
        val text = readPreparedChapterText(
            chapterHref = "c1.xhtml",
            loaded = { loaded },
            currentHref = { "c1.xhtml" },
            readPage = { pageReads++; emptyList() },
        )
        assertEquals(PreparedChapterText("c1.xhtml", sentences = 3, characters = 18), text)
        assertEquals(0, pageReads)
        assertEquals(listOf("One.", "Two two.", "Three."), loaded)
    }

    @Test fun `with nothing loaded the page on screen is read once and only counted`() = runTest {
        var pageReads = 0
        val text = readPreparedChapterText(
            chapterHref = "c1.xhtml",
            loaded = { null },
            currentHref = { "c1.xhtml" },
            readPage = { pageReads++; listOf("One.", "Two.") },
        )
        assertEquals(PreparedChapterText("c1.xhtml", sentences = 2, characters = 8), text)
        assertEquals(1, pageReads)
    }

    @Test fun `a count read while the reader moved to another chapter is thrown away`() = runTest {
        var current = "c1.xhtml"
        val text = readPreparedChapterText(
            chapterHref = "c1.xhtml",
            loaded = { null },
            currentHref = { current },
            readPage = { current = "c2.xhtml"; listOf("Belongs to the next chapter.") },
        )
        assertNull(text)
    }

    @Test fun `another chapter's page is never read for this chapter`() = runTest {
        var pageReads = 0
        val text = readPreparedChapterText(
            chapterHref = "c1.xhtml",
            loaded = { null },
            currentHref = { "c2.xhtml" },
            readPage = { pageReads++; listOf("Elsewhere.") },
        )
        assertNull(text)
        assertEquals(0, pageReads)
    }

    @Test fun `a page that is not ready yet is read again rather than called empty`() = runTest {
        var pageReads = 0
        val text = readPreparedChapterText(
            chapterHref = "c1.xhtml",
            loaded = { null },
            currentHref = { "c1.xhtml" },
            readPage = { if (pageReads++ == 0) emptyList() else listOf("Now it is here.") },
        )
        assertEquals(PreparedChapterText("c1.xhtml", sentences = 1, characters = 15), text)
        assertEquals(2, pageReads)
    }

    @Test fun `a chapter that stays empty is counted as nothing to prepare`() = runTest {
        var pageReads = 0
        val text = readPreparedChapterText(
            chapterHref = "cover.xhtml",
            loaded = { null },
            currentHref = { "cover.xhtml" },
            readPage = { pageReads++; emptyList() },
        )
        assertEquals(PreparedChapterText("cover.xhtml", sentences = 0, characters = 0), text)
        assertEquals(3, pageReads)
    }
}
