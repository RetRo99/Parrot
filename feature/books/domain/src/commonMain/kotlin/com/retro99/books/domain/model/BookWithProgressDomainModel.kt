package com.retro99.books.domain.model

/**
 * Wrapper class that combines a book with its progress information.
 * Used by use cases that need to return both book data and progress together.
 */
data class BookWithProgressDomainModel(
    val book: BookDomainModel,
    /**
     * Progress and cache information for the book.
     * Null if no progress or cache exists for this book.
     */
    val progressInfo: BookProgressInfoDomainModel?,
    val lastOpenedMillis: Long? = null,
    val currentlyReading: Boolean = false,
)
