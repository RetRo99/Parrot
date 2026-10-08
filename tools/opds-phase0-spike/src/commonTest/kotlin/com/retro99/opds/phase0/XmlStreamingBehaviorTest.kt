package com.retro99.opds.phase0

import nl.adaptivity.xmlutil.IXmlStreaming
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Records how xmlutil's shared streaming reader behaves on each target for the
 * behaviors the OPDS parser depends on (plan §4 "Parsing"; §7 Phase 0:
 * "Validate xmlutil namespace/mixed-content/DTD behavior on Android and iOS").
 *
 * Event types are matched by string name so the test does not couple to the
 * exact xmlutil enum surface. Per-platform DTD/entity outcomes are pinned in
 * [PinnedBehavior] and may only be updated together with a note in
 * docs/opds-phase0-spikes.md.
 */
class XmlStreamingBehaviorTest {

    @Test
    fun `navigation_feed_namespaces_links_and_relative_hrefs_are_surfaced_verbatim`() {
        val result = walk(readFixtureText(Fixtures.OPDS1_LISTING))
        val feed = result.roots().singleOrNull { it.localName == "feed" } ?: fail("one Atom feed root")

        assertEquals(
            "http://www.w3.org/2005/Atom",
            feed.namespace,
            "${platformTag}: default namespace must resolve",
        )
        val entries = feed.children.filterIsInstance<CollectorElement>().filter { it.localName == "entry" }
        assertEquals(4, entries.size, "${platformTag}: entry count")

        val links = feed.children.filterIsInstance<CollectorElement>()
            .filter { it.localName == "link" }
            .map { it.attr("rel") to it.attr("href") }
        assertEquals(
            listOf(
                "self" to "/listing",
                "start" to "/",
                "next" to "/listing?offset=25",
                "search" to "/osd.xml",
            ),
            links,
            "${platformTag}: relative hrefs must be surfaced undeclined",
        )

        // Namespace resolution including the opensearch prefix.
        val itemsPerPage = feed.descendants().firstOrNull { it.localName == "itemsPerPage" }
        assertEquals(
            "http://a9.com/-/spec/opensearch/1.1/",
            itemsPerPage?.namespace,
            "${platformTag}: opensearch prefix must resolve to its namespace",
        )
        assertEquals("25", itemsPerPage?.textContent())

        // xml:base on an entry is an ordinary attribute and MUST be surfaced;
        // the parser (not the reader) combines it with relative links.
        val basedEntry = entries.firstOrNull { it.attr("xml:base") != null }
            ?: fail("xml:base attribute vanished under the reader")
        assertEquals("/works/", basedEntry.attr("xml:base"))
        assertEquals(
            "treatise",
            basedEntry.children.filterIsInstance<CollectorElement>().first { it.localName == "link" }
                .attr("href"),
        )

        // data-URI thumbnails round-trip without alteration.
        val thumbnail = feed.descendants().mapNotNull { it.attr("href") }.single { it.startsWith("data:") }
        assertTrue(
            thumbnail.startsWith("data:image/png;base64,iVBOR"),
            "${platformTag}: inline data image damaged: '$thumbnail'",
        )
    }

    @Test
    fun `full_entry_CDATA_content_and_root_xml_base`() {
        val result = walk(readFixtureText(Fixtures.OPDS1_FULL_ENTRY))
        val entry = result.roots().single { it.localName == "entry" }

        assertEquals("/cache/", entry.attr("xml:base"), "${platformTag}: root xml:base is an attribute")

        val content = entry.descendants().firstOrNull { it.localName == "content" }
            ?: fail("content element not found")
        val text = content.textContent()
        assertTrue(
            text == "<p>An <b>HTML</b> blob describing the treatise &amp; more.</p>" ||
                text == "<p>An <b>HTML</b> blob describing the treatise & more.</p>",
            "${platformTag}: CDATA content was not captured verbatim, got '$text'",
        )
        // The reader does not resolve xml:base; links stay relative at the element.
        assertEquals(listOf("treatise.epub", "treatise.png"), entry.hrefs())
    }

    @Test
    fun `acquisition_feed_xhtml_content_is_mixed_content_with_nested_markup`() {
        val result = walk(readFixtureText(Fixtures.OPDS1_ACQUISITION))
        val feed = result.roots().singleOrNull { it.localName == "feed" } ?: fail("feed root")

        val content = feed.descendants().firstOrNull { it.localName == "content" }
            ?: fail("xhtml content element not found")
        assertEquals("xhtml", content.attr("type"))

        val div = content.children.filterIsInstance<CollectorElement>().firstOrNull { it.localName == "div" }
            ?: fail("xhtml wrapper div not reported to the reader")
        val p = div.children.filterIsInstance<CollectorElement>().firstOrNull { it.localName == "p" }
            ?: fail("p element")
        val em = p.children.filterIsInstance<CollectorElement>().count { it.localName == "em" }
        assertEquals(1, em, "${platformTag}: nested markup must surface as elements")
        assertEquals(
            "Description with inline markup and an opaque & opaque entity.",
            p.textContent().trim(),
            "${platformTag}: mixed content text collection",
        )
    }

    @Test
    fun `internal_DTD_entities_DTDbearing_documents_fail_cleanly_in_both_reader_modes_recorded`() {
        val notes = mutableListOf<String>()
        for (expandEntities in listOf(false, true)) {
            val summaryText = try {
                walk(readFixtureText(Fixtures.DTD_BASELINE), expandEntities).roots()
                    .firstOrNull { it.localName == "summary" }?.textContent()
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: parse threw ${error::class.simpleName}: ${error.message?.take(140)}")
                null
            }
            println(
                "${platformTag}: internal-entity (expand=$expandEntities) → text=${summaryText?.let { "'$it'" }}",
            )
            // Recorded behavior (see docs/opds-phase0-spikes.md): the reader
            // never reaches the body for a DOCTYPE-bearing document, in either
            // expandEntities mode. Phase 1 will produce the user-facing
            // "malformed catalogue" error for this case.
            assertEquals(
                PinnedBehavior.internalEntityDocument,
                summaryText,
                "${platformTag}: recorded behavior changed; update the Phase 0 report note",
            )
        }
        assertTrue(notes.count { it.contains("XmlException") } == 2, "${platformTag}: expected both modes to fail cleanly, got notes=$notes")
    }

    @Test
    fun `external_DTD_entities_never_silently_expand`() {
        val notes = mutableListOf<String>()
        for (expandEntities in listOf(false, true)) {
            val summaryText = try {
                walk(readFixtureText(Fixtures.DTD_EXTERNAL), expandEntities).roots()
                    .firstOrNull { it.localName == "summary" }?.textContent()
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: parse threw ${error::class.simpleName}: ${error.message?.take(140)}")
                null
            }
            println(
                "${platformTag}: external-entity (expand=$expandEntities) → text=${summaryText?.let { "'$it'" }}",
            )
            // The parser must never substitute content it did not receive in
            // the document body: either parse fails cleanly or the summary is
            // the literal document text without expansion.
            assertTrue(
                notes.any { it.startsWith("expand=$expandEntities:") && it.contains("XmlException") },
                "${platformTag}: expected a clean XmlException for the external-entity fixture, got notes=$notes",
            )
        }
    }

    @Test
    fun `nonXML_page_behavior_is_recorded_either_way`() {
        val outcome = try {
            walk(readFixtureText(Fixtures.ERROR_HTML))
            "tolerated"
        } catch (error: Throwable) {
            "rejected (${error::class.simpleName})"
        }
        println("${platformTag}: HTML page → $outcome; Phase 1 detection must not rely on parse failure alone")
    }

    @Test
    fun `opensearch_descriptor_attribute_entities_and_templates_survive_parsing`() {
        val result = walk(readFixtureText(Fixtures.OPDS1_OPEN_SEARCH))
        val urls = result.roots().filterIsInstance<CollectorElement>()
            .flatMap { it.descendants() }
            .filter { it.localName == "Url" }
        assertEquals(2, urls.size)
        // &amp; inside the attribute value decodes exactly once (recorded:
        // attributes carry the resolved value, `&p=` here).
        assertEquals(
            "https://catalogue.example.org/opds/search?q={searchTerms}&p={startPage?}",
            urls.first { it.attr("type") == "application/atom+xml;profile=opds-catalog;kind=acquisition" }.attr("template"),
        )
        assertEquals(
            "https://catalogue.example.org/search/{searchTerms}",
            urls.first { it.attr("type") == "text/html" }.attr("template"),
        )
    }

    @Test
    fun `embedded fixture registry matches the resource fixture files`() {
        // Runs on Android (which can read the resource files) to catch drift
        // between the resource fixture files and their embedded copies used by
        // iOS; see tools/opds-phase0-spike/build.gradle.kts and
        // docs/opds-phase0-spikes.md for the loading strategy.
        for ((name, embedded) in EmbeddedFixtures.sources) {
            assertEquals(
                readFixtureText(name.removePrefix("/")),
                embedded.trimMargin("'"),
                "${platformTag}: fixture '$name' drifted between resources and EmbeddedFixtures",
            )
        }
    }

    // ---- tiny event collector -------------------------------------------------
    // Spike-only collector recording exactly what a streaming reader delivers,
    // per target. Phase 1 builds the real (bounded, hardened) walker.

    private sealed interface SpikeNode {
        fun textContent(): String
    }

    private open class CollectorElement(
        val localName: String,
        val namespace: String,
        val attributes: Map<String, String> = emptyMap(),
        val children: MutableList<SpikeNode> = mutableListOf(),
    ) : SpikeNode {
        fun attr(name: String): String? = attributes[name]

        override fun textContent(): String = children.joinToString("") { it.textContent() }

        fun hrefs(): List<String> = descendants()
            .filter { it.localName == "link" }
            .mapNotNull { it.attr("href") }
            .toList()

        fun descendants(): Sequence<CollectorElement> = children.asSequence().flatMap { child ->
            when (child) {
                is CollectorElement -> sequenceOf(child) + child.descendants()
                else -> emptySequence()
            }
        }
    }

    private data class TextNode(val value: String) : SpikeNode {
        override fun textContent(): String = value
    }

    private class WalkResult {
        val nodes = mutableListOf<SpikeNode>()
        var sawDtdEventName: String? = null

        fun roots(): List<CollectorElement> = nodes.filterIsInstance<CollectorElement>()
    }

    private fun walk(text: String, expandEntities: Boolean = false): WalkResult {
        val reader: XmlReader = xmlStreaming.newReader(text, expandEntities)
        val result = WalkResult()
        val stack = ArrayDeque<MutableList<SpikeNode>>().apply { addLast(result.nodes) }

        while (reader.hasNext()) {
            val typeName = reader.next().name
            when {
                typeName in setOf("DTD", "DOCDECL") -> result.sawDtdEventName = typeName
                typeName == "START_ELEMENT" -> {
                    val attrs = mutableMapOf<String, String>()
                    for (index in 0 until reader.attributeCount) {
                        val key = when (reader.getAttributeNamespace(index)) {
                            XML_NAMESPACE -> "xml:${reader.getAttributeLocalName(index)}"
                            else -> reader.getAttributeLocalName(index)
                        }
                        attrs[key] = reader.getAttributeValue(index)
                    }
                    val element = CollectorElement(
                        localName = reader.localName,
                        namespace = reader.namespaceURI,
                        attributes = attrs,
                    )
                    stack.last().add(element)
                    stack.addLast(element.children)
                }
                typeName == "END_ELEMENT" -> stack.removeLast()
                // Text can arrive as plain text, CDATA ("CDSECT" in XMLPullParser
                // naming), or as ENTITY_REF events whose reader.text already
                // carries the resolved predefined-entity char (recorded:
                // "&amp;" → ENTITY_REF with text "&").
                typeName == "TEXT" || typeName == "CDSECT" || typeName == "ENTITY_REF" ->
                    stack.last().add(TextNode(reader.text))
                else -> {} // COMMENT, PROCESSING_INSTRUCTION, and vendor-specific names: recorded separately
            }
        }
        return result
    }

    private companion object {
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    }

    private object PinnedBehavior {
        /**
         * Recorded 2026-10-08, xmlutil 1.0.2 generic reader (KtXmlReader;
         * JVM host and iOS simulator both run the generic implementation):
         *
         * - A document containing a DOCTYPE never reaches its body: the reader
         *   throws `nl.adaptivity.xmlutil.XmlException` ("Unexpected
         *   START_DOCUMENT ..."), in both `expandEntities` modes. No entities
         *   are expanded and no external resource is referenced.
         * - Outside of a DTD, predefined entity references (`&amp;` etc.)
         *   arrive as ENTITY_REF events whose `reader.text` already carries
         *   the resolved character ("&"); CDATA arrives as CDSECT events.
         * - `xml:base`/`xml:lang` surface as attributes with the
         *   http://www.w3.org/XML/1998/namespace namespace.
         * - Namespace prefixes (opensearch:/dc:/opds:) resolve to their URIs.
         */
        val internalEntityDocument: String? = null
    }
}
