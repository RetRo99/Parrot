package com.retro99.catalogue.ui.publication

import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.ui.browse.CatalogueSignInState
import com.retro99.catalogue.ui.browse.display
import com.retro99.server.api.*

data class BookFile(val publication: CataloguePublication, val choice: CatalogueFileChoice, val ordinal: Int, val label: String?, val openable: Boolean, val best: Boolean) {
    val size: Long? get() = choice.link.lengthBytes
}
data class BookFileGroup(val label: String?, val files: List<BookFile>)
data class BookBlocked(val reason: CatalogueUnavailableReason, val provider: String?, val providerLink: String?, val format: String?)

sealed interface BookMainAction {
    data class Download(val file: BookFile) : BookMainAction
    data class Waiting(val otherDownloads: Int) : BookMainAction
    data class Downloading(val bytes: Long, val total: Long?) : BookMainAction
    data object Adding : BookMainAction
    data class Done(val libraryBookId: String, val completedAt: Long?) : BookMainAction
    data class Blocked(val blocked: BookBlocked) : BookMainAction
    data class Failed(val acquisition: CatalogueAcquisition) : BookMainAction
}
sealed interface BookNavigation {
    data object CatalogueRoot : BookNavigation
    data class Read(val libraryBookId: String) : BookNavigation
}
data class CatalogueBookState(
    val catalogueName: String = "",
    val book: CataloguePublication? = null,
    val action: BookMainAction? = null,
    val groups: List<BookFileGroup> = emptyList(),
    val selectedOrdinal: Int? = null,
    val chooseFile: Boolean = false,
    val signIn: CatalogueSignInState? = null,
    val downloadNotice: Boolean = false,
    val navigation: BookNavigation? = null,
    val closed: Boolean = false,
    val loadFailed: Boolean = false,
) {
    val files get() = groups.flatMap { it.files }
    val selectedFile get() = files.firstOrNull { it.ordinal == selectedOrdinal }
}

fun bookFileGroups(publications: List<CataloguePublication>): List<BookFileGroup> {
    var ordinal = 0
    var bestGiven = false
    return publications.map { publication ->
        BookFileGroup(publication.editionLabel.display()?.takeIf(String::isNotBlank), publication.acquisitionChoices.map { choice ->
            val openable = choice.isOpenable && choice.action is CatalogueAcquisitionAction.Download
            val best = openable && !bestGiven
            if (best) bestGiven = true
            BookFile(publication, choice, ++ordinal, choice.link.title.display()?.takeIf(String::isNotBlank), openable, best)
        })
    }
}

/** The classifier's reasons, across editions, in design §10 priority order. No sample download. */
fun bookBlocked(publications: List<CataloguePublication>): BookBlocked? {
    if (bookFileGroups(publications).any { group -> group.files.any { it.openable } }) return null
    val priority = listOf(CatalogueUnavailableReason.SampleOnly, CatalogueUnavailableReason.Sold, CatalogueUnavailableReason.Subscription, CatalogueUnavailableReason.Borrow, CatalogueUnavailableReason.Protected, CatalogueUnavailableReason.UnsupportedFormat)
    val candidates = publications.flatMap { p -> (listOf(p.acquisitionAction) + p.acquisitionChoices.map { it.action }).mapNotNull { action ->
        val reason = when (action) {
            is CatalogueAcquisitionAction.NotAvailable -> action.reason
            is CatalogueAcquisitionAction.OpenProviderPage -> action.reason
            else -> return@mapNotNull null
        }
        val link = (action as? CatalogueAcquisitionAction.OpenProviderPage)?.link
        BookBlocked(reason, p.acquisitionProviderName.display() ?: (if (reason == CatalogueUnavailableReason.Borrow) p.lender else p.seller).display(),
            link?.resolvedHref?.takeIf { reason != CatalogueUnavailableReason.Protected && reason != CatalogueUnavailableReason.UnsupportedFormat && (it.startsWith("https://") || it.startsWith("http://")) },
            p.unsupportedMediaType?.let { "${it.type}/${it.subtype}" })
    } }
    return candidates.minByOrNull { priority.indexOf(it.reason) }
        ?: BookBlocked(CatalogueUnavailableReason.UnsupportedFormat, null, null, null)
}

/** Decimal MB, one decimal place, as in the catalogue boards. */
fun catalogueMegabytes(bytes: Long): String {
    val tenths = ((bytes.coerceAtLeast(0) + 50_000) / 100_000)
    return "${tenths / 10}.${tenths % 10} MB"
}
