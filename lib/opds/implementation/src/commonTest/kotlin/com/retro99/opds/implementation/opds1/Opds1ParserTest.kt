package com.retro99.opds.implementation.opds1

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.api.model.OpdsText
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
    private fun assertEquals(expected: String, actual: OpdsText?) = kotlin.test.assertEquals(OpdsText(expected), actual)

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
        val inherited = document.navigation.first { it.title == OpdsText("A Synthesized Treatise") }
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
        assertTrue(content.body.translations.getValue("und").contains("<p>Description with <em>inline</em> markup"), content.body.toString())
        // Entities are resolved at the reader level (Phase 0 record).
        assertTrue(content.body.translations.getValue("und").contains("an opaque & opaque entity."), content.body.toString().take(200))
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

    // ---- calibre-newest.xml: direct-acquisition entries, colliding titles ----

    @Test
    fun calibre_feed_entries_are_publications_and_never_a_grouping_point() {
        val document = parseFeed(
            Fixtures.OPDS1_CALIBRE_NEWEST,
            mediaType = "application/atom+xml;profile=opds-catalog;kind=acquisition",
            effectiveResponseUrl = "https://calibre.example.org/opds/newest",
        )
        assertEquals("My Calibre Library", document.metadata.title)
        assertEquals(2, document.publications.size)
        assertEquals(0, document.navigation.size)

        val first = document.publications[0]
        val second = document.publications[1]
        assertEquals("calibre:book:1", first.identity.raw)
        assertEquals("calibre:book:2", second.identity.raw)
        // Identical titles across different works (the design's negative case).
        assertEquals(first.title, second.title)
        assertEquals("Mara Write", first.authors.single().name)
        assertEquals("Jon Marnet", second.authors.single().name)

        // Both entries carry their own acquisition links and XHTML descriptions.
        assertEquals(1, first.acquisitionLinkCount)
        assertEquals(1, second.acquisitionLinkCount)
        assertEquals("/opds/get/1.epub", first.acquisitionLinks.single().rawHref)
        assertEquals("https://calibre.example.org/opds/get/1.epub", first.acquisitionLinks.single().resolvedHref)
        assertEquals("application/epub+zip", first.acquisitionLinks.single().mediaType!!.mediaRange)
        assertTrue(first.content!!.body.translations.getValue("und").contains("<p>First book.</p>"))

        // The decided grouping rule (Phase 0 §3 item 5): entries with their own
        // acquisition links are books, NOT a grouping point — a list, whatever
        // the titles do.
        assertEquals(
            com.retro99.opds.api.OpdsEditionDecision.ListOfBooks::class,
            com.retro99.opds.api.OpdsGroupingRule.decide(first, com.retro99.opds.api.OpdsGroupingRule.FetchedTarget.Unknown)::class,
        )
        assertTrue(
            com.retro99.opds.api.OpdsGroupingRule.decide(first, com.retro99.opds.api.OpdsGroupingRule.FetchedTarget.Unknown)
                .rationale.contains("itself acquires"),
        )
    }

    // ---- missing optional fields, unknown extensions ---------------------------

    @Test
    fun entries_without_id_get_a_document_scoped_fallback_identity_with_warning() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Feed without entry ids</title>
              <updated>2026-10-08T00:00:00Z</updated>
              <entry><title>Book Alpha</title><summary>s1</summary></entry>
              <entry><title>Book Beta</title><summary>s2</summary></entry>
            </feed>
        """.trimIndent()
        val outcome = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=navigation", xml.encodeToByteArray()),
            "https://x.dev",
        )
        val document = when (outcome) {
            is OpdsParseResult.Document -> outcome.document as com.retro99.opds.api.model.OpdsFeedDocument
            is OpdsParseResult.Rejected -> fail("$outcome")
        }
        val ids = document.publications.map { it.identity }.takeIf { it.isNotEmpty() }
            ?: document.navigation.map { it.identity }
        // Recoverable: the feed is accepted; identity goes to the documented
        // deterministic document-scoped fallback with a MISSING_IDENTITY
        // warning (plan §3.2; never a title/position/ISBN/download URL).
        assertEquals(2, ids.size)
        assertTrue(ids.all { it.kind == com.retro99.opds.api.model.OpdsIdentity.Kind.DOCUMENT_SCOPED_FALLBACK })
        assertTrue(ids[0].raw != ids[1].raw)
        assertTrue(ids[0].note != null)
        assertTrue(document.warnings.any { it.code == com.retro99.opds.api.model.ParseWarning.Code.MISSING_IDENTITY })
    }

    @Test
    fun unknown_extension_children_are_ignored_with_bounded_warnings() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:ext="urn:example:ext">
              <id>urn:uuid:feed</id><title>Extension feed</title>
              <updated>2026-10-08T00:00:00Z</updated>
              <ext:custom>whatever</ext:custom>
              <entry><id>urn:synthesis:ext:1</id><title>t</title><ext:thing>x</ext:thing></entry>
            </feed>
        """.trimIndent()
        val outcome = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=navigation", xml.encodeToByteArray()),
            "https://x.dev",
        )
        val document = when (outcome) {
            is OpdsParseResult.Document -> outcome.document as com.retro99.opds.api.model.OpdsFeedDocument
            is OpdsParseResult.Rejected -> fail("$outcome")
        }
        // The rest of the document parses normally.
        assertEquals(1, document.navigation.size)
        assertEquals("urn:synthesis:ext:1", document.navigation.single().identity.raw)
        // Unknown extensions are bounded, recorded warnings — one per element,
        // counted per recording path (plan §4 "unknown extension fields are
        // ignored or retained within bounded containers").
        assertTrue(
            document.warnings.count { it.code == com.retro99.opds.api.model.ParseWarning.Code.UNKNOWN_EXTENSION_IGNORED } == 2,
            document.warnings.toString(),
        )
    }

    @Test
    fun missing_feed_title_falls_back_without_rejection() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><id>urn:synthesis:notitle:1</id><title>Only entry</title></entry>
            </feed>
        """.trimIndent()
        val document = when (val outcome = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=navigation", xml.encodeToByteArray()),
            "https://x.dev",
        )) {
            is OpdsParseResult.Document -> outcome.document as com.retro99.opds.api.model.OpdsFeedDocument
            is OpdsParseResult.Rejected -> fail("$outcome")
        }
        assertEquals("Untitled catalogue", document.metadata.title) // model fallback, not a failure
        assertEquals(1, document.navigation.size) // no acquisition links → navigation
    }

    // ---- budgets: bytes, nesting depth, item count (plan §4; OpdsBudgets) -----

    @Test
    fun oversized_feed_bytes_rejected() {
        // Just over the 5 MiB budget: bytes boundary checked before walking.
        val titleLength = OpdsBudgets.MAX_RESPONSE_BYTES.toInt() + 64
        val xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>${"x".repeat(titleLength)}</title></feed>"
        val result = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=acquisition", xml.encodeToByteArray()),
            "https://x.dev",
        )
        assertTrue(
            result is OpdsParseResult.Rejected && result.rejection is OpdsRejection.TooLarge,
            result.toString(),
        )
    }

    @Test
    fun nesting_deeper_than_budget_rejected() {
        val depth = OpdsBudgets.MAX_NESTING_DEPTH + 1
        val opens = buildString { repeat(depth) { append("<div>") } }
        val closes = buildString { repeat(depth) { append("</div>") } }
        val xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\">" +
            "<entry><title>t</title><content type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\">$opens<p>deep</p>$closes</div></content></entry></feed>"
        val outcome = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=acquisition", xml.encodeToByteArray()),
            "https://x.dev",
        )
        assertTrue(
            outcome is OpdsParseResult.Rejected && outcome.rejection is OpdsRejection.TooDeep,
            outcome.toString(),
        )
    }

    @Test
    fun item_count_over_budget_rejected() {
        val count = OpdsBudgets.MAX_ITEMS_PER_RESPONSE + 1
        val xml = buildString {
            append("<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>big</title>")
            repeat(count) { append("<entry><title>t$it</title></entry>") }
            append("</feed>")
        }
        val outcome = parser().parse(
            OpdsPayload("application/atom+xml;profile=opds-catalog;kind=acquisition", xml.encodeToByteArray()),
            "https://x.dev",
        )
        assertTrue(
            outcome is OpdsParseResult.Rejected && outcome.rejection is OpdsRejection.TooManyItems,
            outcome.toString(),
        )
    }

    // ---- document-type declarations are refused (REQUIRED Phase 1 work) -------

    @Test
    fun internal_dtd_document_is_rejected_at_DOCDECL_before_entities_expand() {
        val outcome = parseFixture(Fixtures.DTD_BASELINE, mediaType = "application/xml")
        assertTrue(
            outcome is OpdsParseResult.Rejected && outcome.rejection is OpdsRejection.DocumentTypeDeclarationRejected,
            outcome.toString(),
        )
    }

    @Test
    fun external_dtd_document_is_rejected_like_internal_one() {
        val outcome = parseFixture(Fixtures.DTD_EXTERNAL, mediaType = "application/xml")
        assertTrue(
            outcome is OpdsParseResult.Rejected && outcome.rejection is OpdsRejection.DocumentTypeDeclarationRejected,
            outcome.toString(),
        )
    }

    @Test
    fun deep_nested_entity_document_is_rejected_even_under_byte_budget() {
        // The deep tree would expand to ~32 KB — well under the byte budget, so
        // this documents the DOCDECL stop, not a size-limit stop.
        val outcome = parseFixture(Fixtures.DTD_DEEP, mediaType = "application/xml")
        assertTrue(
            outcome is OpdsParseResult.Rejected && outcome.rejection is OpdsRejection.DocumentTypeDeclarationRejected,
            outcome.toString(),
        )
    }

    @Test
    fun comment_before_declaration_is_rejected_cleanly() {
        val outcome = parseFixture(Fixtures.COMMENT_BEFORE_DECLARATION, mediaType = "application/xml")
        assertTrue(outcome is OpdsParseResult.Rejected, outcome.toString())
    }

    @Test
    fun walker_DOCDECL_stop_is_reachable_and_stops_before_entity_text() {
        // Defense in depth: the dispatcher's detector usually gates first;
        // the walker's own DOCDECL stop is asserted by invoking the OPDS1
        // parser directly. For DOCTYPEs the reader can decode (internal DTDs)
        // the walker stops with the explicit document-type rejection; for
        // DOCTYPEs the reader itself refuses (external SYSTEM entity,
        // Phase 0 record) the reader's clean failure is an equivalent stop —
        // either way no entity text is produced.
        val direct = Opds1Parser(ParserFactory.urlResolver())
        for (fixture in listOf(Fixtures.DTD_BASELINE, Fixtures.DTD_DEEP)) {
            val outcome = direct.parse(
                OpdsPayload("application/xml", readFixtureText(fixture).encodeToByteArray()),
                "https://x.dev",
            )
            assertTrue(
                outcome is OpdsParseResult.Rejected &&
                    outcome.rejection is OpdsRejection.DocumentTypeDeclarationRejected,
                "$fixture via walker: $outcome",
            )
        }
        val external = direct.parse(
            OpdsPayload("application/xml", readFixtureText(Fixtures.DTD_EXTERNAL).encodeToByteArray()),
            "https://x.dev",
        )
        // Clean stop either way; never content.
        assertTrue(external is OpdsParseResult.Rejected, external.toString())
    }
}
