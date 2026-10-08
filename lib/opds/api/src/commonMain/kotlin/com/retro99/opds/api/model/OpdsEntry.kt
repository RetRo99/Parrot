package com.retro99.opds.api.model

/**
 * One catalogued item in a feed, normalized across OPDS 1.x and OPDS 2.0
 * (plan §3.2). All acquisition choices are preserved; nothing is deduplicated
 * by title, and no format inference happens here (classifying is a separate
 * contract's job, §5.1).
 */
data class OpdsEntry(
    val identity: OpdsIdentity,
    val title: OpdsText,
    val updated: String? = null,

    val authors: List<OpdsContributor> = emptyList(),
    val otherContributors: List<OpdsContributor> = emptyList(),
    val languages: List<String> = emptyList(),
    val summary: OpdsText? = null,
    val content: OpdsContent? = null,
    val rights: OpdsText? = null,
    val publisher: OpdsText? = null,
    val published: String? = null,
    /** Dublin Core issued (OPDS1) / metadata published year (OPDS2) text, for the design's telling line (§11.7). */
    val year: String? = null,
    val identifiers: List<OpdsIdentifier> = emptyList(),
    val images: List<OpdsImage> = emptyList(),

    /** All acquisition links, first-come as the catalogue declares them. */
    val links: List<OpdsLink> = emptyList(),

    /**
     * Label the catalogue itself provides for this edition when the feed
     * distinguishes editions (design pass-3: "titles are the catalogue's
     * labels, unclamped"). The parser never fabricates one; the composed UI
     * fallback ("EPUB file 1" / "Edition 1") is Phase 2 mapping.
     */
    val editionLabel: OpdsText? = null,
) {
    val acquisitionLinks: List<OpdsLink> get() = links.filter { it.hasAcquisitionRelation() }

    /** For design's telling line (§11.7, pass-3 engineering note). */
    val acquisitionLinkCount: Int get() = acquisitionLinks.size
}
