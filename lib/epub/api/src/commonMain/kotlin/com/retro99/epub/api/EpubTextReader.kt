package com.retro99.epub.api

import com.retro99.base.result.AppResult

/**
 * Reads the plain text of an EPUB's chapters. Implementations open the archive and read only the
 * entries they need, one at a time; they never load the whole file (read-aloud EPUBs contain the
 * audio and can be several GB).
 */
interface EpubTextReader {
    /** Reading-order chapters with their plain text; an error when the file can't be read. */
    suspend fun readChapters(filePath: String): AppResult<List<EpubChapterText>>
}

data class EpubChapterText(
    /** The chapter's path inside the EPUB, as the reader's locators name it. */
    val href: String,
    val title: String?,
    /** Plain text, block elements separated by '\n'. */
    val text: String,
    /** Elements with an `id`, in document order. */
    val elementOffsets: List<ElementOffset>,
)

/**
 * Where an element with an id starts in its chapter's [EpubChapterText.text], and where it ends
 * (exclusive). The end lets callers tell whether an offset falls inside the element.
 */
data class ElementOffset(
    val elementId: String,
    val startOffset: Int,
    val endOffset: Int = startOffset,
)
