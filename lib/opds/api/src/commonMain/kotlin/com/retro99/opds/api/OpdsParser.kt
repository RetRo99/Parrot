package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsDocument
import com.retro99.opds.api.model.OpdsRejection

/**
 * Structured content of one fetched OPDS resource.
 */
data class OpdsPayload(
    val mediaTypeHeader: String?,
    /** The undecoded bytes, bounded by transport budgets. */
    val bytes: ByteArray,
) {
    /** HTTP charset wins over the XML declaration; supported identically on both targets. */
    fun asText(): String {
        val headerCharset = Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
            .find(mediaTypeHeader.orEmpty())?.let { it.groups[1]?.value ?: it.groups[2]?.value }
        // XML declarations are ASCII-compatible in the supported encodings.
        val prefix = bytes.take(1024).map { (it.toInt() and 255).toChar() }.joinToString("").removePrefix("\u00ef\u00bb\u00bf")
        val declaration = if (prefix.startsWith("<?xml")) prefix.substringBefore("?>") else ""
        val xmlCharset = Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]").find(declaration)?.groupValues?.get(1)
        return when ((headerCharset ?: xmlCharset ?: "UTF-8").lowercase()) {
            "utf-8", "utf8" -> try {
                bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
            } catch (_: Exception) {
                throw IllegalArgumentException("invalid UTF-8 encoding")
            }
            "iso-8859-1", "iso8859-1", "latin1", "latin-1" -> buildString(bytes.size) {
                for (byte in bytes) append((byte.toInt() and 255).toChar())
            }
            "us-ascii", "ascii" -> {
                if (bytes.any { it < 0 }) throw IllegalArgumentException("invalid ASCII encoding")
                bytes.decodeToString()
            }
            else -> throw UnsupportedOpdsEncodingException()
        }
    }
}

/** Never includes the untrusted charset label or payload. */
class UnsupportedOpdsEncodingException : IllegalArgumentException("unsupported encoding")

sealed interface OpdsParseResult {
    data class Document(val document: OpdsDocument) : OpdsParseResult
    data class Rejected(val rejection: OpdsRejection) : OpdsParseResult
}

/**
 * Parser interface for lib/opds/implementation implementations (plan §3.1);
 * version-agnostic: the implementation detects OPDS 1.x vs 2.0 from content.
 */
interface OpdsParser {
    fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult
}
