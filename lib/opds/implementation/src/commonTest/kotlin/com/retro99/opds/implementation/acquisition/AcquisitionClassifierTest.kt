package com.retro99.opds.implementation.acquisition

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.acquisition.DefaultAcquisitionClassifier
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import kotlin.test.*

/** One test per decision-table row; relation variants exercise both protocol versions. */
class AcquisitionClassifierTest {
    private val classifier = DefaultAcquisitionClassifier()
    private val types = SeparatedMediaTypeParser()
    private fun link(rel: String, type: String? = "application/epub+zip", tree: OpdsIndirectAcquisition? = null) =
        OpdsLink("/misleading.epub", "https://example.org/misleading.epub", relations = listOf(rel), mediaType = types.parse(type), indirectAcquisition = tree)
    private fun tree(type: String, vararg children: OpdsIndirectAcquisition) = OpdsIndirectAcquisition(types.parse(type), children.toList())
    private fun downloads(vararg relations: String) = relations.forEach {
        val link = link(it)
        assertEquals(OpdsAcquisitionAction.Download(link), classifier.classify(link))
    }
    private fun unavailable(reason: OpdsUnavailableReason, vararg relations: String, type: String? = "application/epub+zip") = relations.forEach {
        assertEquals(OpdsAcquisitionAction.NotAvailable(reason), classifier.classify(link(it, type)))
    }
    @Test fun direct_epub_download() = downloads("download")
    @Test fun generic_acquisition_epub() = downloads("acquisition", "http://opds-spec.org/acquisition")
    @Test fun open_access_epub() = downloads("open-access", "http://opds-spec.org/acquisition/open-access")
    @Test fun sample_is_never_complete() = unavailable(OpdsUnavailableReason.SAMPLE_ONLY, "sample", "preview", "http://opds-spec.org/acquisition/sample")
    @Test fun buy_epub_is_sold() = unavailable(OpdsUnavailableReason.SOLD, "buy", "http://opds-spec.org/acquisition/buy")
    @Test fun borrow_epub_needs_lending() = unavailable(OpdsUnavailableReason.BORROW, "borrow", "http://opds-spec.org/acquisition/borrow")
    @Test fun subscribe_epub_is_sold() = unavailable(OpdsUnavailableReason.SOLD, "subscribe", "http://opds-spec.org/acquisition/subscribe")
    @Test fun html_buy_opens_provider_page() {
        for (rel in listOf("buy", "http://opds-spec.org/acquisition/buy")) {
            val link = link(rel, "text/html")
            assertEquals(OpdsAcquisitionAction.OpenProviderPage(link, OpdsUnavailableReason.SOLD), classifier.classify(link))
        }
    }
    @Test fun html_borrow_opens_provider_page() {
        val link = link("borrow", "text/html")
        assertEquals(OpdsAcquisitionAction.OpenProviderPage(link, OpdsUnavailableReason.BORROW), classifier.classify(link))
    }
    @Test fun html_subscription_opens_provider_page() {
        val link = link("subscribe", "text/html")
        assertEquals(OpdsAcquisitionAction.OpenProviderPage(link, OpdsUnavailableReason.SOLD), classifier.classify(link))
    }
    @Test fun html_wrapper_is_not_direct_epub() {
        val link = link("acquisition", "text/html", tree("text/html", tree("application/epub+zip")))
        assertEquals(OpdsAcquisitionAction.OpenProviderPage(link, OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link))
    }
    @Test fun licence_wrapper_is_protected() {
        val link = link("acquisition", "application/vnd.readium.lcp.license+json", tree("application/epub+zip"))
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.PROTECTED), classifier.classify(link))
    }
    @Test fun archive_wrapper_is_not_direct_epub() {
        val link = link("download", "application/zip", tree("application/epub+zip"))
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link))
    }
    @Test fun epub_outer_with_indirect_tree_is_not_direct() {
        val link = link("download", tree = tree("application/epub+zip"))
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link))
    }
    @Test fun nested_licence_is_protected() {
        val link = link("download", tree = tree("text/html", tree("application/vnd.adobe.adept+xml", tree("application/epub+zip"))))
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.PROTECTED), classifier.classify(link))
    }
    @Test fun pdf_is_not_a_supported_acquisition() = unavailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT, "download", type = "application/pdf")
    @Test fun mobi_is_not_a_supported_acquisition() = unavailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT, "acquisition", type = "application/x-mobipocket-ebook")
    @Test fun missing_type_is_not_inferred_from_extension() = unavailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT, "download", type = null)
    @Test fun unknown_relation_cannot_download() = unavailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT, "mystery")
    @Test fun no_relation_cannot_download() {
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link("download").copy(relations = emptyList())))
    }
    @Test fun restrictive_relation_wins_over_download() {
        for (relations in listOf(listOf("download", "preview"), listOf("preview", "download"))) {
            assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.SAMPLE_ONLY), classifier.classify(link("download").copy(relations = relations)))
        }
    }
    @Test fun client_cannot_open_epub() {
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link("download"), OpdsClientCapabilities(emptySet())))
    }
    @Test fun media_parameters_do_not_hide_protection() {
        unavailable(OpdsUnavailableReason.PROTECTED, "download", type = "application/epub+zip;profile=lcp")
    }
    @Test fun templated_acquisitions_are_not_ready_downloads() {
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link("download").copy(rawHref = "{file}", resolvedHref = null, isTemplate = true)))
    }
    @Test fun several_epubs_default_to_first_openable_in_catalogue_order() {
        val links = listOf(link("download", "application/pdf"), link("preview"), link("download").copy(rawHref = "/first"), link("open-access").copy(rawHref = "/second"))
        val choices = classifier.files(links)
        assertEquals(links, choices.map { it.link })
        assertEquals(listOf(false, false, true, true), choices.map { it.isOpenable })
        assertEquals(listOf(false, false, true, false), choices.map { it.isDefault })
    }
    @Test fun no_openable_files_has_no_default() {
        assertTrue(classifier.files(listOf(link("buy"), link("download", "application/pdf"))).none { it.isDefault || it.isOpenable })
        assertTrue(classifier.files(emptyList()).isEmpty())
    }
}
