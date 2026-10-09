package com.retro99.catalogue.domain

import com.retro99.catalogue.domain.CatalogueRichText.ListItem
import com.retro99.catalogue.domain.CatalogueRichText.Marker
import com.retro99.catalogue.domain.CatalogueRichText.Span

/**
 * Turns a catalogue's description into [CatalogueRichText].
 *
 * Kept: paragraphs, line breaks, bold, italic, lists. A link becomes its text. Images, tables,
 * scripts, styles, frames, forms and drawings are dropped with what is inside them, and every
 * attribute is dropped, so no address and no event handler is ever read. Input is cut at
 * [CatalogueRichText.MAX_INPUT_CHARS]; nesting is counted, never recursed into, and list
 * indent stops at [CatalogueRichText.MAX_LIST_LEVEL].
 */
fun sanitizeCatalogueDescription(text: String, format: CatalogueDescriptionFormat): CatalogueRichText {
    val bounded = if (text.length > CatalogueRichText.MAX_INPUT_CHARS) text.substring(0, CatalogueRichText.MAX_INPUT_CHARS) else text
    val builder = RichTextBuilder()
    when (format) {
        CatalogueDescriptionFormat.Text -> plainText(bounded, builder)
        CatalogueDescriptionFormat.Html, CatalogueDescriptionFormat.Xhtml -> markup(bounded, builder)
    }
    return builder.build()
}

private fun plainText(text: String, out: RichTextBuilder) {
    text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        out.text(line)
        // A second break in a row ends the paragraph, as in markup.
        out.lineBreak()
    }
}

private val BOLD = setOf("b", "strong")
private val ITALIC = setOf("i", "em", "cite", "dfn", "var")
private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
private val BLOCKS = HEADINGS + setOf(
    "p", "div", "section", "article", "blockquote", "pre", "header", "footer", "aside", "address", "figure",
    "figcaption", "dl", "dt", "dd", "main", "nav", "center", "hr", "body", "html", "details", "summary",
)

/** Dropped with everything inside them. */
private val DROPPED = setOf(
    "script", "style", "iframe", "noscript", "svg", "math", "object", "embed", "applet", "template", "head", "title",
    "table", "select", "textarea", "button", "audio", "video", "canvas", "frameset", "map", "picture",
)

/** Their content is not markup: only their own end tag ends them. */
private val RAW_TEXT = setOf("script", "style")

/** Never have content, so being in [DROPPED] must not swallow what follows. */
private val VOID = setOf("embed")

private fun markup(text: String, out: RichTextBuilder) {
    var at = 0
    var dropped: String? = null // the element being dropped, with everything in it
    var droppedDepth = 0
    val length = text.length

    fun textUpTo(end: Int) {
        if (dropped == null && end > at) out.text(decodeEntities(text.substring(at, end)))
        at = end
    }

    while (at < length) {
        val open = text.indexOf('<', at)
        if (open < 0) {
            textUpTo(length)
            break
        }
        textUpTo(open)
        when {
            text.startsWith("<!--", open) -> {
                val end = text.indexOf("-->", open + 4)
                at = if (end < 0) length else end + 3
            }
            text.startsWith("<![CDATA[", open) -> {
                val end = text.indexOf("]]>", open + 9)
                if (dropped == null) out.text(text.substring(open + 9, if (end < 0) length else end))
                at = if (end < 0) length else end + 3
            }
            text.startsWith("<!", open) || text.startsWith("<?", open) -> {
                val end = text.indexOf('>', open)
                at = if (end < 0) length else end + 1
            }
            else -> {
                val closing = text.startsWith("</", open)
                val nameStart = open + if (closing) 2 else 1
                var nameEnd = nameStart
                while (nameEnd < length && (text[nameEnd].isLetterOrDigit() || text[nameEnd] == ':' || text[nameEnd] == '-' || text[nameEnd] == '_')) nameEnd++
                if (nameEnd == nameStart || !text[nameStart].isLetter()) {
                    // "<" that starts no tag: a character like any other.
                    if (dropped == null) out.text("<")
                    at = open + 1
                    continue
                }
                val tagEnd = endOfTag(text, nameEnd)
                if (tagEnd < 0) {
                    // Never closed. Showing the rest would show its attributes.
                    at = length
                    continue
                }
                // "xhtml:p" is "p"; "svg:script" is "script".
                val name = text.substring(nameStart, nameEnd).substringAfterLast(':').lowercase()
                val selfClosing = !closing && text[tagEnd - 1] == '/'
                at = tagEnd + 1
                val current = dropped
                if (current != null) {
                    if (name == current) {
                        if (closing) {
                            if (--droppedDepth == 0) dropped = null
                        } else if (!selfClosing) {
                            droppedDepth++
                        }
                    }
                } else if (name in DROPPED) {
                    out.droppedElement(name)
                    if (!closing && !selfClosing && name !in VOID) {
                        if (name in RAW_TEXT) {
                            at = endOfRawText(text, at, name)
                        } else {
                            dropped = name
                            droppedDepth = 1
                        }
                    }
                } else if (closing) {
                    out.end(name)
                } else {
                    out.start(name)
                    if (selfClosing) out.end(name)
                }
            }
        }
    }
}

/** Index of the '>' that ends the tag whose name ends at [from], skipping quoted attribute values; -1 if none. */
private fun endOfTag(text: String, from: Int): Int {
    var quote: Char? = null
    for (i in from until text.length) {
        val c = text[i]
        if (quote != null) {
            if (c == quote) quote = null
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            return i
        }
    }
    return -1
}

/** Index just past `</name ...>`, searching from [from] without reading anything as markup. */
private fun endOfRawText(text: String, from: Int, name: String): Int {
    var search = from
    while (true) {
        val close = text.indexOf("</", search)
        if (close < 0) return text.length
        val nameEnd = close + 2 + name.length
        // A namespace prefix in front of the name is allowed: "</svg:script>".
        val candidateEnd = text.indexOf('>', close)
        if (candidateEnd < 0) return text.length
        val written = text.substring(close + 2, candidateEnd).trim().substringAfterLast(':')
        if (written.equals(name, ignoreCase = true) || nameEnd <= text.length && text.regionMatches(close + 2, name, 0, name.length, ignoreCase = true) &&
            (nameEnd == text.length || !text[nameEnd].isLetterOrDigit())
        ) {
            return candidateEnd + 1
        }
        search = close + 2
    }
}

private class RichTextBuilder {
    private class OpenList(val ordered: Boolean) { var count = 0 }

    private val blocks = mutableListOf<CatalogueRichText.Block>()
    private val items = mutableListOf<ListItem>() // of the outermost open list
    private val lists = ArrayDeque<OpenList>()
    private var listsNotOpened = 0 // nested past the limit: counted so their end tags pair up
    private var itemList: OpenList? = null // the list the text being collected is an item of
    private var itemLevel = 0

    private val spans = mutableListOf<Span>()
    private val run = StringBuilder()
    private var runBold = false
    private var runItalic = false
    private var hasContent = false
    private var endsWithSpace = false
    private var endsWithBreak = false

    private var bold = 0
    private var italic = 0
    private var heading = 0

    fun text(text: String) {
        for (c in text) {
            when {
                c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C' -> space()
                // Control characters and direction overrides are not text a description needs.
                c < ' ' || c == '\u007F' || c in '‪'..'‮' || c in '⁦'..'⁩' -> Unit
                else -> append(c)
            }
        }
    }

    /** Runs of white space are one space, and none at the start or end of a line. */
    private fun space() {
        if (!hasContent || endsWithBreak || endsWithSpace) return
        append(' ')
        endsWithSpace = true
    }

    private fun trimSpace() {
        if (endsWithSpace && run.isNotEmpty()) run.setLength(run.length - 1)
        endsWithSpace = false
    }

    private fun append(c: Char) {
        if (lists.isNotEmpty() && itemList == null) startItem()
        val isBold = bold > 0 || heading > 0
        val isItalic = italic > 0
        if (run.isNotEmpty() && (isBold != runBold || isItalic != runItalic)) endRun()
        if (run.isEmpty()) {
            runBold = isBold
            runItalic = isItalic
        }
        endsWithSpace = false
        endsWithBreak = false
        hasContent = true
        run.append(c)
    }

    private fun endRun() {
        if (run.isNotEmpty()) spans += Span(run.toString(), runBold, runItalic)
        run.clear()
    }

    /** `<br>`, or a line end in plain text. Twice in a row ends the paragraph. */
    fun lineBreak() {
        trimSpace()
        if (!hasContent) return
        if (endsWithBreak) {
            if (itemList == null) flush()
            return
        }
        run.append('\n')
        endsWithBreak = true
    }

    /** The edge of a paragraph-like element. Inside a list item it is a new line of that item. */
    private fun blockEdge() {
        if (itemList != null) {
            trimSpace()
            if (hasContent && !endsWithBreak) {
                run.append('\n')
                endsWithBreak = true
            }
        } else {
            flush()
        }
    }

    /** Something was removed here; the words around it must not run together. */
    fun droppedElement(name: String) {
        if (name == "table") blockEdge() else space()
    }

    fun start(name: String) {
        when (name) {
            "br" -> lineBreak()
            in BOLD -> bold++
            in ITALIC -> italic++
            "ul", "ol" -> {
                flush()
                if (lists.size > CatalogueRichText.MAX_LIST_LEVEL) listsNotOpened++ else lists.addLast(OpenList(ordered = name == "ol"))
            }
            "li" -> {
                flush()
                if (lists.isEmpty()) lists.addLast(OpenList(ordered = false))
                startItem()
            }
            in BLOCKS -> {
                blockEdge()
                if (name in HEADINGS) heading++
            }
            // Everything else (a, span, font, u, img, input, ...) leaves only its text, if it has any.
        }
    }

    fun end(name: String) {
        when (name) {
            in BOLD -> if (bold > 0) bold--
            in ITALIC -> if (italic > 0) italic--
            "ul", "ol" -> {
                if (listsNotOpened > 0) {
                    listsNotOpened--
                    return
                }
                if (lists.isEmpty()) return
                flush()
                lists.removeLast()
                if (lists.isEmpty()) endLists()
            }
            "li" -> if (lists.isNotEmpty()) flush()
            in BLOCKS -> {
                if (name in HEADINGS && heading > 0) heading--
                blockEdge()
            }
        }
    }

    private fun startItem() {
        itemList = lists.last()
        itemLevel = minOf(lists.size - 1 + listsNotOpened, CatalogueRichText.MAX_LIST_LEVEL)
    }

    /** Ends the paragraph or list item being collected. Empty ones leave nothing behind. */
    private fun flush() {
        trimSpace()
        if (endsWithBreak && run.isNotEmpty()) run.setLength(run.length - 1)
        endRun()
        val list = itemList
        if (spans.isNotEmpty()) {
            val collected = spans.toList()
            if (list != null) {
                items += ListItem(collected, itemLevel, if (list.ordered) Marker.Number(++list.count) else Marker.Bullet)
            } else {
                blocks += CatalogueRichText.Paragraph(collected)
            }
        }
        spans.clear()
        itemList = null
        hasContent = false
        endsWithSpace = false
        endsWithBreak = false
    }

    private fun endLists() {
        if (items.isNotEmpty()) blocks += CatalogueRichText.ListBlock(items.toList())
        items.clear()
        listsNotOpened = 0
    }

    fun build(): CatalogueRichText {
        flush()
        lists.clear()
        endLists()
        return CatalogueRichText(blocks.toList())
    }
}

private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·",
    "bull" to "•", "sect" to "§", "para" to "¶", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
    "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "shy" to "", "ensp" to " ", "emsp" to " ",
    "thinsp" to " ", "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "agrave" to "à",
    "egrave" to "è", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù", "acirc" to "â", "ecirc" to "ê", "icirc" to "î",
    "ocirc" to "ô", "ucirc" to "û", "auml" to "ä", "euml" to "ë", "iuml" to "ï", "ouml" to "ö", "uuml" to "ü",
    "ccedil" to "ç", "ntilde" to "ñ", "atilde" to "ã", "otilde" to "õ", "aring" to "å", "oslash" to "ø", "aelig" to "æ",
    "szlig" to "ß", "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú", "Auml" to "Ä",
    "Ouml" to "Ö", "Uuml" to "Ü", "Ccedil" to "Ç", "Ntilde" to "Ñ", "Aring" to "Å", "Oslash" to "Ø", "AElig" to "Æ",
    "scaron" to "š", "Scaron" to "Š", "zcaron" to "ž", "Zcaron" to "Ž", "ccaron" to "č", "Ccaron" to "Č",
)
private const val MAX_ENTITY_CHARS = 10

/** Decoded once, into characters: what comes out is text and is not read again as markup. */
private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var at = 0
    while (at < text.length) {
        val c = text[at]
        val end = if (c == '&') text.indexOf(';', at + 1) else -1
        val decoded = if (end in (at + 2)..(at + 1 + MAX_ENTITY_CHARS)) decodeEntity(text.substring(at + 1, end)) else null
        if (decoded == null) {
            out.append(c)
            at++
        } else {
            out.append(decoded)
            at = end + 1
        }
    }
    return out.toString()
}

private fun decodeEntity(body: String): String? {
    if (!body.startsWith('#')) return ENTITIES[body]
    val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
    val digits = body.substring(if (hex) 2 else 1)
    if (digits.isEmpty() || digits.length > 8) return null
    val code = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
    return when {
        code == 0xA0 -> " "
        // Not characters: shown as nothing, not as the letters of the entity.
        code !in 1..0x10FFFF || code in 0xD800..0xDFFF -> ""
        code < 0x10000 -> code.toChar().toString()
        else -> charArrayOf((0xD800 + ((code - 0x10000) shr 10)).toChar(), (0xDC00 + ((code - 0x10000) and 0x3FF)).toChar()).concatToString()
    }
}
