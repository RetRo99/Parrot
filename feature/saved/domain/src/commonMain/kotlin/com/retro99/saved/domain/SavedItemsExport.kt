package com.retro99.saved.domain

import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType

/**
 * Plain text and Markdown exports of one book's saved items: in book order, under
 * chapter headings, each with its quote (or a bookmark's sentence), note and position.
 */
object SavedItemsExport {

    /** Translated words the export uses. */
    data class Labels(
        val bookmark: String,
        val highlight: String,
        val note: String,
        /** "listening, %s" with the audio time. */
        val listeningFormat: String,
        val untitledChapter: String,
        val word: String = "Word",
    )

    fun plainText(
        bookTitle: String,
        bookAuthor: String?,
        items: List<SavedItem>,
        labels: Labels,
    ): String = buildString {
        append(bookTitle)
        if (!bookAuthor.isNullOrBlank()) append(" — ").append(bookAuthor)
        appendLine()
        groupByChapter(items, labels).forEach { (chapter, chapterItems) ->
            appendLine()
            appendLine(chapter)
            chapterItems.forEach { item ->
                appendLine()
                if (item.word != null) appendLine("${item.word.headword} — ${item.word.gloss}")
                else item.text?.let { text -> appendLine("“$text”") }
                if (item.hasNote) appendLine("${labels.note}: ${item.note!!.trim()}")
                appendLine(item.meta(labels))
            }
        }
        if (items.any { it.word != null }) appendLine("\nDictionary excerpts: Open English WordNet (https://en-word.net), CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/), based on Princeton WordNet (https://wordnet.princeton.edu/license-and-commercial-use). Trimmed by Parrot.")
    }.trimEnd() + "\n"

    fun markdown(
        bookTitle: String,
        bookAuthor: String?,
        items: List<SavedItem>,
        labels: Labels,
    ): String = buildString {
        appendLine("# ${bookTitle.escapeMarkdownLine()}")
        if (!bookAuthor.isNullOrBlank()) {
            appendLine()
            appendLine("_${bookAuthor.escapeMarkdownLine()}_")
        }
        groupByChapter(items, labels).forEach { (chapter, chapterItems) ->
            appendLine()
            appendLine("## ${chapter.escapeMarkdownLine()}")
            chapterItems.forEach { item ->
                appendLine()
                if (item.word != null) appendLine("${item.word.headword.escapeMarkdownLine()} — ${item.word.gloss.escapeMarkdownLine()}")
                else item.text?.let { text ->
                    text.lines().forEach { line -> appendLine("> $line") }
                    appendLine()
                }
                if (item.hasNote) {
                    appendLine("**${labels.note}:** ${item.note!!.trim().replace("\n", "  \n")}")
                    appendLine()
                }
                appendLine("*${item.meta(labels)}*")
            }
        }
        if (items.any { it.word != null }) appendLine("\nDictionary excerpts: Open English WordNet (https://en-word.net), CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/), based on Princeton WordNet (https://wordnet.princeton.edu/license-and-commercial-use). Trimmed by Parrot.")
    }.trimEnd() + "\n"

    /** Consecutive items of one chapter, in the order given (book order). */
    fun groupByChapter(items: List<SavedItem>, labels: Labels): List<Pair<String, List<SavedItem>>> {
        val groups = mutableListOf<Pair<String, MutableList<SavedItem>>>()
        items.forEach { item ->
            val chapter = item.location.chapterTitle?.takeIf { title -> title.isNotBlank() }
                ?: labels.untitledChapter
            val last = groups.lastOrNull()
            if (last != null && last.first == chapter) {
                last.second += item
            } else {
                groups += chapter to mutableListOf(item)
            }
        }
        return groups
    }

    private fun SavedItem.meta(labels: Labels): String {
        val kind = when (type) { SavedItemType.Highlight -> labels.highlight; SavedItemType.Bookmark -> labels.bookmark; SavedItemType.Word -> labels.word }
        val where = audio?.let { position ->
            labels.listeningFormat.replace("%s", formatAudioTime(position.offsetMs))
        } ?: location.totalProgression?.let { progression -> "${percent(progression)}%" }
        return listOfNotNull(kind, where).joinToString(" · ")
    }

    private fun String.escapeMarkdownLine(): String = replace("\n", " ").trim()
}

/** Whole percent of the book, 0–100. */
fun percent(totalProgression: Double): Int =
    (totalProgression.coerceIn(0.0, 1.0) * 100).toInt()

/** h:mm:ss, or m:ss under an hour. */
fun formatAudioTime(offsetMs: Long): String {
    val totalSeconds = (offsetMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
}
