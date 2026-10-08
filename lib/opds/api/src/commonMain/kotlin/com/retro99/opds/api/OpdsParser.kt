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
    fun asText(): String = bytes.decodeToString()
}

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
