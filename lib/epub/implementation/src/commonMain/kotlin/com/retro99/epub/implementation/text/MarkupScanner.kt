package com.retro99.epub.implementation.text

/** A token of XML or XHTML, with names lowercased and namespace prefixes removed. */
internal sealed interface MarkupToken {
    data class StartTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : MarkupToken

    data class EndTag(val name: String) : MarkupToken

    /** Text with entities decoded. */
    data class Text(val text: String) : MarkupToken
}

/**
 * A forgiving markup tokenizer for EPUB files. Real XHTML often has entities a strict XML parser
 * rejects (`&nbsp;` without a DTD), so this reads tags, text and entities directly. Comments,
 * processing instructions and doctypes are skipped; CDATA becomes text.
 */
internal fun scanMarkup(content: String, onToken: (MarkupToken) -> Unit) {
    var index = 0
    val length = content.length
    while (index < length) {
        val tagStart = content.indexOf('<', index)
        if (tagStart < 0) {
            emitText(content.substring(index), onToken)
            return
        }
        if (tagStart > index) emitText(content.substring(index, tagStart), onToken)
        when {
            content.startsWith("<!--", tagStart) -> {
                val end = content.indexOf("-->", tagStart + 4)
                index = if (end < 0) length else end + 3
            }
            content.startsWith("<![CDATA[", tagStart) -> {
                val end = content.indexOf("]]>", tagStart + 9)
                val text = if (end < 0) content.substring(tagStart + 9) else
                    content.substring(tagStart + 9, end)
                onToken(MarkupToken.Text(text))
                index = if (end < 0) length else end + 3
            }
            content.startsWith("<?", tagStart) || content.startsWith("<!", tagStart) -> {
                val end = content.indexOf('>', tagStart + 2)
                index = if (end < 0) length else end + 1
            }
            else -> {
                val end = findTagEnd(content, tagStart + 1)
                if (end < 0) return
                onToken(parseTag(content.substring(tagStart + 1, end)))
                index = end + 1
            }
        }
    }
}

private fun emitText(raw: String, onToken: (MarkupToken) -> Unit) {
    if (raw.isNotEmpty()) onToken(MarkupToken.Text(decodeEntities(raw)))
}

/** The index of the '>' closing a tag, skipping quoted attribute values. */
private fun findTagEnd(content: String, from: Int): Int {
    var quote: Char? = null
    var index = from
    while (index < content.length) {
        val char = content[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '>' -> return index
        }
        index++
    }
    return -1
}

private val attributePattern = Regex("""([^\s=/]+)\s*(?:=\s*("[^"]*"|'[^']*'|[^\s>]+))?""")

private fun parseTag(body: String): MarkupToken {
    if (body.startsWith("/")) {
        return MarkupToken.EndTag(localName(body.substring(1).trim()))
    }
    val selfClosing = body.endsWith("/")
    val inner = if (selfClosing) body.dropLast(1) else body
    val nameEnd = inner.indexOfFirst { char -> char.isWhitespace() }
        .let { position -> if (position < 0) inner.length else position }
    val name = localName(inner.substring(0, nameEnd))
    val attributes = LinkedHashMap<String, String>()
    attributePattern.findAll(inner.substring(nameEnd)).forEach { match ->
        val attributeName = match.groupValues[1].lowercase()
        val rawValue = match.groupValues[2]
        val value = if (rawValue.length >= 2 && (rawValue[0] == '"' || rawValue[0] == '\'')) {
            rawValue.substring(1, rawValue.length - 1)
        } else {
            rawValue
        }
        attributes[attributeName] = decodeEntities(value)
    }
    return MarkupToken.StartTag(name, attributes, selfClosing)
}

private fun localName(name: String): String = name.substringAfter(':').lowercase()

private val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "shy" to "­", "mdash" to "—", "ndash" to "–",
    "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
    "rdquo" to "”", "laquo" to "«", "raquo" to "»", "copy" to "©",
    "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·",
    "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à",
    "ouml" to "ö", "uuml" to "ü", "auml" to "ä", "szlig" to "ß",
    "ccedil" to "ç", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
    "ntilde" to "ñ", "zwnj" to "‌", "zwj" to "‍", "thinsp" to " ",
    "ensp" to " ", "emsp" to " ",
)

internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '&') {
            val end = text.indexOf(';', index + 1)
            if (end > index && end - index <= 12) {
                val decoded = decodeEntity(text.substring(index + 1, end))
                if (decoded != null) {
                    result.append(decoded)
                    index = end + 1
                    continue
                }
            }
        }
        result.append(char)
        index++
    }
    return result.toString()
}

private fun decodeEntity(entity: String): String? {
    if (entity.startsWith("#")) {
        val code = if (entity.startsWith("#x") || entity.startsWith("#X")) {
            entity.substring(2).toIntOrNull(16)
        } else {
            entity.substring(1).toIntOrNull()
        } ?: return null
        return codePointToString(code)
    }
    return namedEntities[entity]
}

private fun codePointToString(code: Int): String? = when {
    code < 0 || code > 0x10FFFF -> null
    code < 0x10000 -> code.toChar().toString()
    else -> {
        val offset = code - 0x10000
        charArrayOf(
            (0xD800 + (offset shr 10)).toChar(),
            (0xDC00 + (offset and 0x3FF)).toChar(),
        ).concatToString()
    }
}
