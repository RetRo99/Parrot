package com.retro99.opds.api.model

/**
 * A normalized OPDS link (plan §3.2): the original href/template is kept as
 * written (templates must not be prematurely normalized as concrete URLs);
 * every other aspect is parsed.
 */
data class OpdsLink(
    /** As written by the feed: a reference to resolve or an RFC 6570/OPDS template. */
    val rawHref: String,

    /**
     * The RFC 3986-resolved URL of the reference against the effective
     * response URL plus any inherited xml:base (plan §4). Templates are NOT
     * resolved prematurely (`resolvedHref == null` while `isTemplate`).
     */
    val resolvedHref: String? = null,

    /** true when [rawHref] is a template (a `{...}` expression occurs in it). */
    val isTemplate: Boolean = false,

    /** Normalized relations; standard ones as the OPDS short name (e.g. "next"). */
    val relations: List<String>,

    val mediaType: OpdsMediaType? = null,

    val title: OpdsText? = null,

    /** Declared byte length, when provided. */
    val lengthBytes: Long? = null,

    val price: OpdsPrice? = null,

    /** OPDS2 indirect acquisition tree ("properties.indirectAcquisition"). */
    val indirectAcquisition: OpdsIndirectAcquisition? = null,

    /** Bounded extension properties (OPDS2 `properties` / OPDS1 unknown attributes). */
    val extras: Map<String, String> = emptyMap(),
) {
    /** Standard + documented alias relations for acquisition (plan §2.2). */
    val acquisitionRelations: Set<String> get() = relations.filterToRelations()

    fun hasAcquisitionRelation(): Boolean = ACQUISITION_RELATIONS.intersect(relations.toSet()).isNotEmpty()

    /** True when this link advertises a direct-open EPUB payload (§5.1). */
    fun isEpub(): Boolean {
        val type = mediaType ?: return false
        return type.mainType == "application" && type.subType == "epub+zip" && type.parameter("indirect") == null
    }

    companion object {
        val ACQUISITION_RELATIONS = setOf(
            "http://opds-spec.org/acquisition", // the OPDS1 relation
            "acquisition", // common OPDS2 morph
            "open-access",
            "http://opds-spec.org/acquisition/open-access",
            // OPDS 2 aliases, per §2.2 of the plan:
            "download",
            "buy", "borrow", "preview", "subscribe",
            "http://opds-spec.org/acquisition/buy",
            "http://opds-spec.org/acquisition/borrow",
            "http://opds-spec.org/acquisition/sample",
            "http://opds-spec.org/acquisition/subscribe",
        )
    }
}

private fun List<String>.filterToRelations(): Set<String> =
    filter { OpdsLink.ACQUISITION_RELATIONS.contains(it) }.toSet()

data class OpdsPrice(val value: Double, val currency: String)

/** OPDS2 `properties.indirectAcquisition` subtree (plan §2.2). */
data class OpdsIndirectAcquisition(
    val mediaType: OpdsMediaType?,
    val children: List<OpdsIndirectAcquisition>,
)

data class OpdsImage(val href: String, val mediaType: OpdsMediaType?, val width: Int? = null, val height: Int? = null)

data class OpdsContributor(
    val name: OpdsText,
    val href: String? = null,
    /** e.g. author/translator/editor (OPDS2 role, OPDS1 relayed edition labels are kept in entries). */
    val role: String? = null,
)

/** Description content with retained format (plan §4). */
data class OpdsContent(val format: Format, val body: OpdsText) {
    enum class Format { TEXT, HTML, XHTML }
}

/** ISBN-style identifiers. Never used alone to derive identity (plan §3.2). */
data class OpdsIdentifier(val raw: String, val scheme: String? = null)
