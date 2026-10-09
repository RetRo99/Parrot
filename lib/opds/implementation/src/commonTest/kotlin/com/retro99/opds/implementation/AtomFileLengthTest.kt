package com.retro99.opds.implementation

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsPublicationDocument
import kotlin.test.*

class AtomFileLengthTest {
    @Test fun declared_file_length_is_preserved_and_invalid_lengths_are_absent() {
        for ((value, expected) in listOf("274139" to 274139L, "0" to 0L, "-1" to null, "unknown" to null, "9223372036854775808" to null)) {
            val xml = """<entry xmlns="http://www.w3.org/2005/Atom"><id>book</id><title>Book</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="book.epub" length="$value"/></entry>"""
            val parsed = assertIs<OpdsParseResult.Document>(ParserFactory.opdsParser().parse(OpdsPayload("application/atom+xml", xml.encodeToByteArray()), "https://books.example/book"))
            val publication = assertIs<OpdsPublicationDocument>(parsed.document).publication
            assertEquals(expected, publication.links.single().lengthBytes, "length=$value")
        }
    }
}
