package com.retro99.catalogue.ui.publication

import com.retro99.catalogue.ui.browse.book
import com.retro99.catalogue.ui.browse.text
import com.retro99.server.api.*
import kotlin.test.*

class CatalogueBookModelsTest {
    @Test fun default_is_first_openable_across_editions_and_missing_labels_follow_catalogue_order() {
        val first = book("Book", files = 2, edition = "Illustrated edition, 1911")
        val unsupported = first.acquisitionChoices.first().let { it.copy(isOpenable = false, action = CatalogueAcquisitionAction.NotAvailable(CatalogueUnavailableReason.UnsupportedFormat)) }
        val second = book("Book", id = "second", files = 2)
        val groups = bookFileGroups(listOf(first.copy(acquisitionChoices = listOf(unsupported, first.acquisitionChoices[1])), second))
        assertEquals(listOf("Illustrated edition, 1911", null), groups.map { it.label })
        assertEquals(listOf("EPUB file 1", "EPUB file 2", "EPUB file 3", "EPUB file 4"), groups.flatMap { it.files }.map { it.label ?: "EPUB file ${it.ordinal}" })
        assertEquals(listOf(false, true, false, false), groups.flatMap { it.files }.map { it.best })
        assertFalse(groups.first().files.first().openable)
    }
    @Test fun catalogue_labels_are_preserved_without_clamping_and_selection_rejects_unopenable() {
        val publication = book("Book")
        val label = "EPUB3 (E-readers incl. Send-to-Kindle) — the complete illustrated edition"
        val choice = publication.acquisitionChoices.first().let { it.copy(link = it.link.copy(title = text(label))) }
        assertEquals(label, bookFileGroups(listOf(publication.copy(acquisitionChoices = listOf(choice)))).single().files.single().label)
    }
    @Test fun each_blocked_reason_has_the_design_button_policy_and_priority() {
        for (reason in CatalogueUnavailableReason.entries) {
            val p = book("Book", files = 0)
            val link = com.retro99.catalogue.ui.browse.link("provider", href = "https://provider.example/book")
            val blocked = bookBlocked(listOf(p.copy(acquisitionAction = CatalogueAcquisitionAction.OpenProviderPage(link, reason))))!!
            assertEquals(reason, blocked.reason)
            assertEquals(reason !in listOf(CatalogueUnavailableReason.Protected, CatalogueUnavailableReason.UnsupportedFormat), blocked.providerLink != null)
            assertNull(bookBlocked(listOf(p.copy(acquisitionAction = CatalogueAcquisitionAction.NotAvailable(reason))))!!.providerLink)
        }
        val reasons = listOf(CatalogueUnavailableReason.UnsupportedFormat, CatalogueUnavailableReason.Protected, CatalogueUnavailableReason.Borrow, CatalogueUnavailableReason.Subscription, CatalogueUnavailableReason.Sold, CatalogueUnavailableReason.SampleOnly)
        for (index in reasons.indices) {
            val publications = reasons.take(index + 1).map { book("Book", files = 0).copy(acquisitionAction = CatalogueAcquisitionAction.NotAvailable(it)) }
            assertEquals(reasons[index], bookBlocked(publications)!!.reason)
        }
        assertNull(bookBlocked(listOf(book("Book"))))
    }
}
