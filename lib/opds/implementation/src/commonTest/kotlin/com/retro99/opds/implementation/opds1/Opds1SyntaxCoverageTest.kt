package com.retro99.opds.implementation.opds1

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsFeedDocument
import com.retro99.opds.implementation.ParserFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Lines of the test plan (§8, shared protocol tests) that had no test of their own. */
class Opds1SyntaxCoverageTest {
    private val atom = "application/atom+xml;profile=opds-catalog;kind=acquisition"

    private fun parse(body: String, type: String? = atom) =
        ParserFactory.opdsParser().parse(OpdsPayload(type, body.encodeToByteArray()), "https://catalogue.example.org/opds/feed")
    private fun feed(body: String) = assertIs<OpdsFeedDocument>(assertIs<OpdsParseResult.Document>(parse(body), "not read").document)

    private val plain = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
        <id>urn:feed</id><title>Shelf</title>
        <link rel="next" href="?page=2" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
        <entry><id>urn:book:1</id><title>Book</title><author><name>Ann Author</name></author><dc:language>en</dc:language>
        <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="b/1.epub"/></entry></feed>"""

    @Test fun a_feed_that_writes_atom_with_a_prefix_reads_the_same_as_one_that_uses_the_default_namespace() {
        val prefixed = """<a:feed xmlns:a="http://www.w3.org/2005/Atom" xmlns:terms="http://purl.org/dc/terms/">
        <a:id>urn:feed</a:id><a:title>Shelf</a:title>
        <a:link rel="next" href="?page=2" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
        <a:entry><a:id>urn:book:1</a:id><a:title>Book</a:title><a:author><a:name>Ann Author</a:name></a:author><terms:language>en</terms:language>
        <a:link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="b/1.epub"/></a:entry></a:feed>"""

        val expected = feed(plain)
        val actual = feed(prefixed)

        assertEquals(expected.metadata.title, actual.metadata.title)
        assertEquals(expected.pagination.next?.resolvedHref, actual.pagination.next?.resolvedHref)
        assertEquals("https://catalogue.example.org/opds/feed?page=2", actual.pagination.next?.resolvedHref)
        val (one, other) = expected.publications.single() to actual.publications.single()
        assertEquals(one.title, other.title)
        assertEquals(one.identity, other.identity)
        assertEquals(one.authors.map { it.name }, other.authors.map { it.name })
        assertEquals(one.languages, other.languages)
        assertEquals(listOf("en"), other.languages)
        assertEquals("https://catalogue.example.org/opds/b/1.epub", other.acquisitionLinks.single().resolvedHref)
    }

    /**
     * The reader matches elements by their name alone, so feeds that leave the Atom namespace
     * out, or get it wrong, still read. The other side of that: an element with an Atom name in
     * a foreign namespace is read as Atom too. This pins that behaviour; it is listed as a
     * known limitation in docs/opds-compatibility.md.
     */
    @Test fun elements_are_matched_by_name_whatever_namespace_they_are_in() {
        val noNamespace = """<feed><id>urn:feed</id><title>Shelf</title>
        <entry><id>urn:book:1</id><title>Book</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="b/1.epub"/></entry></feed>"""
        val foreign = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="urn:not-atom"><id>urn:feed</id><title>Shelf</title>
        <x:entry><x:id>urn:book:1</x:id><x:title>Book</x:title><x:link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="b/1.epub"/></x:entry></feed>"""

        for (body in listOf(noNamespace, foreign)) {
            assertEquals("https://catalogue.example.org/opds/b/1.epub", feed(body).publications.single().acquisitionLinks.single().resolvedHref)
        }
    }

    @Test fun named_and_numbered_character_references_are_decoded_in_text_and_in_addresses() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:feed</id><title>Tom &amp; Jerry &lt;3 &#233;t&#xE9; &quot;x&quot;</title>
        <entry><id>urn:book:1</id><title>A &gt; B</title>
        <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="get?id=1&amp;format=epub"/></entry></feed>"""

        val parsed = feed(body)

        assertEquals("Tom & Jerry <3 été \"x\"", parsed.metadata.title.select(emptyList()))
        assertEquals("A > B", parsed.publications.single().title.select(emptyList()))
        assertEquals("https://catalogue.example.org/opds/get?id=1&format=epub", parsed.publications.single().acquisitionLinks.single().resolvedHref)
    }

    /**
     * OPDS 1 facets (`rel="http://opds-spec.org/facet"` with `opds:facetGroup`) are not turned
     * into filters in this release; OPDS 2 facets are. This pins what happens instead: the page
     * reads, its books are there, and nothing is offered as a filter.
     */
    @Test fun opds_1_facet_links_do_not_become_filters_and_do_not_disturb_the_page() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:thr="http://purl.org/syndication/thread/1.0">
        <id>urn:feed</id><title>Shelf</title>
        <link rel="http://opds-spec.org/facet" href="?sort=title" title="Title" opds:facetGroup="Sort" opds:activeFacet="true" thr:count="12"/>
        <link rel="http://opds-spec.org/facet" href="?sort=new" title="Newest" opds:facetGroup="Sort"/>
        <entry><id>urn:book:1</id><title>Book</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="b/1.epub"/></entry></feed>"""

        val parsed = feed(body)

        assertEquals(1, parsed.publications.size)
        assertTrue(parsed.facets.isEmpty())
        assertTrue(parsed.groups.isEmpty())
    }

    @Test fun an_opds_authentication_document_is_not_a_catalogue_page() {
        val document = """{"id":"https://catalogue.example.org/auth","title":"Library","authentication":[{"type":"http://opds-spec.org/auth/basic","labels":{"login":"Card","password":"PIN"}}],"links":[{"rel":"start","href":"/opds"}]}"""

        for (type in listOf("application/opds-authentication+json", "application/vnd.opds.authentication.v1.0+json", "application/json", null)) {
            assertIs<OpdsParseResult.Rejected>(parse(document, type), "served as $type")
        }
    }
}
