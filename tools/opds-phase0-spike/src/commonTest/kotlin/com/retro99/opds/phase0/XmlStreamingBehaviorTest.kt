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
    fun `internal_DTD_entities_are_expanded_recorded_no_builtin_rejection`() {
        val notes = mutableListOf<String>()
        for (expandEntities in listOf(false, true)) {
            val summaryText = try {
                walk(readFixtureText(Fixtures.DTD_BASELINE), expandEntities).deepElements()
                    .firstOrNull { it.localName == "summary" }?.textContent()
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: parse threw ${error::class.simpleName}: ${error.message?.take(140)}")
                null
            }
            println(
                "${platformTag}: internal-entity (expand=$expandEntities) → text=${summaryText?.let { "'$it'" }} notes=$notes",
            )
            // Recorded behavior (docs/opds-phase0-spikes.md §2): the internal-DTD
            // document is ACCEPTED in both modes; the &d; entity tree arrives
            // resolved ("value: AAAAAAAA"), either expanded in place or as
            // resolved ENTITY_REF events. xmlutil does NOT reject DTDs itself.
            assertEquals(
                PinnedBehavior.internalEntityContent(expandEntities),
                summaryText,
                "${platformTag}: recorded behavior changed; update the Phase 0 report note (notes=$notes)",
            )
        }
    }

    @Test
    fun `internal_DTD_emits_DOCDECL_and_reaches_the_document_body`() {
        // Both modes: the DOCDECL event IS delivered and the body IS parsed.
        for (expandEntities in listOf(false, true)) {
            val result = walk(readFixtureText(Fixtures.DTD_BASELINE), expandEntities)
            assertEquals(
                PinnedBehavior.dtdEventName,
                result.sawDtdEventName,
            )
            val entry = result.roots().singleOrNull { it.localName == "entry" }
                ?: fail("$platformTag (expand=$expandEntities): body not reached")
            assertTrue(
                entry.textContent().startsWith("urn:synthesis:dtd-baseline:1") &&
                    entry.textContent().contains("value: "),
                "$platformTag (expand=$expandEntities): body text parsed: '${entry.textContent().take(60)}…'",
            )
        }
    }

    @Test
    fun `external_DTD_entities_are_rejected_at_the_declaration_recorded`() {
        val notes = mutableListOf<String>()
        for (expandEntities in listOf(false, true)) {
            val summaryText = try {
                walk(readFixtureText(Fixtures.DTD_EXTERNAL), expandEntities).deepElements()
                    .firstOrNull { it.localName == "summary" }?.textContent()
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: parse threw ${error::class.simpleName}: ${error.message?.take(140)}")
                null
            }
            println(
                "${platformTag}: external-entity (expand=$expandEntities) → text=${summaryText?.let { "'$it'" }} notes=$notes",
            )
            // Recorded behavior (docs/opds-phase0-spikes.md §2): the reader has
            // NO external-entity support: the parse fails with an XmlException
            // naming the DOCTYPE problem ("Unexpected content in document type
            // declaration") in both modes — no /etc/hosts content is ever
            // read through the text stream.
            assertEquals(
                true,
                summaryText == null,
                "${platformTag}: external entity must fail cleanly in both modes (notes=$notes)",
            )
            assertTrue(
                notes.single { it.startsWith("expand=$expandEntities:") }
                    .contains(PinnedBehavior.externalEntityFailureMessage),
                "$platformTag: external-entity failure text; notes=$notes",
            )
        }
    }

    @Test
    fun `deep_nested_entities_have_no_builtin_expansion_limit_recorded`() {
        // &e15; is 2^15 = 32,768 'A's (< the 5 MiB plan budget; fixture docstring).
        val notes = mutableListOf<String>()
        val summaries = mutableMapOf<Boolean, String?>()
        for (expandEntities in listOf(false, true)) {
            val summaryText = try {
                walk(readFixtureText(Fixtures.DTD_DEEP), expandEntities).deepElements()
                    .firstOrNull { it.localName == "summary" }?.textContent()
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: parse threw ${error::class.simpleName}: ${error.message?.take(140)}")
                null
            }
            summaries[expandEntities] = summaryText
            println(
                "${platformTag}: deep-nesting (expand=$expandEntities) → summary=${summaryText?.length ?: -1} chars, notes=$notes",
            )
            // Recorded: the expansion completes with NO limit enforced by the
            // library — the full expansion is buffered inside the reader.
            // Growth is exponential in the entity tree; larger trees are
            // unbounded without parser-side counters (Phase 1 REQUIRED).
            assertEquals(
                PinnedBehavior.deepEntityExpansionLength,
                summaryText?.length,
                "$platformTag (expand=$expandEntities): no built-in expansion limit",
            )
            assertEquals(
                PinnedBehavior.deepEntityExpansionBody,
                summaryText?.substringAfter("value: "),
                "$platformTag (expand=$expandEntities): expansion is uniform 'A's",
            )
        }
        // Mode-independent: with expandEntities=false the earlier Phase 0
        // record claimed "resolved ENTITY_REF events"; assert parity for the
        // deep fixture too.
        assertEquals(summaries[false], summaries[true], "$platformTag: both modes expand to the same text")
    }

    @Test
    fun `comment_before_declaration_is_malformed_independently_of_DTDs`() {
        // Isolates the recorder: the original Phase 0 fixtures carried a
        // comment before the declaration, so their "Unexpected START_DOCUMENT
        // in state START_DOC" error had nothing to do with the DOCTYPE. Both
        // failures must have distinct fixtures and distinct pins.
        val notes = mutableListOf<String>()
        for (expandEntities in listOf(false, true)) {
            try {
                walk(readFixtureText(Fixtures.COMMENT_BEFORE_DECLARATION), expandEntities)
                fail("$platformTag (expand=$expandEntities): misordered document must not parse")
            } catch (error: Throwable) {
                notes.add("expand=$expandEntities: ${error.message}")
            }
        }
        println("${platformTag}: comment-before-declaration → $notes")
        assertTrue(notes.size == 2, "$platformTag: both modes must fail, notes=$notes")
        assertEquals(
            PinnedBehavior.commentFirstFailureMessage,
            notes[0].substringAfter(": ", missingDelimiterValue = "").takeWhile { it != '\n' },
            "$platformTag: recorded comment-first failure text changed",
        )
    }

    @Test
    fun `phase1_mitigation_stops_at_DOCDECL_before_any_entity_is_read`() {
        for (expandEntities in listOf(false, true)) {
            for (fixture in listOf(
                Fixtures.DTD_BASELINE,
                Fixtures.DTD_DEEP,
                Fixtures.DTD_EXTERNAL,
                Fixtures.COMMENT_BEFORE_DECLARATION,
            )) {
                val outcome = parseStoppingAtDocdecl(readFixtureText(fixture), expandEntities)
                println(
                    "${platformTag}: mitigation (fixture=$fixture expand=$expandEntities) → " +
                        "stoppedAt=${outcome.stoppedAt} textSoFar=${outcome.textContent}",
                )
                assertEquals(
                    PinnedBehavior.mitigationStop(fixture),
                    outcome.stoppedAt,
                    "$platformTag (expand=$expandEntities): mitigation stop point for $fixture",
                )
                // No entity text before the stop: processing aborts with a
                // clear failure before any body text is read.
                assertEquals(
                    "",
                    outcome.textContent,
                    "$platformTag (expand=$expandEntities): no content may be read before the stop ($fixture)",
                )
            }
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

        /** All elements, in document order, including nested ones. */
        fun deepElements(): List<CollectorElement> = roots().flatMap { root ->
            listOf(root) + root.descendants().toList()
        }
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

    /**
     * The Phase 1 mitigation, demonstrated: abort parsing as soon as a
     * document-type declaration is observed — either at the DOCDECL event
     * xmlutil delivers, or, when the declaration itself is unparsable by this
     * reader (external `SYSTEM` entities), at the XmlException the reader
     * throws while processing the DOCTYPE. Either way nothing from the body
     * or any entity is read first; the caller surfaces a clear
     * "this document is not an accepted catalogue (document-type declarations
     * are not supported)" error.
     */
    private class DocdeclStop(val stoppedAt: String?, val textContent: String)

    private fun parseStoppingAtDocdecl(text: String, expandEntities: Boolean): DocdeclStop {
        val reader: XmlReader = xmlStreaming.newReader(text, expandEntities)
        val textSoFar = mutableListOf<String>()
        var stoppedAt: String? = null
        while (reader.hasNext()) {
            val typeName: String = try {
                reader.next().name
            } catch (thrown: Throwable) {
                // A DOCTYPE the library cannot decode (e.g. an external
                // SYSTEM entity) or a misordered document throws here,
                // before the DOCDECL event would be delivered and before
                // any body content: also an accepted mitigation stop.
                return DocdeclStop(PinnedBehavior.thrownBeforeDeclaration, "")
            }
            when {
                typeName in setOf("DTD", "DOCDECL") -> {
                    stoppedAt = typeName
                    return DocdeclStop(stoppedAt, textSoFar.joinToString(""))
                }
                typeName == "TEXT" || typeName == "CDSECT" || typeName == "ENTITY_REF" ->
                    textSoFar.add(reader.text)
            }
        }
        return DocdeclStop(stoppedAt, textSoFar.joinToString(""))
    }

    private companion object {
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    }

    private object PinnedBehavior {
        /**
         * Pinned 2026-10-08 on the Android host (JVM, xmlutil 1.0.2 generic
         * reader) and verified identical on the iOS simulator via
         * `newReader(text, expandEntities)`. See docs/opds-phase0-spikes.md §2.
         *
         * Verified real behavior — superseding the original Phase 0 pin,
         * which was an artifact of a malformed fixture (comment before
         * declaration):
         * - A well-formed document with an internal DTD is ACCEPTED: a
         *   DOCDECL event is delivered and the body is parsed. Internal
         *   entities ARE resolved: expandEntities=true expands to TEXT;
         *   expandEntities=false delivers the same characters as a chain of
         *   resolved ENTITY_REF events (container entities carry empty text,
         *   leaf entities carry their characters).
         * - An external entity (<!ENTITY x SYSTEM "file:///etc/hosts">) is
         *   rejected by xmlutil's own DOCTYPE parser with
         *   "Unexpected content in document type declaration" — before any
         *   DOCDECL event or body content. No file access occurs.
         * - There is NO built-in expansion limit: a 15-level nested entity
         *   tree (32,768 chars) is fully resolved/buffered in both modes;
         *   with expandEntities=false the cost manifests as a storm of
         *   ENTITY_REF events (one per leaf). Phase 1 bounding is REQUIRED.
         * - A comment placed before the declaration fails with
         *   "Unexpected START_DOCUMENT in state START_DOC" regardless of any
         *   DTD; dedicated fixture comment-before-declaration.xml keeps the
         *   two failure causes apart.
         */

        /** Name of the document-type event the reader delivers before the body. */
        val dtdEventName: String = "DOCDECL"

        /** &d; resolution, per expandEntities mode (both resolve the same text). */
        fun internalEntityContent(expandEntities: Boolean): String = "value: AAAAAAAA"

        /** External entity: parse must fail with xmlutil's own message fragment. */
        val externalEntityFailureMessage: String = "Unexpected content in document type declaration"

        /** Sentinel for a preamble the reader rejects before any DOCDECL event. */
        const val thrownBeforeDeclaration: String = "THROWN-BEFORE-DECL"

        /** Deep entity tree: no library-side limit; full expansion delivered. */
        val deepEntityExpansionLength: Int = 7 + 32768
        val deepEntityExpansionBody: String = "A".repeat(32768)

        /** Comment placed before the declaration fails like this. */
        val commentFirstFailureMessage: String = "11:1 - Unexpected START_DOCUMENT in state START_DOC"

        /** Recorded mitigation stop per fixture. */
        fun mitigationStop(fixture: String): String = when (fixture) {
            Fixtures.DTD_BASELINE, Fixtures.DTD_DEEP -> dtdEventName
            Fixtures.DTD_EXTERNAL -> thrownBeforeDeclaration
            Fixtures.COMMENT_BEFORE_DECLARATION -> thrownBeforeDeclaration // never reaches a DOCDECL
            else -> error("unpinned fixture '$fixture'")
        }
    }
}
