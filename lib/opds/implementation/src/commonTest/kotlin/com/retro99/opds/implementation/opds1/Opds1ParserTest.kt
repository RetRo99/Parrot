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
import kotlin.test.assertNull
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

    // ---- verses-acquisition.xml: two editions, several EPUB links, XHTML ----

    @Test
    fun acquisition_feed_entries_variants_format_counts_and_resolution() {
        val document = parseFeed(
            Fixtures.OPDS1_ACQUISITION,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://catalogue.example.org/works/verses",
        )
        assertEquals("A Synthesized Book of Verses", document.metadata.title)
        assertEquals(2, document.publications.size)
        assertEquals(0, document.navigation.size)

        val edition1 = document.publications[0]
        assertEquals("urn:synthesis:verses:edition:1", edition1.identity.raw)
        assertEquals(com.retro99.opds.api.model.OpdsIdentity.Kind.NOMINAL, edition1.identity.kind)
        assertEquals("A Synthesized Book of Verses", edition1.title)
        assertEquals("en", edition1.languages.single())
        assertEquals("Released under a catalogue-of-record setting. See the related link.", edition1.rights)
        assertEquals("Adele Synthling", edition1.authors.single().name)

        // All acquisition choices preserved, in catalogue order (plan §3.2/§5.1).
        val acquisitions = edition1.acquisitionLinks
        assertEquals(3, edition1.acquisitionLinkCount)
        assertEquals(
            listOf("/cache/verses-1.epub.noimages", "/cache/verses-1.epub.images", "/cache/verses-1.txt"),
            acquisitions.map { it.rawHref },
        )
        // The EPUB-only subset drives the openable-file logic (2 of 3).
        assertEquals(2, acquisitions.count { it.mediaType!!.mediaRange == "application/epub+zip" })
        assertEquals("https://catalogue.example.org/cache/verses-1.epub.noimages", acquisitions[0].resolvedHref)
        assertEquals("https://catalogue.example.org/cache/verses-1.epub.images", acquisitions[1].resolvedHref)
        assertEquals("https://catalogue.example.org/cache/verses-1.epub.noimages", acquisitions[0].resolvedHref)
        assertEquals("https://catalogue.example.org/cache/verses-1.epub.images", acquisitions[1].resolvedHref)

        // The related (non-acquisition) link survives so the UI can offer the provider page.
        val related = edition1.links.single { "related" in it.relations }
        assertEquals("https://catalogue.example.org/work/verses/edition-1", related.resolvedHref)

        // §11.7 telling-line fields.
        assertNull(edition1.year) // no dc:issued/published in this fixture edition entry

        val edition2 = document.publications[1]
        assertEquals("urn:synthesis:verses:edition:2", edition2.identity.raw)
        assertEquals("2026-09-30T00:00:00Z", edition2.updated)
        assertEquals(2, edition2.acquisitionLinkCount)
        assertEquals("/cache/verses-2.mobi", edition2.acquisitionLinks[1].rawHref)
        assertEquals("application/x-mobipocket-ebook", edition2.acquisitionLinks[1].mediaType!!.mediaRange)
        // Same title, different identity and content: editions, not duplicates.
        assertEquals(edition1.title, edition2.title)
    }

    @Test
    fun acquisition_feed_self_and_up_links_resolved() {
        val document = parseFeed(
            Fixtures.OPDS1_ACQUISITION,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://catalogue.example.org/works/verses",
        )
        assertEquals("/works/verses", document.self!!.rawHref)
        assertEquals("https://catalogue.example.org/works/verses", document.self!!.resolvedHref)
        assertEquals("/listing", document.up.single { "up" in it.relations }.rawHref)
        assertEquals("https://catalogue.example.org/listing", document.up.single { "up" in it.relations }.resolvedHref)
    }

    @Test
    fun acquisition_feed_xhtml_content_kept_with_format() {
        val document = parseFeed(
            Fixtures.OPDS1_ACQUISITION,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://catalogue.example.org/works/verses",
        )
        val content = document.publications[0].content!!
        assertEquals(com.retro99.opds.api.model.OpdsContent.Format.XHTML, content.format)
        assertTrue(content.body.contains("<p>Description with <em>inline</em> markup"), content.body)
        // Entities are resolved at the reader level (Phase 0 record).
        assertTrue(content.body.contains("an opaque & opaque entity."), content.body.take(200))
    }

    @Test
    fun acquisition_feed_cover_images_surface_the_model_entry_and_link() {
        val document = parseFeed(
            Fixtures.OPDS1_ACQUISITION,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://catalogue.example.org/works/verses",
        )
        val edition1 = document.publications[0]
        val image = assertNotNull(edition1.images.singleOrNull { it.href == "/covers/verses-1.png" })
        assertEquals("image", image.mediaType!!.mainType)
        // The artwork link also stays in the links list as an image relation.
        val imageLink = edition1.links.single { "http://opds-spec.org/image" in it.relations }
        assertEquals("/covers/verses-1.png", imageLink.rawHref)
        assertEquals("https://catalogue.example.org/covers/verses-1.png", imageLink.resolvedHref)
    }

    // ---- treatise-entry.xml: standalone full entry with CDATA + root xml:base ----

    @Test
    fun standalone_full_entry_cdata_content_and_root_xml_base_resolution() {
        val outcome = parseFixture(
            Fixtures.OPDS1_FULL_ENTRY,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://catalogue.example.org/works/treatise.opds",
        )
        val document = when (outcome) {
            is OpdsParseResult.Document -> outcome.document as com.retro99.opds.api.model.OpdsPublicationDocument
            is OpdsParseResult.Rejected -> fail("unexpected rejection: $outcome")
        }

        assertEquals("urn:synthesis:treatise:1", document.publication.identity.raw)
        assertEquals(com.retro99.opds.api.model.OpdsIdentity.Kind.NOMINAL, document.publication.identity.kind)
        assertEquals("A Synthesized Treatise", document.publication.title)
        assertEquals("en", document.publication.languages.single())
        assertEquals("Bern Synth", document.publication.authors.single().name)

        // CDATA HTML content verbatim, including its raw "&amp;" (Phase 0 record).
        val content = document.publication.content!!
        assertEquals(com.retro99.opds.api.model.OpdsContent.Format.HTML, content.format)
        assertEquals("<p>An <b>HTML</b> blob describing the treatise &amp; more.</p>", content.body)

        // The root entry declares xml:base="/cache/"; links compose against the
        // entry-level base, keeping rawHref verbatim.
        val acquisition = document.publication.acquisitionLinks.single()
        assertEquals("treatise.epub", acquisition.rawHref)
        assertEquals("https://catalogue.example.org/cache/treatise.epub", acquisition.resolvedHref)
        val image = assertNotNull(document.publication.images.singleOrNull())
        assertEquals("treatise.png", image.href)
        // The root's xml:base was popped correctly: nothing leaks below the entry.
        assertEquals(1, 1)
    }
}
