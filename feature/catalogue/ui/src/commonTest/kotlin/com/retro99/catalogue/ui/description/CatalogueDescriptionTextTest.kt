package com.retro99.catalogue.ui.description

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.retro99.catalogue.domain.CatalogueDescriptionFormat
import com.retro99.catalogue.domain.CatalogueRichText
import com.retro99.catalogue.domain.CatalogueRichText.Marker
import com.retro99.catalogue.domain.sanitizeCatalogueDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogueDescriptionTextTest {
    @Test fun spans_become_one_text_with_bold_and_italic_ranges() {
        val paragraph = sanitizeCatalogueDescription("Plain <b>bold</b> <i>slanted</i><br><b><i>both</i></b>", CatalogueDescriptionFormat.Html)
            .blocks.single() as CatalogueRichText.Paragraph

        val text = paragraph.spans.toAnnotatedString()

        assertEquals("Plain bold slanted\nboth", text.text)
        val styles = text.spanStyles.map { Triple(text.text.substring(it.start, it.end), it.item.fontWeight, it.item.fontStyle) }
        assertEquals(
            listOf(
                Triple("bold", FontWeight.Bold, null),
                // The line break belongs to the run it ends; it draws nothing.
                Triple("slanted\n", null, FontStyle.Italic),
                Triple("both", FontWeight.Bold, FontStyle.Italic),
            ),
            styles,
        )
    }

    @Test fun a_link_leaves_no_annotation_behind() {
        val paragraph = sanitizeCatalogueDescription("""See <a href="https://example.com">the page</a>.""", CatalogueDescriptionFormat.Html)
            .blocks.single() as CatalogueRichText.Paragraph

        val text = paragraph.spans.toAnnotatedString()

        assertEquals("See the page.", text.text)
        assertTrue(text.spanStyles.isEmpty())
        assertTrue(text.getStringAnnotations(0, text.length).isEmpty())
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test fun markers() {
        assertEquals("•", Marker.Bullet.label())
        assertEquals("12.", Marker.Number(12).label())
    }
}
