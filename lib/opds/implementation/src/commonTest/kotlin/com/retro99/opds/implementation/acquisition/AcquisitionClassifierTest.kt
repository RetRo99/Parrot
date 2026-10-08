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
    @Test fun subscribe_epub_needs_subscription() = unavailable(OpdsUnavailableReason.SUBSCRIPTION, "subscribe", "http://opds-spec.org/acquisition/subscribe")
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
        assertEquals(OpdsAcquisitionAction.OpenProviderPage(link, OpdsUnavailableReason.SUBSCRIPTION), classifier.classify(link))
    }
    @Test fun html_wrapper_is_not_direct_epub() {
        val link = link("acquisition", "text/html", tree("text/html", tree("application/epub+zip")))
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link))
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
    @Test fun broader_reader_capabilities_do_not_enable_other_acquisition_handlers() {
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), classifier.classify(link("download", "application/pdf"), OpdsClientCapabilities(setOf("application/pdf", "application/epub+zip"))))
    }
    @Test fun fixture_choices_match_policy_without_changing_catalogue_order() {
        val parser = com.retro99.opds.implementation.ParserFactory.opdsParser()
        val xml = com.retro99.opds.implementation.fixtures.readFixtureText("opds/opds1/verses-acquisition.xml")
        val feed = (parser.parse(OpdsPayload("application/atom+xml", xml.encodeToByteArray()), "https://example.org/root") as OpdsParseResult.Document).document as OpdsFeedDocument
        val files = classifier.files(feed.publications.first().acquisitionLinks)
        assertEquals(listOf(true, true, false), files.map { it.isOpenable })
        assertEquals(listOf(true, false, false), files.map { it.isDefault })
        val json = com.retro99.opds.implementation.fixtures.readFixtureText("opds/opds2/landscape.json")
        val book = (parser.parse(OpdsPayload("application/opds-publication+json", json.encodeToByteArray()), "https://example.org/root") as OpdsParseResult.Document).document as OpdsPublicationDocument
        val choices = classifier.files(book.publication.acquisitionLinks)
        assertTrue(choices.none { it.isOpenable || it.isDefault })
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.UNSUPPORTED_FORMAT), choices.first().action)
        assertEquals(OpdsUnavailableReason.SOLD, (choices.last().action as OpdsAcquisitionAction.OpenProviderPage).reason)
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
    private fun entry(links: List<OpdsLink>) = OpdsEntry(OpdsIdentity("urn:book", OpdsIdentity.Kind.NOMINAL), OpdsText("Book"), links = links)
    @Test fun entry_reason_precedence_is_independent_of_file_order() {
        val relations = listOf("preview", "buy", "subscribe", "borrow", "download", "acquisition")
        val reasons = listOf(OpdsUnavailableReason.SAMPLE_ONLY, OpdsUnavailableReason.SOLD, OpdsUnavailableReason.SUBSCRIPTION, OpdsUnavailableReason.BORROW, OpdsUnavailableReason.PROTECTED, OpdsUnavailableReason.UNSUPPORTED_FORMAT)
        val links = relations.mapIndexed { i, rel -> link(rel, if (i == 4) "application/vnd.readium.lcp.license+json" else "application/pdf") }
        for (i in reasons.indices) {
            for (ordered in listOf(links.drop(i), links.drop(i).reversed())) {
                assertEquals(OpdsAcquisitionAction.NotAvailable(reasons[i]), classifier.classify(entry(ordered)).action)
            }
        }
    }
    @Test fun any_complete_download_suppresses_blocked_card() {
        val full = link("download")
        assertEquals(OpdsAcquisitionAction.Download(full), classifier.classify(entry(listOf(link("preview"), link("buy"), full))).action)
    }
    @Test fun provider_button_requires_entry_web_link_and_actionable_reason() {
        val web = link("alternate", "text/html")
        for (rel in listOf("preview", "buy", "subscribe", "borrow")) {
            val reason = (classifier.classify(link(rel)) as OpdsAcquisitionAction.NotAvailable).reason
            assertEquals(OpdsAcquisitionAction.OpenProviderPage(web, reason), classifier.classify(entry(listOf(link(rel), web))).action)
        }
        for (type in listOf("application/pdf", "application/vnd.readium.lcp.license+json")) {
            assertIs<OpdsAcquisitionAction.NotAvailable>(classifier.classify(entry(listOf(link("download", type), web))).action)
        }
    }
    @Test fun seller_lender_and_unsupported_type_are_exposed_without_fabrication() {
        val seller = OpdsText("Shop")
        val lender = OpdsText("Library")
        assertEquals(seller, classifier.classify(entry(listOf(link("buy"))).copy(seller = seller)).providerName)
        assertEquals(lender, classifier.classify(entry(listOf(link("borrow"))).copy(lender = lender)).providerName)
        val result = classifier.classify(entry(listOf(link("download", "application/pdf"))))
        assertNull(result.providerName)
        assertEquals("application/pdf", result.unsupportedMediaType?.mediaRange)
        assertNull(classifier.classify(entry(listOf(link("download", null)))).unsupportedMediaType)
    }
    @Test fun explicit_provider_metadata_is_parsed() {
        val json = """{"metadata":{"title":"Book","seller":{"name":{"en":"Shop","fr":"Boutique"}},"lender":"Library"},"links":[{"rel":"buy","href":"/buy","type":"text/html"}]}"""
        val book = ((com.retro99.opds.implementation.ParserFactory.opdsParser().parse(OpdsPayload("application/opds-publication+json", json.encodeToByteArray()), "https://example.org/root") as OpdsParseResult.Document).document as OpdsPublicationDocument).publication
        assertEquals(OpdsText(mapOf("en" to "Shop", "fr" to "Boutique")), book.seller)
        assertEquals(OpdsText("Library"), book.lender)
    }
    @Test fun sample_alias_is_retained_for_entry_precedence_but_never_downloaded() {
        assertEquals(OpdsAcquisitionAction.NotAvailable(OpdsUnavailableReason.SAMPLE_ONLY), classifier.classify(entry(listOf(link("sample")))).action)
    }
    @Test fun explicit_xml_seller_and_lender_keep_language_tags() {
        val xml = """<entry xmlns="http://www.w3.org/2005/Atom" xmlns:p="urn:provider" xml:lang="en"><id>urn:book</id><title>Book</title><p:seller>Shop</p:seller><p:lender xml:lang="fr">Bibliothèque</p:lender><link rel="http://opds-spec.org/acquisition/buy" href="/buy" type="text/html"/></entry>"""
        val book = ((com.retro99.opds.implementation.ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml", xml.encodeToByteArray()), "https://example.org/root") as OpdsParseResult.Document).document as OpdsPublicationDocument).publication
        assertEquals(OpdsText("Shop", "en"), book.seller)
        assertEquals(OpdsText("Bibliothèque", "fr"), book.lender)
    }
}
