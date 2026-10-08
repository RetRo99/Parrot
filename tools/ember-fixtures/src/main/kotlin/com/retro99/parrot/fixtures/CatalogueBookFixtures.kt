package com.retro99.parrot.fixtures

import androidx.compose.runtime.Composable
import com.retro99.catalogue.ui.publication.*
import com.retro99.server.api.*

private fun text(value: String) = CatalogueText(mapOf("und" to value))
private fun link(label: String?, size: Long?, type: String = "epub+zip") = CatalogueLink("https://books.example/file", "https://books.example/file", false, "https://books.example/", listOf("download"), CatalogueMediaType("application", type, emptyMap()), label?.let(::text), size, null, null, emptyMap(), null)
private fun file(label: String?, size: Long?, openable: Boolean = true): CatalogueFileChoice {
    val link = link(label, size, if (openable) "epub+zip" else "vnd.amazon.ebook")
    return CatalogueFileChoice(link, if (openable) CatalogueAcquisitionAction.Download(link) else CatalogueAcquisitionAction.NotAvailable(CatalogueUnavailableReason.UnsupportedFormat), openable, false)
}

fun fixtureCatalogueBook() = CataloguePublication(
    identity = CatalogueIdentity("treasure-island", CatalogueIdentity.Scope.Nominal), title = text("Treasure Island"), updated = null,
    authors = listOf(CatalogueContributor(text("Robert Louis Stevenson"), null, null)), otherContributors = emptyList(), languages = listOf("en"),
    summary = null, content = CatalogueDescription(CatalogueDescription.Format.Html, text("<p>An old sea chart, a one-legged cook and a hunt for buried gold. Young Jim Hawkins sails for an island where nobody can be trusted.</p><p>First published as a serial in <i>Young Folks</i>, 1881–82.</p>")),
    rights = text("Public domain in the USA."), publisher = null, published = "1883", year = "1883", identifiers = emptyList(), images = emptyList(), links = emptyList(), editionLabel = null, seller = null, lender = null,
    acquisitionChoices = listOf(file("EPUB3 (E-readers incl. Send-to-Kindle)", 1_200_000), file("EPUB (older e-readers)", 600_000), file("EPUB, without pictures", null), file("Kindle", null, false)),
    acquisitionAction = CatalogueAcquisitionAction.Download(link(null, 1_200_000)), acquisitionProviderName = null, unsupportedMediaType = null,
    subjects = listOf(text("Adventure"), text("Pirates")),
)

@Composable
fun CatalogueBookBoard(view: String) {
    val book = fixtureCatalogueBook()
    val editions = when (view) {
        "editions" -> listOf(book.copy(acquisitionChoices = listOf(file("EPUB3 (E-readers incl. Send-to-Kindle)", 600_000), file("EPUB (older E-readers, no images, with a much longer description from the catalogue)", 400_000), file(null, null), file("Kindle", null, false))))
        "editionsGrouped" -> listOf(book.copy(editionLabel = text("Illustrated edition, 1911"), acquisitionChoices = listOf(file(null, 4_800_000), file(null, 1_000_000))), book.copy(identity = CatalogueIdentity("edition2", CatalogueIdentity.Scope.Nominal), acquisitionChoices = listOf(file("EPUB3 (E-readers incl. Send-to-Kindle)", 600_000), file("Kindle", null, false))))
        else -> listOf(book)
    }
    val groups = bookFileGroups(editions)
    val action = when (view) {
        "detailWait" -> BookMainAction.Waiting(2)
        "detailWaitOne" -> BookMainAction.Waiting(1)
        "detailDl", "detailIos" -> BookMainAction.Downloading(500_000, 1_200_000)
        "detailUnknown" -> BookMainAction.Downloading(1_400_000, null)
        "detailAdding" -> BookMainAction.Adding
        "detailDone" -> BookMainAction.Done("fixture-book", 1_000_000)
        "blocked", "blockedSold" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.Sold, null, "https://provider.example/book", null))
        "blockedSubscription" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.Subscription, null, "https://provider.example/book", null))
        "blockedBorrow" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.Borrow, null, "https://provider.example/book", null))
        "blockedSample" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.SampleOnly, null, "https://provider.example/book", null))
        "blockedFormat" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.UnsupportedFormat, null, null, "application/pdf"))
        "blockedProtected" -> BookMainAction.Blocked(BookBlocked(CatalogueUnavailableReason.Protected, null, null, null))
        else -> BookMainAction.Download(groups.first().files.first())
    }
    val state = androidx.compose.runtime.remember(view) { androidx.compose.runtime.mutableStateOf(CatalogueBookState("Project Gutenberg", book, action, groups, 1, chooseFile = view.startsWith("editions"))) }
    CatalogueBookContentScreen("fixture", state.value, CatalogueBookActions(onChooseFile = { ordinal ->
        if (state.value.files.any { it.ordinal == ordinal && it.openable }) state.value = state.value.copy(selectedOrdinal = ordinal)
    }, onCloseFiles = { state.value = state.value.copy(chooseFile = false) }), nowEpochMillis = 1_000_000, isIos = view == "detailIos")
}
