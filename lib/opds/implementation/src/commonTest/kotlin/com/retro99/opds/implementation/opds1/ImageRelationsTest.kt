package com.retro99.opds.implementation.opds1

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsFeedDocument
import com.retro99.opds.implementation.ParserFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Found in hardening: only `http://opds-spec.org/image` was read as a picture, so an entry that
 * offers just a thumbnail (every row of a Gutenberg list, every Calibre entry) had no cover.
 */
class ImageRelationsTest {
    private fun images(vararg links: String): List<String> {
        val xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title><entry><id>urn:e:1</id><title>Book</title>" +
            "<link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b.epub\"/>" +
            links.joinToString("") + "</entry></feed>"
        val result = ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml;profile=opds-catalog;kind=acquisition", xml.encodeToByteArray()), "https://catalogue.example.org/feed")
        val feed = assertIs<OpdsFeedDocument>(assertIs<OpdsParseResult.Document>(result).document)
        return feed.publications.single().images.map { it.href }
    }

    private fun link(rel: String, href: String) = "<link rel=\"$rel\" type=\"image/jpeg\" href=\"$href\"/>"

    @Test fun every_standard_and_legacy_picture_relation_is_read() {
        for (rel in listOf(
            "http://opds-spec.org/image",
            "http://opds-spec.org/image/thumbnail",
            "http://opds-spec.org/cover",
            "http://opds-spec.org/thumbnail",
            "x-stanza-cover-image",
            "x-stanza-cover-image-thumbnail",
        )) {
            assertEquals(listOf("$SITE/p.jpg"), images(link(rel, "/p.jpg")), rel)
        }
    }

    @Test fun a_cover_comes_before_a_thumbnail_whatever_order_the_entry_lists_them_in() {
        val thumbnail = link("http://opds-spec.org/image/thumbnail", "/small.jpg")
        val cover = link("http://opds-spec.org/image", "/large.jpg")

        assertEquals(listOf("$SITE/large.jpg", "$SITE/small.jpg"), images(thumbnail, cover))
        assertEquals(listOf("$SITE/large.jpg", "$SITE/small.jpg"), images(cover, thumbnail))
    }

    @Test fun a_picture_relation_among_several_on_one_link_is_read() {
        assertEquals(listOf("$SITE/p.jpg"), images(link("related http://opds-spec.org/image/thumbnail", "/p.jpg")))
    }

    /**
     * Found by the signed-in end-to-end test: the address was kept as written, so a cover linked
     * as "/covers/1.png" or "cover.jpg" (Calibre and most self-hosted catalogues) could not be asked for.
     */
    @Test fun a_picture_address_is_resolved_against_the_page_and_an_inline_picture_is_kept_as_sent() {
        val inline = "data:image/png;base64,iVBORw0KGgo="

        assertEquals(
            listOf("$SITE/covers/1.png", "$SITE/cover.jpg", "https://cdn.example.net/c.jpg", inline),
            images(
                link("http://opds-spec.org/image", "/covers/1.png"),
                link("http://opds-spec.org/image", "cover.jpg"),
                link("http://opds-spec.org/image", "https://cdn.example.net/c.jpg"),
                link("http://opds-spec.org/image", inline),
            ),
        )
    }

    @Test fun links_that_are_not_pictures_are_not_pictures() {
        assertEquals(emptyList(), images(link("related", "/p.jpg"), link("http://opds-spec.org/acquisition/sample", "/s.jpg"), link("alternate", "/a.jpg")))
    }

    private companion object {
        const val SITE = "https://catalogue.example.org"
    }
}
