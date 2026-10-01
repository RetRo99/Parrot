package com.retro99.books.ui.detail

import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.model.BookType
import com.retro99.books.ui.model.BookUiModel
import com.retro99.reader.domain.model.DownloadState

internal data class DetailMedia(
    val type: BookType,
    val state: DownloadState,
    val size: Long?,
    val canOpen: Boolean,
    val canDownload: Boolean,
    val canRemove: Boolean,
    val awaitingUpload: Boolean,
    val preparing: Boolean,
)

internal data class PhoneMediaSummary(
    val singleType: BookType?,
    val formatCount: Int,
    val size: Long?,
)

internal fun phoneMediaSummary(media: List<DetailMedia>): PhoneMediaSummary {
    val cached = media.filter { item -> item.state is DownloadState.Cached }
    val sizes = cached.mapNotNull { item -> item.size }
    return PhoneMediaSummary(
        singleType = cached.singleOrNull()?.type,
        formatCount = cached.size,
        size = sizes.takeIf { values -> values.isNotEmpty() && values.size == cached.size }?.sum(),
    )
}

internal fun BookDetailViewState.detailMedia(): List<DetailMedia> {
    val book = book ?: return emptyList()
    return listOf(BookType.EBOOK, BookType.READALOUD, BookType.AUDIOBOOK).mapNotNull { type ->
        val exists = when (type) {
            BookType.EBOOK -> book.hasEbook
            BookType.READALOUD -> book.hasReadaloud
            BookType.AUDIOBOOK -> book.hasAudiobook
        }
        if (!exists) return@mapNotNull null
        val state = downloadState(type)
        val permissions = libraryMediaActions[type]
        val resource = (book as? BookUiModel.LibraryBook)?.mediaResource(type)
        val preparing = type == BookType.READALOUD && book.narrationIsPreparing()
        DetailMedia(
            type = type,
            state = state,
            size = book.mediaSizes[type]?.takeIf { size -> size > 0 },
            canOpen = state is DownloadState.Cached && !preparing &&
                (book !is BookUiModel.LibraryBook || permissions?.open == true),
            canDownload = !preparing && if (book is BookUiModel.LibraryBook) {
                permissions?.download == true
            } else book.filePath(type) != null,
            canRemove = state is DownloadState.Cached && type in removableDownloadTypes,
            awaitingUpload = resource?.remoteAvailability in setOf("UploadPending", "Uploading"),
            preparing = preparing,
        )
    }
}

internal fun BookDetailViewState.downloadState(type: BookType): DownloadState = when (type) {
    BookType.EBOOK -> ebookDownloadState
    BookType.READALOUD -> readaloudDownloadState
    BookType.AUDIOBOOK -> audiobookDownloadState
}

/** Comparing is navigation, not an answer to the pending open. */
internal fun BookDetailViewState.beginPositionComparison(): BookDetailViewState =
    copy(comparingLinkedPositions = true)

internal fun BookDetailViewState.returnFromPositionComparison(): BookDetailViewState =
    copy(comparingLinkedPositions = false)

internal fun BookUiModel.narrationIsPreparing(): Boolean =
    narrationStatus?.lowercase()?.let { status ->
        status !in setOf("ready", "completed", "complete", "available", "aligned")
    } ?: false

internal fun DetailMedia.openIntent(): BookDetailIntent = when (type) {
    BookType.EBOOK -> BookDetailIntent.OnReadEbookClicked
    BookType.READALOUD -> BookDetailIntent.OnReadReadaloudClicked
    BookType.AUDIOBOOK -> BookDetailIntent.OnPlayAudiobookClicked
}

internal val BookFileTransfer.isActive: Boolean
    get() = state in setOf("pending", "transferring", "verifying", "finalizing")

/** A current row per direction and format; old completed attempts aren't duplicate status. */
internal fun visibleDetailTransfers(transfers: List<BookFileTransfer>): List<BookFileTransfer> =
    transfers.filter { transfer -> transfer.isActive || transfer.state == "failed" }

internal fun formatPublicationDate(raw: String?): String? {
    val value = raw?.trim()?.takeIf { text -> text.isNotEmpty() } ?: return null
    val year = value.take(4).toIntOrNull() ?: return null
    return year.takeIf { number -> number >= 1000 }?.toString()
}

internal fun plainTextDescription(description: String): String = description
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("</(?:p|div)>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("</?[a-zA-Z][^>]*>"), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("[ \t]+"), " ")
    .replace(Regex(" +([,.;:!?)])"), "$1")
    .replace(Regex(" *\n *"), "\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
