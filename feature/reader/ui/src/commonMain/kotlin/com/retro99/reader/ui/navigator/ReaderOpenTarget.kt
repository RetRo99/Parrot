package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.model.PositionUiModel

/** Where the reader opens a stored position. */
sealed interface ReaderOpenTarget {
    /** At the position's own locator. */
    data object Locator : ReaderOpenTarget

    /** At the share of the whole book, because the locator's href can't be opened. */
    data class TotalProgression(val progression: Double) : ReaderOpenTarget

    /** At the start of the book: nothing better is known. */
    data object Start : ReaderOpenTarget
}

/**
 * A position whose href isn't one of the publication's resources (an Audiobookshelf CFI or
 * JSON locator, or any bad value) opens at its total progression instead of the start of the
 * book (B4). An empty [readingOrderHrefs] means the reading order isn't known: the locator is
 * used as before.
 */
fun PositionUiModel.openTarget(readingOrderHrefs: List<String>): ReaderOpenTarget {
    if (readingOrderHrefs.isEmpty() || isInReadingOrder(href, readingOrderHrefs)) {
        return ReaderOpenTarget.Locator
    }
    return totalProgression
        ?.takeIf { progression -> progression in 0.0..1.0 }
        ?.let { progression -> ReaderOpenTarget.TotalProgression(progression) }
        ?: ReaderOpenTarget.Start
}

/** Whether [href] names one of [readingOrderHrefs], ignoring a fragment and a leading slash. */
fun isInReadingOrder(href: String, readingOrderHrefs: List<String>): Boolean {
    val resource = href.resourcePath()
    if (resource.isEmpty()) return false
    return readingOrderHrefs.any { candidate -> candidate.resourcePath() == resource }
}

private fun String.resourcePath(): String = substringBefore('#').trimStart('/')
