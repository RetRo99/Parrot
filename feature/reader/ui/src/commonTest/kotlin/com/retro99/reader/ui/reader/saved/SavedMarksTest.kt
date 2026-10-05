package com.retro99.reader.ui.reader.saved

import com.retro99.reader.ui.navigator.PageMarkOptions
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SavedMarksTest {

    private val lightPage = SavedMarkStyle(
        fills = HighlightColor.entries.associateWith { 0x110000 + it.ordinal },
        rules = HighlightColor.entries.associateWith { 0x220000 + it.ordinal },
        barColor = 0xFFA9561F.toInt(),
        marginLeftDp = 16,
        marginRightDp = 16,
    )

    @Test
    fun `a plain highlight is filled and carries no rule or bar`() {
        // When
        val mark = SavedMarks.marks(listOf(highlight("a")), lightPage).single()

        // Then: the fill shows the colour, and nothing else is drawn over the text.
        assertEquals(0x110000, mark.fill)
        assertEquals(0, mark.ruleCount)
        assertEquals(0, mark.barColor)
        assertTrue(mark.tappable)
    }

    @Test
    fun `a highlight with a note gets one rule in its tone and a bar at the edge`() {
        // When
        val mark = SavedMarks.marks(listOf(highlight("a", note = "remember")), lightPage).single()

        // Then
        assertEquals(1, mark.ruleCount)
        assertEquals(0x220000, mark.ruleColor)
        assertEquals(0xFFA9561F.toInt(), mark.barColor)
    }

    @Test
    fun `the rule takes the tone of the highlight's own colour`() {
        // When
        val marks = SavedMarks.marks(
            listOf(
                highlight("a", color = HighlightColor.Rose, note = "n"),
                highlight("b", color = HighlightColor.Sky, note = "n"),
            ),
            lightPage,
        )

        // Then
        assertEquals(listOf(0x220001, 0x220003), marks.map { mark -> mark.ruleColor })
    }

    @Test
    fun `on e-ink a plain highlight is one rule and a noted one is two`() {
        // Given: no fills and no colours, just black rules.
        val eink = SavedMarkStyle(
            fills = emptyMap(),
            rules = emptyMap(),
            eink = true,
            barColor = 0xFF000000.toInt(),
            marginLeftDp = 16,
            marginRightDp = 16,
        )

        // Then
        assertEquals(1, SavedMarks.marks(listOf(highlight("a")), eink).single().ruleCount)
        assertEquals(2, SavedMarks.marks(listOf(highlight("a", note = "n")), eink).single().ruleCount)
    }

    @Test
    fun `on e-ink the fill is transparent but the highlight is still tappable`() {
        // Given
        val eink = SavedMarkStyle(eink = true)

        // When
        val mark = SavedMarks.marks(listOf(highlight("a")), eink).single()

        // Then: the decoration is only there to carry the taps on the detail sheet.
        assertEquals(0, mark.fill)
        assertTrue(mark.tappable)
    }

    @Test
    fun `the fill is told whether it lands on a dark page`() {
        // Given: the same highlight on a light and on a dark page.
        val darkPage = lightPage.copy(darkPage = true)

        // Then: the fill blends by darkening onto the first and lightening onto the second.
        assertFalse(SavedMarks.marks(listOf(highlight("a")), lightPage).single().darkPage)
        assertTrue(SavedMarks.marks(listOf(highlight("a")), darkPage).single().darkPage)
    }

    @Test
    fun `a bookmark with a note is left to the ribbon and marked nowhere in the text`() {
        // When
        val mark = SavedMarks.marks(listOf(highlight("a", type = SavedItemType.Bookmark, note = "mine")), lightPage).single()

        // Then: it is still reported, so the reader knows the page is bookmarked.
        assertEquals("a", mark.id)
        assertEquals(0, mark.fill)
        assertEquals(0, mark.ruleCount)
        assertEquals(0, mark.barColor)
        assertFalse(mark.tappable)
    }

    @Test
    fun `a highlight without text is skipped`() {
        assertEquals(emptyList<com.retro99.reader.ui.navigator.PageMark>(), SavedMarks.marks(listOf(highlight("a", quote = null)), lightPage))
    }

    @Test
    fun `the marks keep their size and never follow the book font`() {
        // The page draws these in dp; changing them would let the marks grow with the text.
        val defaults = PageMarkOptions()
        assertEquals(4, defaults.edgeWidth)
        assertEquals(2, defaults.edgeRadius)
        assertEquals(4, defaults.edgeGap)
        assertEquals(3, defaults.ruleBelow)
        assertEquals(2, defaults.ruleWeight)
        assertEquals(1.5, defaults.pairWeight, 0.0001)
        assertEquals(2.0, defaults.pairGap, 0.0001)
    }

    @Test
    fun `the bar is dropped where the page margin has no room for it`() {
        assertEquals(8, PageMarkOptions().minMargin)
    }

    @Test
    fun `the options carry the page margins and whether it scrolls`() {
        // Given
        val style = lightPage.copy(marginLeftDp = 4, marginRightDp = 24, scroll = true)

        // Then
        assertEquals(PageMarkOptions(marginLeft = 4, marginRight = 24, scroll = true), style.options())
    }

    private fun highlight(
        id: String,
        type: SavedItemType = SavedItemType.Highlight,
        color: HighlightColor = HighlightColor.Amber,
        note: String? = null,
        quote: String? = "Out on the water",
    ): SavedItem {
        val now = Instant.parse("2026-10-04T12:00:00Z")
        return SavedItem(
            id = id,
            book = SavedBookRef(key = "library:1", uuid = "1", title = "The Crossing", author = "Mara"),
            type = type,
            location = SavedLocation(
                href = "chap8.xhtml",
                mediaType = "application/xhtml+xml",
                progression = 0.4,
                totalProgression = 0.4,
                position = 12,
                chapterTitle = "8. The Crossing",
            ),
            anchor = quote?.let { TextAnchor(before = "the planks. ", quote = it, after = " the first") },
            color = if (type == SavedItemType.Highlight) color else null,
            note = note,
            audio = null,
            snippetPending = false,
            createdAt = now,
            updatedAt = now,
            remoteRevision = null,
        )
    }
}
