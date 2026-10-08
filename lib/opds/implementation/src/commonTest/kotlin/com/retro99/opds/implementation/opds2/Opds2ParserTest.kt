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
