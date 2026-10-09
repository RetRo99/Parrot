package com.retro99.opds.implementation.stress

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.api.model.OpdsFeedDocument
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.ParserFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Each feed budget, at the limit and one past it, for both versions of the format. */
class FeedLimitsStressTest {
    private val atom = "application/atom+xml;profile=opds-catalog;kind=acquisition"
    private val json = "application/opds+json"

    private fun parse(type: String, body: ByteArray) = ParserFactory.opdsParser().parse(OpdsPayload(type, body), "https://catalogue.example.org/feed")
    private fun parse(type: String, body: String) = parse(type, body.encodeToByteArray())
    private fun feed(result: OpdsParseResult) = assertIs<OpdsFeedDocument>(assertIs<OpdsParseResult.Document>(result, result.toString()).document)
    private inline fun <reified T : OpdsRejection> assertRejected(result: OpdsParseResult) {
        assertIs<T>(assertIs<OpdsParseResult.Rejected>(result, "expected a rejection").rejection)
    }

    private fun atomWithEntries(count: Int) = buildString {
        append("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>big</title>")
        repeat(count) { append("<entry><id>urn:e:$it</id><title>t$it</title><link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b/$it.epub\"/></entry>") }
        append("</feed>")
    }

    private fun jsonWithPublications(count: Int) = buildString {
        append("""{"metadata":{"title":"big"},"publications":[""")
        repeat(count) {
            if (it > 0) append(',')
            append("""{"metadata":{"title":"t$it","identifier":"urn:e:$it"},"links":[{"href":"/b/$it.epub","type":"application/epub+zip","rel":"http://opds-spec.org/acquisition"}]}""")
        }
        append("]}")
    }

    @Test fun an_atom_feed_at_the_item_limit_keeps_every_entry_and_one_more_entry_is_refused() {
        val limit = OpdsBudgets.MAX_ITEMS_PER_RESPONSE

        val full = feed(parse(atom, atomWithEntries(limit)))

        assertEquals(limit, full.publications.size)
        assertEquals("t${limit - 1}", full.publications.last().title.select(emptyList()))
        assertRejected<OpdsRejection.TooManyItems>(parse(atom, atomWithEntries(limit + 1)))
    }

    @Test fun a_json_feed_at_the_item_limit_keeps_every_publication_and_one_more_is_refused() {
        val limit = OpdsBudgets.MAX_ITEMS_PER_RESPONSE

        val full = feed(parse(json, jsonWithPublications(limit)))

        assertEquals(limit, full.publications.size)
        assertRejected<OpdsRejection.TooManyItems>(parse(json, jsonWithPublications(limit + 1)))
    }

    @Test fun a_feed_of_exactly_the_byte_limit_is_read_and_one_byte_more_is_refused_in_both_formats() {
        val limit = OpdsBudgets.MAX_RESPONSE_BYTES.toInt()
        fun atomOf(size: Int): ByteArray {
            val head = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>padded</title><!--"
            val tail = "--></feed>"
            return (head + "x".repeat(size - head.length - tail.length) + tail).encodeToByteArray()
        }
        fun jsonOf(size: Int): ByteArray {
            val head = """{"metadata":{"title":"padded"},"publications":[],"padding":""""
            val tail = "\"}"
            return (head + "x".repeat(size - head.length - tail.length) + tail).encodeToByteArray()
        }
        assertEquals(limit, atomOf(limit).size)
        assertEquals(limit + 1, jsonOf(limit + 1).size)

        assertEquals("padded", feed(parse(atom, atomOf(limit))).metadata.title.select(emptyList()))
        assertEquals("padded", feed(parse(json, jsonOf(limit))).metadata.title.select(emptyList()))
        assertRejected<OpdsRejection.TooLarge>(parse(atom, atomOf(limit + 1)))
        assertRejected<OpdsRejection.TooLarge>(parse(json, jsonOf(limit + 1)))
    }

    @Test fun atom_nesting_at_the_limit_is_read_and_one_level_deeper_is_refused() {
        // feed > entry > content > div > (wrappers) > p: four levels above the wrappers, one below.
        fun nested(totalDepth: Int): String {
            val wrappers = totalDepth - 5
            return "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>deep</title><entry><id>urn:e:1</id><title>t</title>" +
                "<link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b.epub\"/><content type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\">${"<div>".repeat(wrappers)}<p>deep</p>${"</div>".repeat(wrappers)}</div></content></entry></feed>"
        }

        assertEquals(1, feed(parse(atom, nested(OpdsBudgets.MAX_NESTING_DEPTH))).publications.size)
        assertRejected<OpdsRejection.TooDeep>(parse(atom, nested(OpdsBudgets.MAX_NESTING_DEPTH + 1)))
    }

    @Test fun atom_nesting_inside_an_element_the_reader_ignores_still_counts() {
        fun ignored(totalDepth: Int): String {
            val levels = totalDepth - 1
            return "<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:x=\"urn:x\"><title>deep</title>${"<x:a>".repeat(levels)}${"</x:a>".repeat(levels)}</feed>"
        }

        assertIs<OpdsParseResult.Document>(parse(atom, ignored(OpdsBudgets.MAX_NESTING_DEPTH)))
        assertRejected<OpdsRejection.TooDeep>(parse(atom, ignored(OpdsBudgets.MAX_NESTING_DEPTH + 1)))
        // Far past the limit: refused, not a stack overflow.
        assertRejected<OpdsRejection.TooDeep>(parse(atom, ignored(100_000)))
    }

    @Test fun json_nesting_at_the_limit_is_read_and_one_level_deeper_is_refused() {
        // The root object is the first level.
        fun nested(totalDepth: Int) = """{"metadata":{"title":"deep"},"publications":[],"unknown":${"[".repeat(totalDepth - 1)}0${"]".repeat(totalDepth - 1)}}"""

        assertIs<OpdsParseResult.Document>(parse(json, nested(OpdsBudgets.MAX_NESTING_DEPTH)))
        assertRejected<OpdsRejection.TooDeep>(parse(json, nested(OpdsBudgets.MAX_NESTING_DEPTH + 1)))
        assertRejected<OpdsRejection.TooDeep>(parse(json, nested(100_000)))
    }

    @Test fun a_feed_at_every_limit_at_once_is_read() {
        // The item limit, with the rest of the byte budget as padding.
        val entries = atomWithEntries(OpdsBudgets.MAX_ITEMS_PER_RESPONSE).removeSuffix("</feed>")
        val room = OpdsBudgets.MAX_RESPONSE_BYTES.toInt() - entries.length - "<!---->".length - "</feed>".length
        val body = entries + "<!--" + "x".repeat(room) + "-->" + "</feed>"
        assertEquals(OpdsBudgets.MAX_RESPONSE_BYTES.toInt(), body.encodeToByteArray().size)

        assertEquals(OpdsBudgets.MAX_ITEMS_PER_RESPONSE, feed(parse(atom, body)).publications.size)
    }

    @Test fun thousands_of_inline_thumbnails_stay_inside_the_page_and_are_not_copied_or_resolved() {
        // A thumbnail of about 2 KiB on each of 2,000 entries: a 4 MiB page.
        val image = "data:image/png;base64," + "iVBORw0KGgo".padEnd(2_000, 'A')
        val count = OpdsBudgets.MAX_ITEMS_PER_RESPONSE
        val body = buildString {
            append("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>pictures</title>")
            repeat(count) { append("<entry><id>urn:e:$it</id><title>t$it</title><link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b/$it.epub\"/><link rel=\"http://opds-spec.org/image/thumbnail\" type=\"image/png\" href=\"$image\"/></entry>") }
            append("</feed>")
        }
        assertTrue(body.length < OpdsBudgets.MAX_RESPONSE_BYTES)

        val parsed = feed(parse(atom, body))

        val images = parsed.publications.flatMap { it.images }
        assertEquals(count, images.size)
        assertTrue(images.all { it.href == image }, "an inline picture is kept as it was sent")
        // What is retained for pictures is what the page carried, never more.
        assertTrue(images.sumOf { it.href.length.toLong() } <= body.length)
    }
}
