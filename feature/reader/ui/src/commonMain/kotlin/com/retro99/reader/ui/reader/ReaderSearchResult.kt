package com.retro99.reader.ui.reader

/** A full-text search hit returned by the platform EPUB navigator. */
data class ReaderSearchResult(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val before: String?,
    val match: String?,
    val after: String?,
    /** Zero-based reading-order index within this search. */
    val index: Int,
    /** Original Readium locator, including all locations and unmodified Locator.Text. */
    val locatorJson: String,
    val sessionId: Long = 0,
)

data class ReaderSearchBatch(
    val results: List<ReaderSearchResult>,
    val runningCount: Int,
    val isComplete: Boolean = false,
)

class BookNotSearchableException(val noTextLayer: Boolean = false) : IllegalStateException()
