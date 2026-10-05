package com.retro99.reader.ui.navigator

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A fill is blended onto the page, never painted behind it: a book may paint a background behind
 * its own text (body { background: white }), which covers anything painted underneath and used to
 * hide whole highlights in books that do.
 */
class SavedHighlightStyleTest {

    private val template = SavedHighlightStyle.template()

    @Test
    fun `a fill on a light page multiplies onto the text`() {
        val fill = SavedHighlightStyle.fillElement(tint = 0xFFFBE2A4.toInt(), darkPage = false)

        assertContains(fill, "background-color: rgba(251, 226, 164, 1.0) !important")
        assertContains(fill, "mix-blend-mode: multiply;")
    }

    @Test
    fun `a fill on a dark page screens onto the text`() {
        val fill = SavedHighlightStyle.fillElement(tint = 0x66C9962E, darkPage = true)

        assertContains(fill, "mix-blend-mode: screen;")
    }

    @Test
    fun `the fill keeps the tint's own alpha`() {
        val fill = SavedHighlightStyle.fillElement(tint = 0x66C9962E, darkPage = true)

        assertContains(fill, "background-color: rgba(201, 150, 46, 0.4)")
    }

    @Test
    fun `the fill is never painted behind the page`() {
        val stylesheet = template.stylesheet.orEmpty()

        assertContains(stylesheet, "z-index: 0;")
        assertFalse(stylesheet.contains("z-index: -1"), "a fill behind the page is buried by the book's own backgrounds")
    }
}
