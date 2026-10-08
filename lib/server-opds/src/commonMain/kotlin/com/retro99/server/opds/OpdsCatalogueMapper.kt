package com.retro99.server.opds

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.acquisition.DefaultAcquisitionClassifier
import com.retro99.server.api.*

internal class OpdsCatalogueMapper(private val target: (String) -> CatalogueTarget) {
    private val classifier = DefaultAcquisitionClassifier()
    private fun OpdsText.map() = CatalogueText(translations.toMap())
    private fun OpdsMediaType.map() = CatalogueMediaType(mainType, subType, parameters.toMap())
    private fun OpdsIdentity.map() = CatalogueIdentity(raw, when (kind) {
        OpdsIdentity.Kind.NOMINAL -> CatalogueIdentity.Scope.Nominal
        OpdsIdentity.Kind.DOCUMENT_SCOPED_FALLBACK -> CatalogueIdentity.Scope.Document
        OpdsIdentity.Kind.PROVIDER_SCOPED_FALLBACK -> CatalogueIdentity.Scope.Provider
    }, note)
    private fun OpdsContributor.map() = CatalogueContributor(name.map(), href, role)
    private fun OpdsIndirectAcquisition.map(): CatalogueIndirectAcquisition = CatalogueIndirectAcquisition(mediaType?.map(), children.map { it.map() })
    private fun OpdsLink.map(base: String): CatalogueLink = CatalogueLink(rawHref, resolvedHref, isTemplate,
        effectiveBaseUri ?: base, relations.toList(), mediaType?.map(), title?.map(), lengthBytes,
        price?.let { CataloguePrice(it.value, it.currency) }, indirectAcquisition?.map(), extras.toMap(),
        resolvedHref?.takeUnless { isTemplate }?.let(target))
    private fun OpdsUnavailableReason.map() = when (this) {
        OpdsUnavailableReason.SOLD -> CatalogueUnavailableReason.Sold
        OpdsUnavailableReason.SUBSCRIPTION -> CatalogueUnavailableReason.Subscription
        OpdsUnavailableReason.BORROW -> CatalogueUnavailableReason.Borrow
        OpdsUnavailableReason.SAMPLE_ONLY -> CatalogueUnavailableReason.SampleOnly
        OpdsUnavailableReason.UNSUPPORTED_FORMAT -> CatalogueUnavailableReason.UnsupportedFormat
        OpdsUnavailableReason.PROTECTED -> CatalogueUnavailableReason.Protected
    }
    private fun OpdsAcquisitionAction.map(base: String): CatalogueAcquisitionAction = when (this) {
        is OpdsAcquisitionAction.Download -> CatalogueAcquisitionAction.Download(link.map(base))
        is OpdsAcquisitionAction.OpenProviderPage -> CatalogueAcquisitionAction.OpenProviderPage(link.map(base), reason.map())
        is OpdsAcquisitionAction.NotAvailable -> CatalogueAcquisitionAction.NotAvailable(reason.map())
    }
    private fun OpdsEntry.map(base: String): CataloguePublication {
        val acquisition = classifier.classify(this)
        return CataloguePublication(identity.map(), title.map(), updated, authors.map { it.map() }, otherContributors.map { it.map() },
            languages.toList(), summary?.map(), content?.let { CatalogueDescription(when (it.format) {
                OpdsContent.Format.TEXT -> CatalogueDescription.Format.Text
                OpdsContent.Format.HTML -> CatalogueDescription.Format.Html
                OpdsContent.Format.XHTML -> CatalogueDescription.Format.Xhtml
            }, it.body.map()) }, rights?.map(), publisher?.map(), published, year,
            identifiers.map { CatalogueIdentifier(it.raw, it.scheme) }, images.map { CatalogueImage(it.href, it.mediaType?.map(), it.width, it.height) },
            links.map { it.map(base) }, editionLabel?.map(), seller?.map(), lender?.map(),
            classifier.files(acquisitionLinks).map { CatalogueFileChoice(it.link.map(base), it.action.map(base), it.isOpenable, it.isDefault) },
            acquisition.action.map(base), acquisition.providerName?.map(), acquisition.unsupportedMediaType?.map())
    }
    private fun OpdsFacetOption.map(base: String) = CatalogueFacetOption(title?.map(), link.map(base), active, count)
    fun map(document: OpdsDocument, status: CatalogueFetchStatus): CatalogueDocument {
        val base = document.effectiveResponseUrl
        val warnings = document.warnings.map { CatalogueWarning(when (it.code) {
            ParseWarning.Code.MISSING_IDENTITY -> CatalogueWarning.Kind.MissingIdentity
            ParseWarning.Code.UNRESOLVABLE_LINK -> CatalogueWarning.Kind.UnresolvableLink
            ParseWarning.Code.UNKNOWN_EXTENSION_IGNORED -> CatalogueWarning.Kind.UnknownExtension
            ParseWarning.Code.MALFORMED_ITEM_SKIPPED -> CatalogueWarning.Kind.MalformedItem
            ParseWarning.Code.MEDIA_TYPE_NOT_PARSED -> CatalogueWarning.Kind.InvalidMediaType
        }, it.elementPath) }
        return when (document) {
            is OpdsPublicationDocument -> CataloguePublicationDocument(document.publication.map(base), base, document.self?.map(base), document.referencedBy?.map(base), warnings, status)
            is OpdsFeedDocument -> CatalogueFeedDocument(
                document.metadata.let { CatalogueFeedMetadata(it.title.map(), it.identifier?.map(), it.updated, it.authors.map { it.map() }, it.language, it.rights?.map(), it.modified) },
                document.navigation.map { it.map(base) }, document.publications.map { it.map(base) },
                document.groups.map { CatalogueGroup(it.title.map(), it.links.map { it.map(base) }, it.publications.map { it.map(base) }, it.navigation.map { it.map(base) }) },
                document.facets.map { CatalogueFacetGroup(it.name?.map(), it.options.map { it.map(base) }, it.allOption?.map(base)) },
                document.pagination.let { CataloguePagination(it.first?.map(base), it.next?.map(base), it.previous?.map(base), it.last?.map(base)) },
                document.search?.let { CatalogueSearchOffer(it.link.map(base), when (it.kind) {
                    OpdsSearchOffer.Kind.URI_TEMPLATE -> CatalogueSearchOffer.Kind.UriTemplate
                    OpdsSearchOffer.Kind.OPEN_SEARCH_DESCRIPTOR -> CatalogueSearchOffer.Kind.Descriptor
                }) }, document.up.map { it.map(base) }, base, document.self?.map(base), document.referencedBy?.map(base), warnings, status)
        }
    }
}
