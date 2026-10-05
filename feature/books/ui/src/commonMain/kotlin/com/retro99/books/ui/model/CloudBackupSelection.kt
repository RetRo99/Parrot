package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookType

/** A local import that can still be added to Parrot Cloud. */
data class CloudBackupBook(
    val book: BookUiModel.LibraryBook,
    val mediaTypes: List<String>,
    val sizeBytes: Long,
) {
    val id: String get() = book.uuid
}

private val CLOUD_COPY_STATES = setOf("Available", "UploadPending", "Uploading")
private val UPLOADABLE_MEDIA_TYPES = setOf(BookType.EBOOK.value, BookType.READALOUD.value)

/**
 * Builds the exact list offered by the library banner and selection sheet. A title appears
 * once even if it has more than one imported EPUB representation.
 */
fun List<BookUiModel>.cloudBackupBooks(
    uploadingBookIds: Set<String> = emptySet(),
): List<CloudBackupBook> = mapNotNull { model ->
    val book = model as? BookUiModel.LibraryBook ?: return@mapNotNull null
    if (book.uuid in uploadingBookIds) return@mapNotNull null
    if (book.mediaResources.any { resource -> resource.remoteAvailability in CLOUD_COPY_STATES }) {
        return@mapNotNull null
    }
    val resources = book.mediaResources.filter { resource ->
        resource.localPath != null &&
            resource.localOrigin == "import" &&
            resource.mediaType in UPLOADABLE_MEDIA_TYPES
    }
    if (resources.isEmpty()) return@mapNotNull null
    CloudBackupBook(
        book = book,
        mediaTypes = resources.map { resource -> resource.mediaType },
        sizeBytes = resources.sumOf { resource -> resource.size ?: 0L },
    )
}

/** Imported upload targets, including ones already in an active/completed cloud state. */
fun List<BookUiModel>.cloudBackupUploadBookIds(): Set<String> = mapNotNullTo(linkedSetOf()) { model ->
    val book = model as? BookUiModel.LibraryBook ?: return@mapNotNullTo null
    book.uuid.takeIf {
        book.mediaResources.any { resource ->
            resource.localPath != null &&
                resource.localOrigin == "import" &&
                resource.mediaType in UPLOADABLE_MEDIA_TYPES
        }
    }
}

/** Decimal storage labels, matching the Parrot Cloud storage screen. */
fun cloudStorageLabel(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    val (divisor, unit) = when {
        value >= 1_000_000_000L -> 1_000_000_000.0 to "GB"
        value >= 1_000_000L -> 1_000_000.0 to "MB"
        value >= 1_000L -> 1_000.0 to "KB"
        else -> return "$value B"
    }
    val rounded = ((value / divisor) * 10.0).toLong() / 10.0
    val number = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$number $unit"
}
