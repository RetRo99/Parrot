package com.retro99.server.api

/** Normalized domain values, with no XML/JSON or protocol implementation types. */
data class CatalogueText(val translations: Map<String, String>)
data class CatalogueMediaType(val type: String, val subtype: String, val parameters: Map<String, String>)
data class CatalogueIdentity(val value: String, val scope: Scope, val note: String? = null) {
    enum class Scope { Nominal, Document, Provider }
}
data class CatalogueWarning(val kind: Kind, val elementPath: String?) {
    enum class Kind { MissingIdentity, UnresolvableLink, UnknownExtension, MalformedItem, InvalidMediaType }
}
data class CataloguePrice(val value: Double, val currency: String)
data class CatalogueIndirectAcquisition(val mediaType: CatalogueMediaType?, val children: List<CatalogueIndirectAcquisition>)
data class CatalogueLink(
    val rawHref: String,
    val resolvedHref: String?,
    val isTemplate: Boolean,
    val effectiveBaseUri: String,
    val relations: List<String>,
    val mediaType: CatalogueMediaType?,
    val title: CatalogueText?,
    val lengthBytes: Long?,
    val price: CataloguePrice?,
    val indirectAcquisition: CatalogueIndirectAcquisition?,
    val extras: Map<String, String>,
    val target: CatalogueTarget?,
)
data class CatalogueImage(val href: String, val mediaType: CatalogueMediaType?, val width: Int?, val height: Int?)
data class CatalogueContributor(val name: CatalogueText, val href: String?, val role: String?)
data class CatalogueDescription(val format: Format, val body: CatalogueText) {
    enum class Format { Text, Html, Xhtml }
}
data class CatalogueIdentifier(val value: String, val scheme: String?)
enum class CatalogueUnavailableReason { Sold, Subscription, Borrow, SampleOnly, UnsupportedFormat, Protected }
sealed interface CatalogueAcquisitionAction {
    data class Download(val link: CatalogueLink) : CatalogueAcquisitionAction
    data class OpenProviderPage(val link: CatalogueLink, val reason: CatalogueUnavailableReason) : CatalogueAcquisitionAction
    data class NotAvailable(val reason: CatalogueUnavailableReason) : CatalogueAcquisitionAction
}
data class CatalogueFileChoice(val link: CatalogueLink, val action: CatalogueAcquisitionAction, val isOpenable: Boolean, val isDefault: Boolean)
data class CataloguePublication(
    val identity: CatalogueIdentity,
    val title: CatalogueText,
    val updated: String?,
    val authors: List<CatalogueContributor>,
    val otherContributors: List<CatalogueContributor>,
    val languages: List<String>,
    val summary: CatalogueText?,
    val content: CatalogueDescription?,
    val rights: CatalogueText?,
    val publisher: CatalogueText?,
    val published: String?,
    val year: String?,
    val identifiers: List<CatalogueIdentifier>,
    val images: List<CatalogueImage>,
    val links: List<CatalogueLink>,
    val editionLabel: CatalogueText?,
    val seller: CatalogueText?,
    val lender: CatalogueText?,
    val acquisitionChoices: List<CatalogueFileChoice>,
    val acquisitionAction: CatalogueAcquisitionAction,
    val acquisitionProviderName: CatalogueText?,
    val unsupportedMediaType: CatalogueMediaType?,
    val subjects: List<CatalogueText> = emptyList(),
)
data class CatalogueFeedMetadata(val title: CatalogueText, val identifier: CatalogueIdentity?, val updated: String?, val authors: List<CatalogueContributor>, val language: String?, val rights: CatalogueText?, val modified: String?)
data class CataloguePagination(val first: CatalogueLink?, val next: CatalogueLink?, val previous: CatalogueLink?, val last: CatalogueLink?)
data class CatalogueGroup(val title: CatalogueText, val links: List<CatalogueLink>, val publications: List<CataloguePublication>, val navigation: List<CataloguePublication>)
data class CatalogueFacetOption(val title: CatalogueText?, val link: CatalogueLink, val active: Boolean, val count: Long?)
data class CatalogueFacetGroup(val name: CatalogueText?, val options: List<CatalogueFacetOption>, val allOption: CatalogueFacetOption?)
data class CatalogueSearchOffer(val link: CatalogueLink, val kind: Kind) {
    enum class Kind { UriTemplate, Descriptor }
}
/**
 * @param savedCopyAt set when the catalogue could not be reached and this is the copy saved
 *   at that time ("saved copy"); null for a page that came from the catalogue just now
 */
data class CatalogueFetchStatus(
    val checkedAt: Long,
    val fromCache: Boolean,
    val crossOriginPrivateNetwork: Boolean,
    val savedCopyAt: Long? = null,
) {
    val isSavedCopy: Boolean get() = savedCopyAt != null
}
sealed interface CatalogueDocument {
    val context: CatalogueTarget
    val responseUrl: String
    val self: CatalogueLink?
    val referencedBy: CatalogueLink?
    val warnings: List<CatalogueWarning>
    val fetchStatus: CatalogueFetchStatus
}
data class CatalogueFeedDocument(
    val metadata: CatalogueFeedMetadata,
    val navigation: List<CataloguePublication>,
    val publications: List<CataloguePublication>,
    val groups: List<CatalogueGroup>,
    val facets: List<CatalogueFacetGroup>,
    val pagination: CataloguePagination,
    val search: CatalogueSearchOffer?,
    val up: List<CatalogueLink>,
    override val responseUrl: String,
    override val self: CatalogueLink?,
    override val referencedBy: CatalogueLink?,
    override val warnings: List<CatalogueWarning>,
    override val fetchStatus: CatalogueFetchStatus,
    override val context: CatalogueTarget,
) : CatalogueDocument
data class CataloguePublicationDocument(
    val publication: CataloguePublication,
    override val responseUrl: String,
    override val self: CatalogueLink?,
    override val referencedBy: CatalogueLink?,
    override val warnings: List<CatalogueWarning>,
    override val fetchStatus: CatalogueFetchStatus,
    override val context: CatalogueTarget,
) : CatalogueDocument
