package com.retro99.opds.implementation.opds2

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.OpdsUrlResolver

/** Phase 1 step 4 (not yet implemented): OPDS 2.0 JSON parser. */
internal class Opds2Parser(
    private val resolver: OpdsUrlResolver,
) : OpdsParser {

    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult {
        throw NotImplementedError("OPDS2 parser is Phase 1 step 4 (not yet implemented)")
    }
}
