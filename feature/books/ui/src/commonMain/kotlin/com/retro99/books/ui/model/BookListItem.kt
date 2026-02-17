package com.retro99.books.ui.model

/**
 * Unified sealed interface for displaying books in a list.
 * This allows both Storyteller books and imported books to be displayed
 * in a single sorted list while maintaining type safety.
 */
sealed interface BookListItem {
    val uuid: String
    val title: String
    val authors: List<String>
    val coverImageSource: String?
    val isLocal: Boolean

    /**
     * A book from the Storyteller server/library.
     */
    data class StorytellerBook(
        val book: BookUiModel,
        val isFavorite: Boolean,
    ) : BookListItem {
        override val uuid: String get() = book.uuid
        override val title: String get() = book.title
        override val authors: List<String> get() = book.authors
        override val coverImageSource: String? get() = book.coverUrl
        override val isLocal: Boolean get() = false
    }

    /**
     * A locally imported book (EPUB file).
     */
    data class LocalBook(
        val book: ImportedBookUiModel,
    ) : BookListItem {
        override val uuid: String get() = book.uuid
        override val title: String get() = book.title
        override val authors: List<String> get() = listOfNotNull(book.author)
        override val coverImageSource: String? get() = book.coverPath?.let { "file://$it" }
        override val isLocal: Boolean get() = true
    }
}

