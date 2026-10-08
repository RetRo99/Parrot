package com.retro99.opds.implementation.opds1

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.OpdsUrlResolver
import com.retro99.opds.api.UnsupportedOpdsEncodingException
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.api.model.OpdsContent
import com.retro99.opds.api.model.OpdsContributor
import com.retro99.opds.api.model.OpdsEntry
import com.retro99.opds.api.model.OpdsFeedDocument
import com.retro99.opds.api.model.OpdsFeedMetadata
import com.retro99.opds.api.model.OpdsIdentifier
import com.retro99.opds.api.model.OpdsImage
import com.retro99.opds.api.model.OpdsLink
import com.retro99.opds.api.model.OpdsPagination
import com.retro99.opds.api.model.OpdsPublicationDocument
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.api.model.OpdsSearchOffer
import com.retro99.opds.api.model.OpdsIdentity
import com.retro99.opds.api.model.ParseWarning
import com.retro99.opds.api.model.OpdsText
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * OPDS 1.x (Atom profile) parser on a bounded walker + visitors.
 *
 * Reader/stream conventions per docs/opds-phase0-spikes.md §2 and plan §4:
 *
 * - **DOCDECL stop before any entity text is read** — xmlutil accepts
 *   well-formed internal DTDs, delivers DOCDECL, and resolves internal
 *   entities in both `expandEntities` modes with NO expansion limit; a
 *   DOCTYPE is a hard rejection here, which is REQUIRED Phase 1 work.
 * - Structural budgets: nesting depth, item count, response bytes.
 * - Pagination strictly from declared links (next/previous|prev/first/last).
 * - Links keep the raw href plus the RFC 3986-resolved URL composed from the
 *   effective response URL and any inherited xml:base; templates (`{...}`)
 *   and `data:` URIs are NOT resolved.
 * - Identity: feed-declared ids only; missing ones take a deterministic
 *   document-scoped fallback with a warning (§3.2) — never title, position,
 *   ISBN, or download URL.
 * - Attributes are read only inside START_ELEMENT (reader stale after END);
 *   text is TEXT + CDSECT + ENTITY_REF concatenation.
 * - Recoverable per-item problems become warnings; malformed roots fail.
 */
internal class Opds1Parser(
    private val resolver: OpdsUrlResolver,
    private val mediaTypeParser: SeparatedMediaTypeParser = SeparatedMediaTypeParser(),
) : OpdsParser {

    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult {
        if (payload.bytes.size > OpdsBudgets.MAX_RESPONSE_BYTES) {
            return OpdsParseResult.Rejected(OpdsRejection.TooLarge())
        }
        val state = ParseState(effectiveResponseUrl, resolver)
        try {
            Opds1XmlWalker(state).walk(xmlStreaming.newReader(payload.asText(), false), RootVisitor(state, mediaTypeParser))
        } catch (_: UnsupportedOpdsEncodingException) {
            return OpdsParseResult.Rejected(OpdsRejection.UnsupportedEncoding())
        } catch (structural: StructuralAbort) {
            return OpdsParseResult.Rejected(structural.rejection)
        } catch (parseError: Exception) {
            return OpdsParseResult.Rejected(OpdsRejection.Malformed(parseError.message?.take(120)))
        }
        return state.assemble()
    }
}

// ── parse state ─────────────────────────────────────────────────────────

private class ParseState(
    val effectiveResponseUrl: String,
    private val resolver: OpdsUrlResolver,
) {
    val warnings: MutableList<ParseWarning> = mutableListOf()
    /** xml:base chain; index 0 is the effective response URL. */
    val baseStack: MutableList<String> = mutableListOf(effectiveResponseUrl)
    var depth = 0
    var entryCount = 0
    var rootIsEntry = false
    val entries: MutableList<OpdsEntry> = mutableListOf()

    // feed metadata
    var feedTitle: OpdsText? = null
    var feedIdentity: String? = null
    var feedUpdated: String? = null
    var feedRights: OpdsText? = null
    val languageStack = mutableListOf<String?>(null)
    fun tagged(text: String, attributes: Map<String, String> = emptyMap()): OpdsText =
        OpdsText(text, if ("xml:lang" in attributes) attributes["xml:lang"] else languageStack.last())
    val feedAuthors: MutableList<OpdsContributor> = mutableListOf()

    // feed topology, declared links only (plan §2.2)
    var selfLink: OpdsLink? = null
    var searchOffer: OpdsSearchOffer? = null
    var firstLink: OpdsLink? = null
    var nextLink: OpdsLink? = null
    var prevLink: OpdsLink? = null
    var lastLink: OpdsLink? = null
    val upLinks: MutableList<OpdsLink> = mutableListOf()

    fun base(): String = baseStack.last()

    fun resolve(href: String): String? = when {
        href.startsWith("data:") -> null // bounded inline image (Phase 0 record)
        href.contains('{') -> null // templates are expanded later, never resolved early
        else -> runCatching { resolver.resolve(base(), href) }
            .getOrElse {
                warn(ParseWarning.Code.UNRESOLVABLE_LINK)
                null
            }
    }

    fun pushXmlBase(xmlBase: String?) {
        val merged = if (xmlBase.isNullOrBlank()) {
            base()
        } else {
            runCatching { resolver.resolve(base(), xmlBase) }.getOrDefault(base())
        }
        baseStack.add(merged)
    }

    fun popXmlBase() {
        if (baseStack.size > 1) baseStack.removeAt(baseStack.size - 1)
    }

    fun checkDepth() {
        if (depth > OpdsBudgets.MAX_NESTING_DEPTH) {
            throw StructuralAbort(OpdsRejection.TooDeep())
        }
    }

    fun recountEntry() {
        entryCount++
        if (entryCount > OpdsBudgets.MAX_ITEMS_PER_RESPONSE) {
            throw StructuralAbort(OpdsRejection.TooManyItems())
        }
    }

    fun warn(code: ParseWarning.Code, path: String? = null) {
        if (warnings.size < WARNING_BUDGET) warnings.add(ParseWarning(code, path))
    }

    fun assemble(): OpdsParseResult {
        if (rootIsEntry) {
            val standalone = entries.singleOrNull()
                ?: return OpdsParseResult.Rejected(OpdsRejection.Malformed("standalone entry produced no content"))
            return OpdsParseResult.Document(
                OpdsPublicationDocument(
                    publication = standalone,
                    self = selfLink,
                    effectiveResponseUrl = effectiveResponseUrl,
                    warnings = warnings,
                ),
            )
        }
        // Section split by acquisition-link presence (plan §2.2: "Treat kind
        // as a hint, not truth. Classify entry actions from relations and
        // document structure").
        val publications = entries.filter { it.acquisitionLinkCount > 0 }
        val navigation = entries.filter { it.acquisitionLinkCount == 0 }
        return OpdsParseResult.Document(
            OpdsFeedDocument(
                metadata = OpdsFeedMetadata(
                    title = feedTitle ?: OpdsText("Untitled catalogue"),
                    identifier = feedIdentity?.let { OpdsIdentity(it, OpdsIdentity.Kind.NOMINAL) },
                    updated = feedUpdated,
                    modified = feedUpdated,
                    rights = feedRights,
                    authors = feedAuthors,
                ),
                navigation = navigation,
                publications = publications,
                groups = emptyList(),
                facets = emptyList(), // OPDS1 facet extensions: see the phase report ambiguity note
                pagination = OpdsPagination(
                    first = firstLink,
                    next = nextLink,
                    previous = prevLink,
                    last = lastLink,
                ),
                search = searchOffer,
                self = selfLink,
                up = upLinks,
                effectiveResponseUrl = effectiveResponseUrl,
                warnings = warnings,
            ),
        )
    }

    companion object {
        const val WARNING_BUDGET = 100
    }
}

private class StructuralAbort(val rejection: OpdsRejection) : IllegalStateException(rejection.note)

// ── walker ────────────────────────────────────────────────────────────────

/** One visitor owns one element; the walker owns every piece of depth bookkeeping. */
private interface Visitor {
    /**
     * START_ELEMENT handling. Returns the visitor for the element's
     * children, or null when the subtree is ignored (consumed by the
     * walker; budgets still enforced).
     */
    fun descend(name: String, attributes: Map<String, String>): Visitor?

    /** TEXT/CDSECT/ENTITY_REF content of the element itself. */
    fun text(text: String, typeName: String)

    /** END_ELEMENT for the element this visitor owns. */
    fun end(name: String)
}

/**
 * Bounded walker: DOCDECL stop, depth/item budgets, xml:base composition
 * at every visited START, attribute maps materialized inside
 * START_ELEMENT events only (Phase 0 record: accessors go stale after
 * END_ELEMENT), text as TEXT+CDSECT+ENTITY_REF concatenation.
 *
 * `skipDepth` consumes ignored subtrees without invoking visitors; their
 * START events still count against the depth budget.
 */
private class Opds1XmlWalker(private val state: ParseState) {

    fun walk(reader: XmlReader, root: Visitor) {
        var current: Visitor = root
        val visiting = ArrayDeque<Visitor>()
        var skipDepth = 0

        fun attributeMap(reader: XmlReader): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (index in 0 until reader.attributeCount) {
                val namespace = reader.getAttributeNamespace(index)
                val localName = reader.getAttributeLocalName(index)
                val key = if (namespace == XML_NAMESPACE) "xml:$localName" else localName
                if (key !in out) out[key] = reader.getAttributeValue(index).orEmpty()
            }
            return out
        }

        while (reader.hasNext()) {
            when (val typeName = reader.next().name) {
                "DOCDECL", "DTD" -> {
                    // Phase 1 REQUIRED mitigation: stop before any entity
                    // text can be read (Phase 0 record).
                    throw StructuralAbort(OpdsRejection.DocumentTypeDeclarationRejected())
                }
                "START_ELEMENT" -> {
                    state.depth++ // every element counts structurally
                    state.checkDepth()
                    if (skipDepth > 0) {
                        skipDepth++
                        continue
                    }
                    val attributes = attributeMap(reader)
                    val child = current.descend(reader.localName.lowercase(), attributes)
                    if (child == null) {
                        skipDepth = 1
                    } else {
                        state.pushXmlBase(attributes["xml:base"])
                        state.languageStack.add(if ("xml:lang" in attributes) attributes["xml:lang"] else state.languageStack.last())
                        visiting.addLast(current)
                        current = child
                    }
                }
                "END_ELEMENT" -> {
                    state.depth--
                    if (skipDepth > 0) {
                        skipDepth--
                        continue
                    }
                    current.end(reader.localName.lowercase())
                    state.popXmlBase()
                    state.languageStack.removeAt(state.languageStack.lastIndex)
                    val parent = visiting.removeLastOrNull()
                        ?: return // the root element is complete; the rest is trailing whitespace
                    current = parent
                }
                "TEXT", "CDSECT", "ENTITY_REF" -> {
                    if (skipDepth > 0) continue
                    current.text(reader.text, typeName)
                }
                else -> {} // START_DOCUMENT, END_DOCUMENT, whitespace, comments, PIs
            }
        }
    }

    companion object {
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    }
}

// ── visitors ──────────────────────────────────────────────────────────────

/** Root: accepts exactly one `feed` or `entry` element; anything else fails. */
private class RootVisitor(
    private val state: ParseState,
    private val mediaTypeParser: SeparatedMediaTypeParser,
) : Visitor {

    override fun descend(name: String, attributes: Map<String, String>): Visitor? {
        if (descended) return null
        descended = true
        return when (name) {
            "feed" -> FeedBodyVisitor(state, mediaTypeParser)
            "entry" -> {
                state.rootIsEntry = true
                EntryBodyVisitor(state, standalone = true, mediaTypeParser = mediaTypeParser)
            }
            else -> throw StructuralAbort(OpdsRejection.NotACatalogue("root element is not a feed or entry"))
        }
    }

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) = Unit

    private var descended = false
}

/** `<feed>` body: metadata, declared topological links, entries. */
private class FeedBodyVisitor(
    private val state: ParseState,
    private val mediaTypeParser: SeparatedMediaTypeParser,
) : Visitor {

    override fun descend(name: String, attributes: Map<String, String>): Visitor? = when (name) {
        "entry" -> {
            state.recountEntry()
            EntryBodyVisitor(state, standalone = false, mediaTypeParser = mediaTypeParser)
        }
        "id" -> ScalarVisitor { text -> if (state.feedIdentity == null) state.feedIdentity = text }
        "title" -> ScalarVisitor { text -> if (state.feedTitle == null) state.feedTitle = state.tagged(text) }
        "updated" -> ScalarVisitor { text -> if (state.feedUpdated == null) state.feedUpdated = text }
        "rights" -> ScalarVisitor { text -> if (state.feedRights == null) state.feedRights = state.tagged(text) }
        "author" -> ContributorVisitor(state.feedAuthors, state)
        "link" -> FeedLinkVisitor(state, mediaTypeParser, attributes)
        else -> {
            // Bounded-unknown OPDS1 extensions (opensearch:, dc:, app: …):
            // recorded, ignored (plan §4).
            state.warn(ParseWarning.Code.UNKNOWN_EXTENSION_IGNORED, "feed/$name")
            null
        }
    }

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) = Unit
}

/** Collects a scalar text value; nested markup contributes its text too. */
private class ScalarVisitor(
    private val finalize: (String) -> Unit,
) : Visitor {
    private val buffer = StringBuilder()
    private var finalized = false

    override fun descend(name: String, attributes: Map<String, String>): Visitor? = ScalarNested(buffer)

    override fun text(text: String, typeName: String) {
        buffer.append(text)
    }

    override fun end(name: String) {
        if (!finalized) {
            finalized = true
            finalize(buffer.toString())
        }
    }
}

/** Nested markup inside a scalar position: contributes text, does not flush. */
private class ScalarNested(private val buffer: StringBuilder) : Visitor {
    override fun descend(name: String, attributes: Map<String, String>): Visitor? = ScalarNested(buffer)

    override fun text(text: String, typeName: String) {
        buffer.append(text)
    }

    override fun end(name: String) = Unit
}

/** Feed-level declared link: attached by relation (self/next/…/search/up).
 *  The element's own attributes are captured at construction (parent's START). */
private class FeedLinkVisitor(
    private val state: ParseState,
    mediaTypeParser: SeparatedMediaTypeParser,
    attributes: Map<String, String>,
) : Visitor {

    private val link = buildLink(attributes, state, mediaTypeParser)

    override fun descend(name: String, attributes: Map<String, String>): Visitor? =
        null // links have no meaningful children

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) {
        attachFeedLink(link, state)
    }
}


/**
 * `<entry>` body, feed child or standalone root. Assembly happens at the
 * element's end; identity fallback + MISSING_IDENTITY warning are applied
 * there (plan §3.2).
 */
private class EntryBodyVisitor(
    private val state: ParseState,
    val standalone: Boolean,
    private val mediaTypeParser: SeparatedMediaTypeParser,
    private val entryIndex: Int = if (standalone) 0 else state.entryCount - 1,
) : Visitor {

    private val assembler = EntryAssembler(state, entryIndex)

    override fun descend(name: String, attributes: Map<String, String>): Visitor? = when (name) {
        "id" -> ScalarVisitor { text -> if (assembler.rawId == null) assembler.rawId = text }
        "title" -> ScalarVisitor { text -> if (assembler.title.isBlank()) assembler.title = state.tagged(text) }
        "updated" -> ScalarVisitor { text -> if (assembler.updated == null) assembler.updated = text }
        "summary" -> ScalarVisitor { text -> if (assembler.summary == null) assembler.summary = state.tagged(text) }
        "rights" -> ScalarVisitor { text -> if (assembler.rights == null) assembler.rights = state.tagged(text) }
        "publisher" -> ScalarVisitor { text -> if (assembler.publisher == null) assembler.publisher = state.tagged(text) }
        "seller" -> ScalarVisitor { text -> if (assembler.seller == null) assembler.seller = state.tagged(text) }
        "lender" -> ScalarVisitor { text -> if (assembler.lender == null) assembler.lender = state.tagged(text) }
        "published", "issued" -> ScalarVisitor { text -> if (assembler.published == null) assembler.published = text }
        "language" -> ScalarVisitor { text -> if (text.isNotBlank()) assembler.languages.add(text) }
        "identifier" -> IdentifierVisitor(assembler, attributes)
        "author" -> ContributorVisitor(assembler.authors, state)
        "contributor" -> ContributorVisitor(assembler.otherContributors, state)
        "content" -> ContentVisitor(attributes, assembler, state)
        "link" -> EntryLinkVisitor(assembler, state, mediaTypeParser, attributes)
        else -> {
            state.warn(ParseWarning.Code.UNKNOWN_EXTENSION_IGNORED, "entry/$name")
            null
        }
    }

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) {
        state.entries.add(assembler.assemble())
    }
}

/** Atom contributor: name (+ optional uri). */
private class ContributorVisitor(
    private val sink: MutableCollection<OpdsContributor>,
    private val state: ParseState,
) : Visitor {
    private var localizedName = OpdsText("")
    private var uri: String? = null
    private var sawName = false

    override fun descend(name: String, attributes: Map<String, String>): Visitor? = when (name) {
        "name" -> ScalarVisitor { text -> if (!sawName) { localizedName = state.tagged(text); sawName = true } }
        "uri" -> ScalarVisitor { text -> uri = text }
        else -> null
    }

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) {
        if (name == "author" || name == "contributor") {
            if (name.isNotBlank() && sawName) {
                sink.add(OpdsContributor(name = localizedName, href = uri))
            }
        }
    }
}

/** dc:identifier with its optional scheme. */
private class IdentifierVisitor(
    private val assembler: EntryAssembler,
    attributes: Map<String, String>,
) : Visitor {
    private val buffer = StringBuilder()
    private val scheme: String? = attributes["scheme"]

    override fun descend(name: String, attributes: Map<String, String>): Visitor? =
        ScalarNested(buffer)

    override fun text(text: String, typeName: String) {
        buffer.append(text)
    }

    override fun end(name: String) {
        val raw = buffer.toString().trim()
        if (raw.isNotEmpty()) {
            assembler.identifiers.add(OpdsIdentifier(raw, scheme?.takeIf { it.isNotBlank() }))
        }
    }
}

/**
 * `<content>`: format from the type attribute; XHTML/HTML bodies are
 * reconstructed keeping tag order (entities resolve at the reader level,
 * Phase 0 record); CDATA HTML payloads stay verbatim.
 */
private class ContentVisitor(
    attributes: Map<String, String>,
    private val assembler: EntryAssembler,
    state: ParseState,
) : Visitor {
    private val language = state.tagged("", attributes).translations.keys.single()
    private val format: OpdsContent.Format = when (attributes["type"]?.trim()?.lowercase().orEmpty()) {
        "xhtml", "application/xhtml+xml" -> OpdsContent.Format.XHTML
        "html", "text/html" -> OpdsContent.Format.HTML
        else -> OpdsContent.Format.TEXT
    }
    private val body = StringBuilder()
    private var recorded = false

    override fun descend(name: String, attributes: Map<String, String>): Visitor? {
        body.append('<').append(name).append('>')
        return NestedInlineVisitor(body)
    }

    override fun text(text: String, typeName: String) {
        body.append(text)
    }

    override fun end(name: String) {
        if (!recorded) {
            recorded = true
            assembler.content = OpdsContent(
                format = format,
                body = OpdsText(if (format == OpdsContent.Format.TEXT) body.toString().trim() else body.toString(), language),
            )
        }
    }
}

/** Inline element inside a content body: open tag, text, closing tags preserved. */
private class NestedInlineVisitor(private val buffer: StringBuilder) : Visitor {
    override fun descend(name: String, attributes: Map<String, String>): Visitor? {
        buffer.append('<').append(name).append('>')
        return NestedInlineVisitor(buffer)
    }

    override fun text(text: String, typeName: String) {
        buffer.append(text)
    }

    override fun end(name: String) {
        buffer.append("</").append(name).append('>')
    }
}

/** Entry-level link: acquisition/image/related/self…, attached to the entry.
 *  The element's own attributes are captured at construction (parent's START). */
private class EntryLinkVisitor(
    private val assembler: EntryAssembler,
    private val state: ParseState,
    mediaTypeParser: SeparatedMediaTypeParser,
    attributes: Map<String, String>,
) : Visitor {

    private val link = buildLink(attributes, state, mediaTypeParser)

    override fun descend(name: String, attributes: Map<String, String>): Visitor? = null

    override fun text(text: String, typeName: String) = Unit

    override fun end(name: String) {
        assembler.registerLink(link)
    }
}

// ── assembly ───────────────────────────────────────────────────────────────

private class EntryAssembler(
    private val state: ParseState,
    val entryIndex: Int,
) {
    var rawId: String? = null
    var title: OpdsText = OpdsText("")
    var updated: String? = null
    var summary: OpdsText? = null
    var content: OpdsContent? = null
    var rights: OpdsText? = null
    var publisher: OpdsText? = null
    var seller: OpdsText? = null
    var lender: OpdsText? = null
    var published: String? = null
    val languages: MutableList<String> = mutableListOf()
    val authors: MutableList<OpdsContributor> = mutableListOf()
    val otherContributors: MutableList<OpdsContributor> = mutableListOf()
    val identifiers: MutableList<OpdsIdentifier> = mutableListOf()
    val links: MutableList<OpdsLink> = mutableListOf()
    val images: MutableList<OpdsImage> = mutableListOf()

    fun registerLink(link: OpdsLink) {
        val rel = link.relations.firstOrNull().orEmpty()
        val isImage = rel == IMAGE_RELATION || rel == THUMBNAIL_RELATION ||
            rel.endsWith("/image") || rel.endsWith("/image-thumbnail")
        if (isImage && images.size < IMAGE_BUDGET) {
            images.add(OpdsImage(link.rawHref, link.mediaType))
        }
        if (links.size < LINK_BUDGET) {
            links.add(link)
        }
    }

    fun assemble(): OpdsEntry {
        val identity = rawId?.let { OpdsIdentity(it, OpdsIdentity.Kind.NOMINAL) }
            ?: OpdsIdentity.documentScoped(
                fnv1aHex("entry|$entryIndex|$title|$updated|$summary"),
                note = "no feed-declared identity; valid only within this document",
            ).also {
                state.warn(ParseWarning.Code.MISSING_IDENTITY, "entry[$entryIndex]")
            }

        return OpdsEntry(
            identity = identity,
            title = title,
            updated = updated,
            authors = authors,
            otherContributors = otherContributors,
            languages = languages,
            summary = summary?.takeIf { it.isNotBlank() },
            content = content?.takeIf { it.body.isNotBlank() },
            rights = rights?.takeIf { it.isNotBlank() },
            publisher = publisher?.takeIf { it.isNotBlank() },
            published = published,
            // §11.7 telling line: year text as the catalogue declares it.
            year = yearOf(published),
            identifiers = identifiers,
            images = images,
            links = links,
            // §11.7: the parser never fabricates edition labels; the
            // catalogue's own text stays in summary/title for the mapping.
            editionLabel = null,
            seller = seller?.takeIf { it.isNotBlank() },
            lender = lender?.takeIf { it.isNotBlank() },
        )
    }

    private fun yearOf(published: String?): String? {
        val digits = published?.takeWhile { it.isDigit() }.orEmpty()
        return if (digits.length == 4) digits else null
    }
}

// ── link building ─────────────────────────────────────────────────────────


private fun boundedExtras(map: Map<String, String>): Map<String, String> =
    map.entries.take(EXTRA_BUDGET).associate { (key, value) -> key to value.take(EXTRA_VALUE_CHARS) }

private fun buildLink(
attributes: Map<String, String>,
state: ParseState,
mediaTypeParser: SeparatedMediaTypeParser,
): OpdsLink {
// Links are constructed during descend(), before the walker's base push.
state.pushXmlBase(attributes["xml:base"])
try {
val href = attributes["href"].orEmpty()
val relations = attributes["rel"].orEmpty().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
return OpdsLink(
    rawHref = href,
    resolvedHref = state.resolve(href),
    isTemplate = href.contains('{'),
    effectiveBaseUri = state.base(),
    relations = relations,
    mediaType = mediaTypeParser.parse(attributes["type"]),
    title = attributes["title"]?.takeIf { it.isNotBlank() }?.let { state.tagged(it, attributes) },
    extras = attributes
        .filterKeys { it !in KNOWN_LINK_ATTRIBUTES && it != "xml:base" }
        .takeIf { it.isNotEmpty() }
        ?.let { boundedExtras(it) }
        ?: emptyMap(),
)
} finally {
    state.popXmlBase()
}
}

private fun attachFeedLink(link: OpdsLink, state: ParseState) {
when {
    "self" in link.relations -> if (state.selfLink == null) state.selfLink = link
    "next" in link.relations -> state.nextLink = link
    "previous" in link.relations || "prev" in link.relations -> state.prevLink = link
    "first" in link.relations -> state.firstLink = link
    "last" in link.relations -> state.lastLink = link
    "up" in link.relations -> state.upLinks.add(link)
    "start" in link.relations -> {
        // OPDS1's `start` is the navigation root pointer; the normalized model
        // keeps it among the parent-navigation links (recorded in the phase
        // ambiguity notes — no dedicated start field for v1).
        state.upLinks.add(link)
    }
    "search" in link.relations -> {
        if (state.searchOffer == null &&
            link.mediaType?.mediaRange?.endsWith("/opensearchdescription+xml") == true
        ) {
            state.searchOffer = OpdsSearchOffer(link, OpdsSearchOffer.Kind.OPEN_SEARCH_DESCRIPTOR)
        }
    }
}
}

private fun fnv1aHex(seed: String): String {
var hash = 0x811C9DC5u
for (character in seed) {
    hash = hash xor ((character.code and 0xFF).toUInt())
    hash *= 0x01000193u
}
    return hash.toString(16).padStart(8, '0')
}

private const val WARNING_BUDGET = 100
private const val IMAGE_BUDGET = 50
private const val LINK_BUDGET = 500
private const val EXTRA_BUDGET = 20
private const val EXTRA_VALUE_CHARS = 200
private const val IMAGE_RELATION = "http://opds-spec.org/image"
private const val THUMBNAIL_RELATION = "http://opds-spec.org/image-thumbnail"
private val KNOWN_LINK_ATTRIBUTES = setOf("href", "rel", "type", "title")
