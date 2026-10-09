package com.retro99.opds.implementation

import com.retro99.opds.api.OpdsParseResult
import com.retro99.opds.api.OpdsPayload
import com.retro99.opds.api.model.OpdsPublicationDocument
import kotlin.test.*

class PublicationSubjectsTest {
    @Test fun atom_categories_keep_label_or_term_and_omit_empty_categories() {
        val xml = """<entry xmlns="http://www.w3.org/2005/Atom"><id>book</id><title>Book</title><category term="adventure" label="Adventure"/><category term="Pirates"/><category/></entry>"""
        assertEquals(listOf("Adventure", "Pirates"), subjects("application/atom+xml", xml))
    }
    @Test fun json_subjects_accept_names_and_strings_without_losing_catalogue_order() {
        val json = """{"metadata":{"title":"Book","subject":["Adventure",{"name":"Pirates","code":"123"},{}]},"links":[]}"""
        assertEquals(listOf("Adventure", "Pirates"), subjects("application/opds+json", json))
    }
    private fun subjects(type: String, value: String): List<String> {
        val parsed = assertIs<OpdsParseResult.Document>(ParserFactory.opdsParser().parse(OpdsPayload(type, value.encodeToByteArray()), "https://books.example/book"))
        return assertIs<OpdsPublicationDocument>(parsed.document).publication.subjects.map { it.translations.values.first() }
    }
}
