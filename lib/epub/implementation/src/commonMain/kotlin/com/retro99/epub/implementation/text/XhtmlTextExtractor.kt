package com.retro99.epub.implementation.text

import com.retro99.epub.api.ElementOffset

internal data class ExtractedChapter(
    val title: String?,
    val text: String,
    val elementOffsets: List<ElementOffset>,
)

/**
 * Turns a chapter's XHTML into plain text: whitespace collapsed, block elements separated by
 * '\n', entities decoded. Records where each element with an `id` starts and ends in that text.
 */
internal object XhtmlTextExtractor {

    private val blockElements = setOf(
        "address", "article", "aside", "blockquote", "body", "br", "dd", "div", "dl", "dt",
        "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "li", "nav", "ol", "p", "pre", "section", "table", "tbody", "td", "th", "tr", "ul",
    )
    private val skippedElements = setOf("head", "script", "style", "noscript", "svg", "math")
    private val headings = setOf("h1", "h2", "h3")

    fun extract(content: String): ExtractedChapter {
        val builder = Builder()
        scanMarkup(content) { token -> builder.accept(token) }
        return builder.build()
    }

    private class Record(val id: String, var start: Int = -1, var end: Int = -1)

    private class Frame(val name: String, val record: Record?)

    private class Builder {
        private val text = StringBuilder()
        private val records = mutableListOf<Record>()
        private val unstarted = mutableListOf<Record>()
        private val stack = ArrayDeque<Frame>()
        private var skipDepth = 0
        private var pendingBreak = false
        private var documentTitle: StringBuilder? = null
        private var titleText: String? = null
        private var heading: StringBuilder? = null
        private var headingText: String? = null

        fun accept(token: MarkupToken) {
            when (token) {
                is MarkupToken.StartTag -> start(token)
                is MarkupToken.EndTag -> end(token.name)
                is MarkupToken.Text -> appendText(token.text)
            }
        }

        private fun start(tag: MarkupToken.StartTag) {
            if (tag.name == "title" && titleText == null && !tag.selfClosing) {
                documentTitle = StringBuilder()
            }
            if (tag.name in skippedElements && !tag.selfClosing) skipDepth++
            if (tag.name in headings && headingText == null && heading == null) {
                heading = StringBuilder()
            }
            if (tag.name in blockElements) pendingBreak = true
            val id = tag.attributes["id"]?.takeIf { value -> value.isNotEmpty() }
            val record = id?.let { value -> Record(value) }?.also { created ->
                records += created
                unstarted += created
            }
            if (tag.selfClosing) {
                record?.let { closed -> close(closed) }
            } else {
                stack.addLast(Frame(tag.name, record))
            }
        }

        private fun end(name: String) {
            if (name == "title" && documentTitle != null) {
                titleText = documentTitle.toString().collapseWhitespace().ifEmpty { null }
                documentTitle = null
            }
            if (name in headings && heading != null) {
                headingText = heading.toString().collapseWhitespace().ifEmpty { null }
                heading = null
            }
            if (name in skippedElements && skipDepth > 0) skipDepth--
            if (name in blockElements) pendingBreak = true
            // Pop to the matching element; unclosed children close with it.
            if (stack.none { frame -> frame.name == name }) return
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                frame.record?.let { record -> close(record) }
                if (frame.name == name) break
            }
        }

        private fun close(record: Record) {
            if (record.start < 0) {
                record.start = text.length
                unstarted.remove(record)
            }
            record.end = text.length
        }

        private fun appendText(raw: String) {
            documentTitle?.append(raw)
            if (skipDepth > 0) return
            heading?.append(raw)
            for (char in raw) {
                val isSpace = char.isWhitespace() && char != ' '
                if (isSpace) {
                    if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') {
                        text.append(' ')
                    }
                    continue
                }
                if (pendingBreak) {
                    if (text.isNotEmpty()) {
                        if (text.last() == ' ') text.setLength(text.length - 1)
                        if (text.last() != '\n') text.append('\n')
                    }
                    pendingBreak = false
                }
                if (unstarted.isNotEmpty()) {
                    unstarted.forEach { record -> record.start = text.length }
                    unstarted.clear()
                }
                text.append(char)
            }
        }

        fun build(): ExtractedChapter {
            while (text.isNotEmpty() && text.last().isWhitespace()) text.setLength(text.length - 1)
            val length = text.length
            val offsets = records.map { record ->
                val start = (if (record.start < 0) length else record.start).coerceAtMost(length)
                val end = (if (record.end < 0) length else record.end).coerceIn(start, length)
                ElementOffset(elementId = record.id, startOffset = start, endOffset = end)
            }
            return ExtractedChapter(
                title = headingText ?: titleText,
                text = text.toString(),
                elementOffsets = offsets,
            )
        }
    }
}

private val whitespace = Regex("\\s+")

internal fun String.collapseWhitespace(): String = replace(whitespace, " ").trim()
