package com.retro99.opds.implementation.opds2

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import com.retro99.opds.implementation.fixtures.readFixtureText
import kotlin.test.*

class Opds2ParserTest {
    private val base = "https://catalogue.example.org/redirected/root.json"
    private fun parse(text: String): OpdsParseResult = ParserFactory.opdsParser().parse(
        OpdsPayload("application/opds+json", text.encodeToByteArray()), base)
    private fun fixture(name: String) = (parse(readFixtureText("opds/opds2/$name.json")) as OpdsParseResult.Document).document

    @Test fun optional_unknown_fields_relations_and_facets() {
        val feed = (parse("""{"metadata":{"title":"Minimal"},"unknown":{"nested":[1,2]},"publications":[
            {"metadata":{"title":"Book","edition":"Revised"},"links":[{"href":"book.json","rel":["self","alternate"]}]},
            {"metadata":{"title":"No identity"}}],"facets":[{"metadata":{"title":"Language"},"links":[
            {"href":"?all","title":"All","rel":"all","properties":{"numberOfItems":12}},
            {"href":"?en","title":"English","properties":{"numberOfItems":7,"active":true}}]}]}""") as OpdsParseResult.Document).document as OpdsFeedDocument
        assertEquals("Revised", feed.publications.first().editionLabel)
        assertEquals(listOf("self", "alternate"), feed.publications.first().links.single().relations)
        assertEquals(emptyList(), feed.publications.last().authors)
        assertNull(feed.publications.last().year)
        assertEquals(0, feed.publications.last().acquisitionLinkCount)
        assertEquals(OpdsIdentity.Kind.DOCUMENT_SCOPED_FALLBACK, feed.publications.last().identity.kind)
        assertTrue(feed.warnings.any { it.code == ParseWarning.Code.MISSING_IDENTITY })
        val facet = feed.facets.single()
        assertEquals("Language", facet.name)
        assertEquals(12L, facet.allOption?.count)
        assertEquals(7L, facet.options.last().count)
        assertTrue(facet.options.last().active)
    }

    @Test fun equivalent_versions_share_the_publication_model() {
        val xml = """<entry xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
            <id>https://catalogue.example.org/book</id><title>Shared</title><author><name>Writer</name></author>
            <dc:language>en</dc:language><dc:publisher>Press</dc:publisher><published>2026-01-01</published>
            <rights>Rights</rights><link rel="self" href="/book"/><link rel="http://opds-spec.org/acquisition" href="/book.epub" type="application/epub+zip"/>
            </entry>"""
        val one = (ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml", xml.encodeToByteArray()), base) as OpdsParseResult.Document).document as OpdsPublicationDocument
        val two = (parse("""{"metadata":{"title":"Shared","author":"Writer","language":"en","publisher":"Press","published":"2026-01-01","rights":"Rights"},"links":[{"rel":"self","href":"/book"},{"rel":"http://opds-spec.org/acquisition","href":"/book.epub","type":"application/epub+zip"}]}""") as OpdsParseResult.Document).document as OpdsPublicationDocument
        assertEquals(one.publication.identity, two.publication.identity)
        assertEquals(one.publication.title, two.publication.title)
        assertEquals(one.publication.authors, two.publication.authors)
        assertEquals(one.publication.languages, two.publication.languages)
        assertEquals(one.publication.publisher, two.publication.publisher)
        assertEquals(one.publication.published, two.publication.published)
        assertEquals(one.publication.rights, two.publication.rights)
        assertEquals(one.publication.links, two.publication.links)
    }

    @Test fun malformed_items_warn_but_root_fails() {
        val feed = (parse("""{"metadata":{"title":"Feed"},"publications":[null,{"metadata":{}},{"metadata":{"title":"Good"}}],"groups":[null],"facets":[null]}""") as OpdsParseResult.Document).document as OpdsFeedDocument
        assertEquals(1, feed.publications.size)
        assertEquals(4, feed.warnings.count { it.code == ParseWarning.Code.MALFORMED_ITEM_SKIPPED })
        assertIs<OpdsParseResult.Rejected>(parse("""{"metadata":{},"publications":[]}"""))
        assertIs<OpdsParseResult.Rejected>(parse("""{"metadata":{"title":"Broken"}"""))
        assertIs<OpdsParseResult.Rejected>(parse("""{"metadata":{"title":"Broken"},"publications":{}}"""))
    }

    @Test fun bytes_budget() {
        val result = ParserFactory.opdsParser().parse(OpdsPayload("application/opds+json", ByteArray(OpdsBudgets.MAX_RESPONSE_BYTES.toInt() + 1)), base)
        assertIs<OpdsRejection.TooLarge>((result as OpdsParseResult.Rejected).rejection)
    }
    @Test fun generic_json_uses_structure_not_first_key() {
        val result = ParserFactory.opdsParser().parse(OpdsPayload("application/json", readFixtureText("opds/opds2/catalog.json").encodeToByteArray()), base)
        assertIs<OpdsFeedDocument>((result as OpdsParseResult.Document).document)
        assertIs<OpdsParseResult.Rejected>(parse("""{"error":"Not a catalogue"}"""))
    }
    @Test fun depth_budget_including_unknown_fields() {
        val result = parse("""{"metadata":{"title":"Deep"},"publications":[],"unknown":${"[".repeat(65)}0${"]".repeat(65)}}""")
        assertIs<OpdsRejection.TooDeep>((result as OpdsParseResult.Rejected).rejection)
    }
    @Test fun item_budget_includes_groups_and_navigation() {
        val items = List(2000) { """{"href":"/item/$it","title":"Item"}""" }.joinToString(",")
        val result = parse("""{"metadata":{"title":"Many"},"navigation":[$items],"groups":[{"metadata":{"title":"Group"},"publications":[{"metadata":{"title":"Extra"}}]}]}""")
        assertIs<OpdsRejection.TooManyItems>((result as OpdsParseResult.Rejected).rejection)
    }

    @Test fun limits_accept_boundaries_and_ignore_braces_in_strings() {
        val items = List(2000) { """{"href":"/item/$it"}""" }.joinToString(",")
        assertIs<OpdsParseResult.Document>(parse("""{"metadata":{"title":"Many"},"navigation":[$items]}"""))
        assertIs<OpdsParseResult.Document>(parse("""{"metadata":{"title":"Deep"},"publications":[],"unknown":${"[".repeat(63)}0${"]".repeat(63)}}"""))
        assertIs<OpdsParseResult.Document>(parse("""{"metadata":{"title":"Escaped \\\" ${"{".repeat(100)}"},"publications":[]}"""))
    }

    @Test fun malformed_nested_sections_and_contributors_are_recoverable() {
        val document = (parse("""{"metadata":{"title":"Feed"},"publications":[{"metadata":{"title":"Good","author":[null,123,{"name":"Writer"}]},"images":[null,{"href":"/image"}],"links":[{"href":"/good"},null]}],"groups":[{"metadata":{"title":"Bad"},"publications":{}},{"metadata":{"title":"Good group"},"publications":[]}]}""") as OpdsParseResult.Document).document as OpdsFeedDocument
        assertEquals(1, document.publications.size)
        assertEquals(listOf("Writer"), document.publications.single().authors.map { it.name })
        assertEquals(1, document.publications.single().images.size)
        assertEquals(listOf("Good group"), document.groups.map { it.title })
        assertEquals(5, document.warnings.count { it.code == ParseWarning.Code.MALFORMED_ITEM_SKIPPED })
    }

    @Test fun identity_fallbacks_are_scoped_and_repeatable() {
        val json = """{"metadata":{"title":"Feed"},"publications":[{"metadata":{"title":"Same"}},{"metadata":{"title":"Same"}},{"metadata":{"title":"Identifier","identifier":"urn:example:1"}}]}"""
        val feed = (parse(json) as OpdsParseResult.Document).document as OpdsFeedDocument
        assertNotEquals(feed.publications[0].identity, feed.publications[1].identity)
        assertEquals(feed.publications.map { it.identity }, ((parse(json) as OpdsParseResult.Document).document as OpdsFeedDocument).publications.map { it.identity })
        val other = (ParserFactory.opdsParser().parse(OpdsPayload("application/opds+json", json.encodeToByteArray()), "https://catalogue.example.org/other.json") as OpdsParseResult.Document).document as OpdsFeedDocument
        assertEquals(feed.publications.last().identity, other.publications.last().identity)
        assertNotEquals(feed.publications.first().identity, other.publications.first().identity)
    }

    @Test fun multiple_indirect_roots_and_standard_price_object() {
        val publication = (parse("""{"metadata":{"title":"Purchase"},"links":[{"href":"/buy","rel":["buy","related"],"properties":{"price":{"value":9.5,"currency":"USD"},"indirectAcquisition":[{"type":"text/html","children":[{"type":"application/epub+zip"}]},{"type":"application/pdf"}]}}]}""") as OpdsParseResult.Document).document as OpdsPublicationDocument
        val link = publication.publication.links.single()
        assertEquals(OpdsPrice(9.5, "USD"), link.price)
        val tree = assertNotNull(link.indirectAcquisition)
        assertNull(tree.mediaType)
        assertEquals(2, tree.children.size)
        assertEquals("application/epub+zip", tree.children.first().children.single().mediaType?.mediaRange)
    }

    @Test fun landscape_standalone_publication() {
        val document = fixture("landscape") as OpdsPublicationDocument
        val book = document.publication
        assertEquals("Localized Landscape", book.title)
        assertEquals(document.self?.resolvedHref, book.identity.raw)
        assertEquals(listOf("Gray Script", "Emery Quill"), book.authors.map { it.name })
        assertEquals(OpdsContributor("Hesta Vane", "http://catalogue.example.org/people/vane", "translator"), book.otherContributors.single())
        assertEquals("Synth Press", book.publisher)
        assertEquals("2026", book.year)
        assertEquals("Synthetic rights text.", book.rights)
        assertEquals(listOf(300, 900), book.images.map { it.width })
        assertEquals(listOf(450, 1350), book.images.map { it.height })
        val indirect = assertNotNull(book.links[1].indirectAcquisition)
        assertEquals("text/html", indirect.mediaType?.mediaRange)
        assertEquals("application/epub+zip", indirect.children.single().mediaType?.mediaRange)
        assertEquals(OpdsPrice(12.5, "EUR"), book.links[2].price)
        assertEquals(2, book.acquisitionLinkCount)
        assertEquals("alternate", book.links.last().relations.single())
    }

    @Test fun catalog_metadata_topology_and_contributors() {
        val feed = fixture("catalog") as OpdsFeedDocument
        assertEquals("Example Catalogue — Root", feed.metadata.title)
        assertEquals("urn:uuid:77777777-8888-9999-aaaa-bbbbbbbbbbbb", feed.metadata.identifier?.raw)
        assertEquals("2026-10-08T00:00:00Z", feed.metadata.modified)
        assertEquals("en", feed.metadata.language)
        assertEquals("Example Catalogue", feed.metadata.authors.single().name)
        assertEquals(3, feed.navigation.size)
        assertEquals("New publications", feed.navigation.first().title)
        assertEquals("https://catalogue.example.org/opds/2/new.json", feed.navigation.first().identity.raw)
        assertEquals("https://catalogue.example.org/opds/2/root.json?pag=2", feed.pagination.next?.resolvedHref)
        assertNull(feed.pagination.previous)
        assertEquals(listOf("Visitors", "Residents"), feed.groups.map { it.title })
        assertEquals("Kim Synth", feed.groups.last().publications.single().authors.single().name)
        assertEquals("Genre", feed.facets.single().name)
        assertEquals(listOf("Fiction", "Essays"), feed.facets.single().options.map { it.title })
        assertTrue(feed.facets.single().options.first().active)
        val search = assertNotNull(feed.search)
        assertEquals(OpdsSearchOffer.Kind.URI_TEMPLATE, search.kind)
        assertEquals("{?query,title}", search.link.rawHref)
        assertTrue(search.link.isTemplate)
        assertNull(search.link.resolvedHref)
        val book = feed.publications.single()
        assertEquals("https://catalogue.example.org/opds/2/publications/river.json", book.identity.raw)
        assertEquals(listOf("Casey Script", "Emery Quill", "Gordon Manifold"), book.authors.map { it.name })
        assertEquals(listOf("en", "de"), book.languages)
        assertEquals("Synth Press", book.publisher)
        assertEquals("2026", book.year)
        assertEquals(1, book.acquisitionLinkCount)
        assertEquals(2, book.images.size)
    }
}
