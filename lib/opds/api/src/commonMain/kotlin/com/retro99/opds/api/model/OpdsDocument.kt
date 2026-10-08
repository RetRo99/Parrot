package com.retro99.opds.api.model

/**
 * A parsed OPDS response (plan §3.2): a navigation/acquisition feed or a
 * standalone publication, holding everything the rest of the app needs for
 * browsing, provenance, and the design's requirements (§11.7).
 */
sealed interface OpdsDocument {
    val warnings: List<ParseWarning>

    /**
     * The effective response URL (after redirects) — the base against which
     * all relative links in this document resolve (plan §4).
     */
    val effectiveResponseUrl: String

    /**
     * The document's own `self` link when advertised (identity metadata,
     * plan §4: "a feed's self is useful identity metadata, but must not
     * silently change the base for all of its relative links").
     */
    val self: OpdsLink?

    /**
     * The navigation/listing context the invoker reached this document
     * through (§11.7 provenance). Protocol-internal: null unless set by the
     * caller directly after a navigation act.
     */
    val referencedBy: OpdsLink?
}

/** A feed document; sections are only present when the catalogue supplies them. */
data class OpdsFeedDocument(
    val metadata: OpdsFeedMetadata,
    /** Navigation entries (folders); a facet link/group link is not one of these. */
    val navigation: List<OpdsEntry>,
    /** Acquisition/publication entries, in catalogue order. */
    val publications: List<OpdsEntry>,
    val groups: List<OpdsGroup>,
    /** Facet groups; each option is (optionally) countable and "active" (§11.7). */
    val facets: List<OpdsFacetGroup>,
    val pagination: OpdsPagination,
    /** Advertised search, if the document offers one (OPDS2 template or OPDS1 descriptor link). */
    val search: OpdsSearchOffer? = null,
    override val self: OpdsLink? = null,
    val up: List<OpdsLink> = emptyList(),
    override val effectiveResponseUrl: String,
    override val referencedBy: OpdsLink? = null,
    override val warnings: List<ParseWarning> = emptyList(),
) : OpdsDocument

/** A standalone publication document (OPDS1 full entry / OPDS2 publication). */
data class OpdsPublicationDocument(
    val publication: OpdsEntry,
    override val self: OpdsLink? = null,
    override val effectiveResponseUrl: String,
    override val referencedBy: OpdsLink? = null,
    override val warnings: List<ParseWarning> = emptyList(),
) : OpdsDocument

data class OpdsFeedMetadata(
    val title: OpdsText,
    val identifier: OpdsIdentity?,
    val updated: String? = null,
    val authors: List<OpdsContributor> = emptyList(),
    val language: String? = null,
    /** The catalogue's rights line, shown verbatim in the design's Rights row. */
    val rights: OpdsText? = null,
    /** The feed's Dublin-Core or OPDS2 `modified`/`updated` — provenance change signal (§10.0). */
    val modified: String? = null,
)

/** The feed's own topology of links (plan §2.2: "advertised links only"). */
data class OpdsPagination(
    val first: OpdsLink? = null,
    val next: OpdsLink? = null,
    val previous: OpdsLink? = null,
    val last: OpdsLink? = null,
) {
    val hasPagination: Boolean get() = first != null || next != null || previous != null || last != null
}

data class OpdsGroup(
    val title: OpdsText,
    /** Links describing the group (its own self/reference navigation link). */
    val links: List<OpdsLink>,
    val publications: List<OpdsEntry>,
    val navigation: List<OpdsEntry>,
)

data class OpdsFacetGroup(
    /** The facet's display name (design requirement 8, §11.7). */
    val name: OpdsText?,
    /** Options in catalogue order. */
    val options: List<OpdsFacetOption>,
    /** The catalogue-declared "all" option when present. */
    val allOption: OpdsFacetOption? = null,
)

data class OpdsFacetOption(
    val title: OpdsText?,
    val link: OpdsLink,
    val active: Boolean = false,
    /** Category counts when the catalogue advertises them. */
    val count: Long? = null,
)

/**
 * Advertised search. OPDS1 feeds link an OpenSearch description document;
 * OPDS2 feeds advertise a URI template directly. Expansion is a separate
 * (testable) step; the template here is raw.
 */
data class OpdsSearchOffer(
    val link: OpdsLink,
    val kind: Kind,
) {
    enum class Kind { URI_TEMPLATE, OPEN_SEARCH_DESCRIPTOR }
}
