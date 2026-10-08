package com.retro99.opds.implementation.localization

import com.retro99.opds.api.*
import com.retro99.opds.api.model.*
import com.retro99.opds.implementation.ParserFactory
import kotlin.test.*

class LocalizedTextTest {
    private fun document(body: String, type: String) = (ParserFactory.opdsParser().parse(
        OpdsPayload(type, body.encodeToByteArray()), "https://example.org/root") as OpdsParseResult.Document).document

    @Test fun json_keeps_all_localized_fields_without_selecting() {
        val book = (document("""{"metadata":{"title":{"und":"Landscape","de":"Landschaft"},"author":{"name":{"en":"Writer","fr":"Auteur"}},"description":{"en":"Description","fr":"Texte"},"rights":{"en":"Rights","fr":"Droits"},"publisher":{"name":{"en":"Press","fr":"Presse"}},"edition":{"en":"Revised","fr":"Révisée"}},"links":[{"href":"/file","title":{"en":"File","fr":"Fichier"}}]}""", "application/opds-publication+json") as OpdsPublicationDocument).publication
        assertEquals(mapOf("und" to "Landscape", "de" to "Landschaft"), book.title.translations)
        assertEquals(mapOf("en" to "Writer", "fr" to "Auteur"), book.authors.single().name.translations)
        assertEquals(mapOf("en" to "Description", "fr" to "Texte"), book.content?.body?.translations)
        assertEquals("Droits", book.rights?.select(listOf("fr")))
        assertEquals("Presse", book.publisher?.select(listOf("fr")))
        assertEquals("Révisée", book.editionLabel?.select(listOf("fr")))
        assertEquals("Fichier", book.links.single().title?.select(listOf("fr")))
    }
    @Test fun preference_order_exact_primary_und_then_first() {
        val text = OpdsText(linkedMapOf("fr" to "French", "en-GB" to "British", "en" to "English", "und" to "Default"))
        assertEquals("British", text.select(listOf("EN-gb")))
        assertEquals("English", text.select(listOf("en-US", "fr")))
        assertEquals("French", text.select(listOf("fr", "en-GB")))
        assertEquals("Default", text.select(listOf("es")))
        assertEquals("Default", text.select(emptyList()))
        assertEquals("French", OpdsText(linkedMapOf("fr" to "French", "de" to "German")).select(listOf("ja")))
        assertNull(OpdsText(emptyMap()).select(listOf("en")))
        assertEquals("British", OpdsText(mapOf("en-GB" to "British")).select(listOf("en-US")))
    }
    @Test fun xml_text_inherits_and_overrides_language() {
        val feed = document("""<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en"><id>urn:feed</id><title>Feed</title><entry xml:lang="de"><id>urn:book</id><title>Buch</title><summary xml:lang="fr">Résumé</summary><rights>Rechte</rights><author><name xml:lang="it">Autore</name></author><content type="text">Inhalt</content><link rel="http://opds-spec.org/acquisition" href="/book" title="Datei" type="application/epub+zip"/></entry></feed>""", "application/atom+xml") as OpdsFeedDocument
        assertEquals(mapOf("en" to "Feed"), feed.metadata.title.translations)
        val book = feed.publications.single()
        assertEquals(mapOf("de" to "Buch"), book.title.translations)
        assertEquals(mapOf("fr" to "Résumé"), book.summary?.translations)
        assertEquals(mapOf("de" to "Rechte"), book.rights?.translations)
        assertEquals(mapOf("it" to "Autore"), book.authors.single().name.translations)
        assertEquals(mapOf("de" to "Inhalt"), book.content?.body?.translations)
        assertEquals(mapOf("de" to "Datei"), book.links.single().title?.translations)
    }
}
