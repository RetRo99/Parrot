package com.retro99.opds.phase0.rfc6570

/**
 * Phase 0 spike: a bounded RFC 6570 subset, exactly the declared supported
 * level for OPDS2 search in the plan (§4: "RFC 6570 expansion with `query` and
 * advertised optional advanced fields … Unsupported expressions must fail
 * explicitly"):
 *
 * - literal text,
 * - simple string expansion `{var}` and `{var1,var2}`,
 * - reserved expansion `{+var}`,
 * - form-style query expansion `{?var}` and `{&var}` (scalar values only).
 *
 * Everything else (other operators, `*` explode modifiers, prefix
 * modifiers, empty expressions, unmatched braces) fails with
 * [Rfc6570Spike.UnsupportedTemplateException] instead of silently mangling a
 * search URL. Vectors quoted from RFC 6570 §2.4/§3.2.1/§3.2.7 (license note:
 * tools/opds-phase0-spike/README.md).
 */
object Rfc6570Spike {

    class UnsupportedTemplateException(template: String, at: Int, why: String) :
        IllegalStateException("RFC 6570 template '$template' at offset $at: $why")

    /** @param variables scalar values only; an absent key means "undefined". */
    fun expand(template: String, variables: Map<String, String>): String {
        val out = StringBuilder()
        var index = 0
        while (index < template.length) {
            when (val c = template[index]) {
                '{' -> index = expandExpression(template, index, variables, out)
                '}' -> throw UnsupportedTemplateException(template, index, "unmatched '}'")
                else -> {
                    checkLiteral(template, index)
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }

    /** RFC 6570 §2.1 literals: reject characters that cannot appear in a template. */
    private fun checkLiteral(template: String, index: Int) {
        when (val c = template[index]) {
            '%' -> {
                val isTriplet = index + 2 <= template.lastIndex &&
                    template[index + 1].isHexChar() && template[index + 2].isHexChar()
                if (!isTriplet) {
                    throw UnsupportedTemplateException(template, index, "bare '%' literal (must be a pct-encoded triplet)")
                }
            }
            ' ' -> throw UnsupportedTemplateException(template, index, "space literal")
            '"', '\'', '<', '>', '\\', '^', '`', '{', '}', '|' ->
                throw UnsupportedTemplateException(template, index, "disallowed literal")
            else -> {
                if (c.code in 0x00..0x20) throw UnsupportedTemplateException(template, index, "control/whitespace literal")
                if (c.code == 0x7F) throw UnsupportedTemplateException(template, index, "disallowed literal")
                // Non-ASCII literals are tolerated (§2.1 allows ucschar/iprivate).
                // Phase 1 must pct-encode them per §3.1; the spike passes them
                // through to keep the encoder under test focused on variables.
            }
        }
    }

    private fun expandExpression(
        template: String,
        start: Int,
        variables: Map<String, String>,
        out: StringBuilder,
    ): Int {
        val end = template.indexOf('}', start)
        if (end == -1) throw UnsupportedTemplateException(template, start, "unmatched '{'")
        if (end == start + 1) throw UnsupportedTemplateException(template, start, "empty expression")
        val expression = template.substring(start + 1, end)
        if (expression.first() == '{' || expression.first() == '}') {
            throw UnsupportedTemplateException(template, start, "nested braces")
        }

        val operator: Char? = when (val first = expression.first()) {
            '+', '?', '#', '.', '/', ';', '&', '@', ',', '|', '!', '$' -> first
            else -> null
        }
        if (operator != null && operator !in "+?&") {
            throw UnsupportedTemplateException(
                template, start,
                "unsupported operator '$operator' (declared level: 'var', '+var', '?list', '&list')",
            )
        }
        val names = expression.drop(if (operator == null) 0 else 1)

        val terms = names.split(',')
        for (term in terms) {
            if (term.isEmpty()) throw UnsupportedTemplateException(template, start, "empty variable name")
            if ('*' in term || ':' in term) {
                throw UnsupportedTemplateException(template, start, "compose/explode/prefix modifiers are not supported")
            }
            if (!isValidVarname(term)) {
                throw UnsupportedTemplateException(
                    template, start,
                    "variable name '$term' is not a RFC 6570 varname (letter/digit first; '%'/'.'/'_'/pct-triplet inside)",
                )
            }
        }

        if (operator == '?' || operator == '&') {
            val defined = terms.mapNotNull { name -> variables[name]?.let { name to it } }
            if (defined.isNotEmpty()) {
                out.append(operator).append(
                    defined.joinToString("&") { (name, value) ->
                        "${simpleEncode(name)}=${simpleEncode(value)}"
                    },
                )
            }
            return end + 1
        }

        // Simple/reserved string expansion: defined variables in declaration
        // order, comma-separated (RFC 6570 §3.2.1); undefined ones vanish.
        val defined = terms.mapNotNull { name -> variables[name] ?: return@mapNotNull null }
        if (defined.isNotEmpty()) {
            out.append(defined.joinToString(",") { value ->
                when (operator) {
                    null -> simpleEncode(value)
                    else -> reservedEncode(value)
                }
            })
        }
        return end + 1
    }

    /** RFC 6570 simple string expansion: unreserved ASCII kept, rest %-encoded. */
    private fun simpleEncode(text: String): String = encode(text, allowReserved = false)

    /**
     * RFC 6570 reserved expansion keeps reserved characters ('/' ':' '?' '@'…
     * and already-encoded `%XX` octets) literal; spaces and non-ASCII chars are
     * still percent-encoded.
     */
    private fun reservedEncode(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '%' && index + 2 < text.length &&
                text[index + 1].isHexChar() && text[index + 2].isHexChar()
            ) {
                out.append(text, index, index + 3)
                index += 3
                continue
            }
            encodeChar(text[index], out, allowReserved = true)
            index++
        }
        return out.toString()
    }

    private fun encode(text: String, allowReserved: Boolean): String {
        val out = StringBuilder()
        for (ch in text) encodeChar(ch, out, allowReserved)
        return out.toString()
    }

    private fun encodeChar(ch: Char, out: StringBuilder, allowReserved: Boolean) {
        val code = ch.code
        if (allowReserved && code in RESERVED) {
            out.append(ch)
            return
        }
        for (byte in ch.toString().encodeToByteArray()) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned in UNRESERVED) {
                out.append(unsigned.toChar())
            } else {
                out.append('%').append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private fun Char.isHexChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** RFC 6570 §2.3: varname = varchar *(["."] varchar), varchar = ALPHA / DIGIT / "_" / pct-encoded. */
    private fun isValidVarname(name: String): Boolean {
        if (name.isEmpty()) return false
        return name.split(".").all { segment -> segment.isNotEmpty() && segment.all { it.isAlphaOrDigit() || it == '_' || it == '%' } }
    }

    private fun Char.isAlphaOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private val UNRESERVED = listOf(0x61..0x7A, 0x41..0x5A, 0x30..0x39).flatMap { it.toList() } +
        listOf('-'.code, '.'.code, '_'.code, '~'.code)

    /** RFC 6570 reserved set ('gen-delims' + 'sub-delims') for reserved expansion. */
    private val RESERVED = listOf(
        ':', '/', '?', '#', '[', ']', '@', '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=',
    ).map { it.code }
}
