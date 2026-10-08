package com.retro99.opds.implementation

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import kotlin.test.*

class LinkBaseRetentionTest {
    @Test fun inherited_link_base_survives_normalization_for_the_server_adapter() {
        val parsed = ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml", """<feed xmlns="http://www.w3.org/2005/Atom" xml:base="shelf/"><id>urn:feed</id><title>Books</title><link rel="search" href="search.xml" type="application/opensearchdescription+xml" xml:base="../find/"/></feed>""".encodeToByteArray()), "https://books.example/opds/")
        val feed = assertIs<OpdsFeedDocument>(assertIs<OpdsParseResult.Document>(parsed).document)
        assertEquals("https://books.example/opds/find/", feed.search?.link?.effectiveBaseUri)
    }
}
