package com.retro99.opds.implementation.search

import com.retro99.opds.api.OpdsTemplateExpander

/** Phase 0 scalar subset, hardened for UTF-8 literals, surrogate pairs and varnames. */
class Rfc6570Expander : OpdsTemplateExpander {
    override fun isTemplate(text: String): Boolean = '{' in text

    override fun expand(template: String, variables: Map<String, String>): String = buildString {
        var index = 0
        while (index < template.length) {
            if (template[index] == '{') {
                val end = template.indexOf('}', index + 1)
                if (end < 0) fail(index, "unmatched brace")
                val expression = template.substring(index + 1, end)
                if (expression.isEmpty()) fail(index, "empty expression")
                val operator = expression.first().takeIf { it in "+?&" }
                val names = expression.drop(if (operator == null) 0 else 1).split(',')
                if (names.any { !validName(it) }) fail(index, "unsupported operator, modifier or variable syntax")
                val defined = names.mapNotNull { name -> variables[name]?.let { name to it } }
                if (defined.isNotEmpty()) {
                    if (operator == '?' || operator == '&') {
                        append(operator)
                        append(defined.joinToString("&") { (name, value) -> "$name=${PercentEncoding.encode(value)}" })
                    } else {
                        append(defined.joinToString(",") { (_, value) -> PercentEncoding.encode(value, operator == '+') })
                    }
                }
                index = end + 1
            } else {
                val end = template.indexOf('{', index).let { if (it < 0) template.length else it }
                val literal = template.substring(index, end)
                for ((offset, c) in literal.withIndex()) {
                    if (c.code <= 0x20 || c.code in 0x7f..0x9f || c in "\"'<>\\^`{}|" || c == '\uFFFE' || c == '\uFFFF') {
                        fail(index + offset, "invalid literal")
                    }
                    if (c == '%' && (offset + 2 >= literal.length || !literal[offset + 1].isHex() || !literal[offset + 2].isHex())) {
                        fail(index + offset, "invalid percent triplet")
                    }
                }
                append(PercentEncoding.encode(literal, allowReserved = true))
                index = end
            }
        }
    }

    private fun validName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('.') || name.endsWith('.') || ".." in name) return false
        var i = 0
        while (i < name.length) {
            val c = name[i]
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' -> i++
                c == '%' && i + 2 < name.length && name[i + 1].isHex() && name[i + 2].isHex() -> i += 3
                else -> return false
            }
        }
        return true
    }

    private fun fail(at: Int, why: String): Nothing = throw OpdsTemplateExpander.OpdsTemplateException(at, why)
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Scalar UTF-8 percent encoding shared with OpenSearch; never form-encodes spaces as '+'. */
internal object PercentEncoding {
    fun encode(text: String, allowReserved: Boolean = false): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (allowReserved && c == '%' && i + 2 < text.length && text[i + 1].isHex() && text[i + 2].isHex()) {
                append(text, i, i + 3)
                i += 3
                continue
            }
            val width = if (c.isHighSurrogate()) {
                if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) throw OpdsTemplateExpander.OpdsTemplateException(i, "invalid Unicode scalar")
                2
            } else {
                if (c.isLowSurrogate()) throw OpdsTemplateExpander.OpdsTemplateException(i, "invalid Unicode scalar")
                1
            }
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~" || allowReserved && c in ":/?#[]@!$&'()*+,;=") {
                append(c)
            } else {
                for (byte in text.substring(i, i + width).encodeToByteArray()) {
                    append('%').append((byte.toInt() and 255).toString(16).uppercase().padStart(2, '0'))
                }
            }
            i += width
        }
    }
}
