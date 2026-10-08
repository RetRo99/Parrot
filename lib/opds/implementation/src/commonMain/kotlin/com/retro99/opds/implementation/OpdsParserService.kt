package com.retro99.opds.implementation

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload

interface OpdsParserService : OpdsParser {
    companion object {
        /** Singleton name used in tests before dependent composition; parse is phase-tested. */
        const val NOT_IMPLEMENTED = "this parser behavior is not implemented yet (phase 1 step in progress)"
    }
}

/** Temporary skeleton for test-first runs; replaced during Phase 1. */
internal class ParserStub : OpdsParser {
    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult {
        error(OpdsParserService.NOT_IMPLEMENTED)
    }
}
