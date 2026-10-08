package com.retro99.opds.implementation.encoding

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import kotlin.test.*

class OpdsEncodingTest {
    private fun feed(encoding: String) = """<?xml version="1.0" encoding="$encoding"?><feed xmlns="http://www.w3.org/2005/Atom"><id>urn:test</id><title>Café</title></feed>"""
    private fun title(payload: OpdsPayload): String {
        val result = ParserFactory.opdsParser().parse(payload, "https://example.org/feed") as OpdsParseResult.Document
        return (result.document as OpdsFeedDocument).metadata.title
    }
    @Test fun utf8_header_and_declaration() {
        assertEquals("Café", title(OpdsPayload("application/atom+xml; charset=\"UTF-8\"", feed("UTF-8").encodeToByteArray())))
    }
    @Test fun latin1_xml_declaration() {
        assertEquals("Café", title(OpdsPayload("application/atom+xml", feed("ISO-8859-1").map { it.code.toByte() }.toByteArray())))
    }
    @Test fun latin1_content_type_takes_precedence() {
        assertEquals("Café", title(OpdsPayload("application/atom+xml; charset=ISO-8859-1", feed("UTF-8").map { it.code.toByte() }.toByteArray())))
    }
    @Test fun unsupported_charset_fails_clearly() {
        for (payload in listOf(
            OpdsPayload("application/atom+xml; charset=Shift_JIS", feed("UTF-8").encodeToByteArray()),
            OpdsPayload("application/atom+xml", feed("Shift_JIS").encodeToByteArray()))) {
            val result = ParserFactory.opdsParser().parse(payload, "https://example.org/feed")
            assertIs<OpdsParseResult.Rejected>(result)
            assertEquals("unsupported encoding", result.rejection.note)
        }
    }
    @Test fun utf8_bom_keeps_xml_declaration_encoding() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + feed("Shift_JIS").encodeToByteArray()
        val result = ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml", bytes), "https://example.org/feed")
        assertIs<OpdsParseResult.Rejected>(result)
        assertEquals("unsupported encoding", result.rejection.note)
    }
    @Test fun invalid_utf8_is_not_silently_replaced() {
        val result = ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml; charset=UTF-8", feed("UTF-8").map { it.code.toByte() }.toByteArray()), "https://example.org/feed")
        assertIs<OpdsParseResult.Rejected>(result)
        assertIs<OpdsRejection.Malformed>(result.rejection)
    }
}
