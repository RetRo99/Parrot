package com.retro99.reader.ui.reader

/** A full-text search hit returned by the platform EPUB navigator. */
data class ReaderSearchResult(
    val href: String,
    val type: String,
    val title: String?,
    val progression: Double?,
    val position: Int?,
    val totalProgression: Double?,
    val snippet: String,
)
