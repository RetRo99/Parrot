package com.retro99.opds.implementation.opds1

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.implementation.ParserFactory
import com.retro99.opds.implementation.fixtures.Fixtures
import com.retro99.opds.implementation.fixtures.readFixtureText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * OPDS 1.x parser tests (plan §7 Phase 1 test-first order 3): fixture in,
 * expected normalized model out. One fixture per commit batch; the reader
 * conventions come from docs/opds-phase0-spikes.md §2.
 */
class Opds1ParserTest {

    private fun parser(): OpdsParser = ParserFactory.opdsParser()

    private fun parseFixture(
        fixture: String,
        mediaType: String = "application/atom+xml;profile=opds-catalog;kind=navigation",
        effectiveResponseUrl: String = "https://catalogue.example.org",
    ): OpdsParseResult = parser().parse(
        OpdsPayload(mediaType, readFixtureText(fixture).encodeToByteArray()),
        effectiveResponseUrl,
    )

    private fun parseFeed(
        fixture: String,
        mediaType: String = "application/atom+xml;profile=opds-catalog;kind=navigation",
        effectiveResponseUrl: String = "https://catalogue.example.org",
    ): com.retro99.opds.api.model.OpdsFeedDocument = when (val outcome = parseFixture(fixture, mediaType, effectiveResponseUrl)) {
        is OpdsParseResult.Document -> outcome.document as com.retro99.opds.api.model.OpdsFeedDocument
        is OpdsParseResult.Rejected -> fail("unexpected rejection of $fixture: $outcome")
    }

    // ---- listing.xml: navigation feed -----------------------------------------

    @Test
    fun navigation_feed_metadata_and_links() {
        val document = parseFeed(Fixtures.OPDS1_LISTING)

        assertEquals("Example Catalogue — Listing", document.metadata.title)
        assertNotNull(document.metadata.identifier)
        assertEquals("urn:uuid:11111111-2222-3333-4444-555555555555", document.metadata.identifier!!.raw)
        assertEquals(com.retro99.opds.api.model.OpdsIdentity.Kind.NOMINAL, document.metadata.identifier!!.kind)
        assertEquals("2026-10-08T00:00:00Z", document.metadata.updated)
        assertEquals("Example Catalogue", document.metadata.authors.single().name)

        // Feed self is identity metadata, resolved against the response URL.
        assertEquals("https://catalogue.example.org", document.effectiveResponseUrl)
        val selfLink = assertNotNull(
            document.self,
            "self link missing; title=${document.metadata.title} id=${document.metadata.identifier} " +
                "up=${document.up.map { it.relations }} pagination=${document.pagination}",
        ).also { assertEquals(listOf("self"), it.relations) }
        assertEquals("/listing", selfLink.rawHref)
        assertEquals("https://catalogue.example.org/listing", selfLink.resolvedHref)

        val startLink = document.up.firstOrNull { "start" in it.relations } ?: fail("no start link; up=${document.up.map { it.rawHref }}")
        assertEquals("https://catalogue.example.org/", startLink.resolvedHref)
    }

    @Test
    fun navigation_feed_pagination_comes_from_declared_links_only() {
        val document = parseFeed(Fixtures.OPDS1_LISTING)
        // Declared: start, next and search exist; first/prev/last are NOT declared.
        val next = document.pagination.next!!
        assertEquals("/listing?offset=25", next.rawHref)
        assertEquals("https://catalogue.example.org/listing?offset=25", next.resolvedHref)
        assertEquals(null, document.pagination.first)
        assertEquals(null, document.pagination.previous)
        assertEquals(null, document.pagination.last)
        // Media type parameters parsed, not string-compared (plan §4).
        assertEquals("opds-catalog", next.mediaType!!.parameter("profile"))
        assertEquals("navigation", next.mediaType!!.parameter("kind"))
        assertEquals("atom+xml", next.mediaType!!.subType)
    }

    @Test
    fun navigation_feed_search_offer_is_the_descriptor_link() {
        val document = parseFeed(Fixtures.OPDS1_LISTING)
        val search = assertNotNull(document.search)
        assertEquals(com.retro99.opds.api.model.OpdsSearchOffer.Kind.OPEN_SEARCH_DESCRIPTOR, search.kind)
        assertEquals("application/opensearchdescription+xml", search.link.mediaType!!.mediaRange)
        assertEquals("/osd.xml", search.link.rawHref)
        assertEquals("https://catalogue.example.org/osd.xml", search.link.resolvedHref)
    }

    @Test
    fun navigation_feed_entries_with_relative_hrefs_and_inherited_xml_base() {
        val document = parseFeed(Fixtures.OPDS1_LISTING)
        // All four listing entries are navigation: their subsection targets
        // link onward, none acquires in place.
        assertEquals(4, document.navigation.size)
        assertEquals(0, document.publications.size)
        assertTrue(document.navigation.all { it.acquisitionLinkCount == 0 })

        val first = document.navigation[0]
        assertEquals("urn:uuid:22222222-3333-4444-5555-666666666666", first.identity.raw)
        assertEquals("A Synthesized Book of Verses", first.title)
        assertEquals("2026-10-05T00:00:00Z", first.updated)
        val subsection = first.links.single { "subsection" in it.relations }
        assertEquals("/works/verses", subsection.rawHref)
        assertEquals("https://catalogue.example.org/works/verses", subsection.resolvedHref)
        assertEquals("acquisition", subsection.mediaType!!.parameter("kind"))
        assertEquals("opds-catalog", subsection.mediaType!!.parameter("profile"))

        // The entry that declares xml:base="/works/" composes its relative link
        // against the entry-level base, not the feed base.
        val inherited = document.navigation.first { it.title == "A Synthesized Treatise" }
        val inheritedLink = inherited.links.single { "subsection" in it.relations }
        assertEquals("treatise", inheritedLink.rawHref)
        assertEquals("https://catalogue.example.org/works/treatise", inheritedLink.resolvedHref)
    }

    @Test
    fun navigation_feed_data_uri_thumbnail_roundtrip() {
        val document = parseFeed(Fixtures.OPDS1_LISTING)
        // Inline data: thumbnail round-trips unmodified (Phase 0 record) and
        // its declared media type is parsed; data URIs are never "resolved".
        val rawImage = assertNotNull(
            document.navigation.flatMap { it.images }.singleOrNull { it.href.startsWith("data:") },
        )
        assertTrue(rawImage.href.startsWith("data:image/png;base64,iVBOR"), rawImage.href.take(40))
        assertEquals("image", rawImage.mediaType!!.mainType)
    }
}
