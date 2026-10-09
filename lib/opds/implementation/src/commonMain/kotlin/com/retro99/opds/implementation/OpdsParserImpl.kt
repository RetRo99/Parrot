package com.retro99.opds.implementation

import com.retro99.opds.api.OpdsContentType
import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsParser
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.OpdsUrlResolver
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.detect.OpdsDocumentDetector
import com.retro99.opds.implementation.opds1.Opds1Parser
import com.retro99.opds.implementation.opds2.Opds2Parser
import com.retro99.opds.implementation.url.Rfc3986ReferenceResolver

/** Test/test-support factory for the parsed OPDS document pipeline. */
object ParserFactory {
    fun urlResolver(): OpdsUrlResolver = Rfc3986ReferenceResolver()

    fun opdsParser(): OpdsParser = OpdsParserImpl(urlResolver())
}

/**
 * Version-dispatching parser (plan §3.1): detection first, then OPDS1/OPDS2
 * parsing onto the same normalized model. One source may link between the two
 * versions (plan §1), but one body is exactly one document.
 */
internal class OpdsParserImpl(
    private val resolver: OpdsUrlResolver,
) : OpdsParser {

    private val detector = OpdsDocumentDetector()

    override fun parse(payload: OpdsPayload, effectiveResponseUrl: String): OpdsParseResult =
        when (val kind = detector.detectBody(payload)) {
            is OpdsContentType.Rejected -> OpdsParseResult.Rejected(kind.rejection)

            OpdsContentType.NotACatalogue ->
                OpdsParseResult.Rejected(
                    OpdsRejection.NotACatalogue("structure is not an OPDS document"),
                )

            OpdsContentType.FEED, OpdsContentType.PUBLICATION ->
                if (payload.asText().trimStart().startsWith("{")) {
                    Opds2Parser(resolver).parse(payload, effectiveResponseUrl)
                } else {
                    Opds1Parser(resolver).parse(payload, effectiveResponseUrl)
                }

            OpdsContentType.OPEN_SEARCH_DESCRIPTION ->
                OpdsParseResult.Rejected(OpdsRejection.NotACatalogue("descriptor documents are not feed bodies"))
        }
}
