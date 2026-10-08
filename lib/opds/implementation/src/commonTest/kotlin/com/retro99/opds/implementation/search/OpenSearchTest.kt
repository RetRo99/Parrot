package com.retro99.opds.implementation.search

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import com.retro99.opds.implementation.fixtures.readFixtureText
import kotlin.test.*

class OpenSearchTest {
    private val reader = OpenSearchReader(ParserFactory.urlResolver())
    private val base = "https://example.org/redirected/description.xml"
    private fun read(xml: String) = reader.readDescriptor(OpdsPayload("application/opensearchdescription+xml", xml.encodeToByteArray()), base)
    private fun preferred(xml: String) = assertNotNull((read(xml) as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor.preferred)
    private fun descriptor(urls: String) = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">$urls</OpenSearchDescription>"""

    @Test fun osd_fixture_prefers_atom_and_expands_optional_page_default() {
        val result = (read(readFixtureText("opds/opds1/osd.xml")) as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor
        val preferred = assertNotNull(result.preferred)
        assertEquals("application/atom+xml", preferred.responseMediaType?.mediaRange)
        assertEquals("opds-catalog", preferred.responseMediaType?.parameter("profile"))
        assertEquals("UTF-8", preferred.inputEncoding)
        assertEquals(listOf("searchTerms", "startPage"), preferred.parameters.map { it.name })
        assertEquals(listOf(true, false), preferred.parameters.map { it.required })
        assertEquals("1", preferred.parameters.last().defaultValue)
        assertEquals("https://catalogue.example.org/opds/search?q=caf%C3%A9%20%26%2B&p=1", reader.expand(preferred, "café &+"))
        assertEquals("https://catalogue.example.org/opds/search?q=river&p=4", reader.expand(preferred, "river", mapOf("startPage" to "4")))
    }
    @Test fun plain_atom_is_valid_and_html_suggestions_are_not_selected() {
        val result = (read(descriptor("""<Url type="text/html" template="/html/{searchTerms}"/><Url type="application/x-suggestions+json" template="/suggest/{searchTerms}"/><Url type="application/atom+xml" template="/atom/{searchTerms}"/>""")) as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor
        assertEquals("/atom/{searchTerms}", result.preferred?.template)
        assertEquals("https://example.org/atom/river", reader.expand(assertNotNull(result.preferred), "river"))
        assertNull((read(descriptor("""<Url type="text/html" template="/html/{searchTerms}"/>""")) as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor.preferred)
    }
    @Test fun opds_types_rank_above_plain_atom_and_ties_keep_order() {
        val result = (read(descriptor("""<Url type="application/atom+xml" template="/plain/{searchTerms}"/><Url type="application/atom+xml;profile=opds-catalog" template="/first/{searchTerms}"/><Url type="application/opds+json" template="/second/{searchTerms}"/>""")) as OpdsOpenSearchReader.OpdsDescriptorResult.Descriptor).descriptor
        assertEquals("/first/{searchTerms}", result.preferred?.template)
        assertEquals(listOf("/plain/{searchTerms}", "/second/{searchTerms}"), result.alternatives.map { it.template })
    }
    @Test fun expanded_reference_resolves_after_expansion_against_effective_xml_base() {
        val preferred = preferred(descriptor("""<Url xml:base="../search/" type="application/atom+xml" template="{searchTerms}/results"/>"""))
        assertEquals("https://example.org/search/river/results", reader.expand(preferred, "river"))
        assertEquals("https://example.org/search/a%2Fb/results", reader.expand(preferred, "a/b"))
        val queryOnly = preferred(descriptor("""<Url type="application/atom+xml" template="?term={searchTerms}"/>"""))
        assertEquals("https://example.org/redirected/description.xml?term=river", reader.expand(queryOnly, "river"))
    }
    @Test fun unsupported_required_parameter_is_explicit_and_optional_is_omitted() {
        val required = preferred(descriptor("""<Url type="application/atom+xml" template="?term={searchTerms}&amp;lat={geo:lat}"/>"""))
        val error = assertFailsWith<OpdsSearchError.UnsupportedRequiredParameter> { reader.expand(required, "river") }
        assertEquals("geo:lat", error.parameterName)
        val optional = preferred(descriptor("""<Url type="application/atom+xml" template="?term={searchTerms}&amp;extra={unknown?}"/>"""))
        assertEquals("https://example.org/redirected/description.xml?term=river&extra=", reader.expand(optional, "river"))
    }
    @Test fun advertised_offsets_and_encoding_parameters_have_defaults() {
        val preferred = preferred(descriptor("""<InputEncoding>UTF-8</InputEncoding><OutputEncoding>UTF-8</OutputEncoding><Url type="application/atom+xml" pageOffset="0" indexOffset="0" template="?term={searchTerms}&amp;page={startPage?}&amp;index={startIndex}&amp;input={inputEncoding}&amp;output={outputEncoding}&amp;count={count?}"/>"""))
        assertEquals("https://example.org/redirected/description.xml?term=river&page=0&index=0&input=UTF-8&output=UTF-8&count=", reader.expand(preferred, "river"))
        assertTrue(reader.expand(preferred, "river", mapOf("count" to "12")).endsWith("count=12"))
    }
    @Test fun malformed_root_and_wrong_namespace_fail() {
        for (xml in listOf("<html/>", "<OpenSearchDescription/>", "<OpenSearchDescription xmlns=\"http://a9.com/-/spec/opensearch/1.1/\">")) {
            assertIs<OpdsOpenSearchReader.OpdsDescriptorResult.NotADescriptor>(read(xml))
        }
    }
    @Test fun opds2_catalog_search_expands_advertised_fields_before_resolution() {
        val feed = (ParserFactory.opdsParser().parse(OpdsPayload("application/opds+json", readFixtureText("opds/opds2/catalog.json").encodeToByteArray()), "https://example.org/redirected/root.json") as OpdsParseResult.Document).document as OpdsFeedDocument
        val offer = assertNotNull(feed.search)
        val search = OpdsSearchResolver(ParserFactory.urlResolver(), Rfc6570Expander())
        assertEquals("https://example.org/redirected/root.json?query=caf%C3%A9%20%26&title=River", search.expand(offer, feed.effectiveResponseUrl, "café &", mapOf("title" to "River", "unadvertised" to "ignored")))
        assertEquals("{?query,title}", offer.link.rawHref)
        assertNull(offer.link.resolvedHref)
        val relative = offer.copy(link = offer.link.copy(rawHref = "{+route}{?query}"))
        assertEquals("https://example.org/new?query=river", search.expand(relative, feed.effectiveResponseUrl, "river", mapOf("route" to "/new")))
    }
}
