package com.retro99.catalogue.ui.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CataloguePresetTest {
    @Test
    fun parses_known_presets_with_verified_secure_links_and_accounts() {
        val presets = parseCataloguePresets(PRESETS)

        assertEquals(listOf("project-gutenberg", "standard-ebooks"), presets.map { it.id })
        assertEquals("gutenberg.org", presets.first().host)
        assertFalse(presets.first().needsAccount)
        assertTrue(presets.first().termsUrl!!.startsWith("https://"))
        assertTrue(presets.last().needsAccount)
        assertNull(presets.last().termsUrl)
    }

    @Test
    fun preset_list_hides_an_address_that_is_already_registered() {
        val presets = parseCataloguePresets(PRESETS)

        assertEquals(
            listOf("standard-ebooks"),
            availableCataloguePresets(presets, setOf(presets.first().address)).map { it.id },
        )
    }

    @Test
    fun refuses_insecure_preset_addresses_and_links() {
        val insecureAddress = PRESETS.replace("https://www.gutenberg.org/ebooks/search.opds/", "http://example.org/opds")
        val insecureTerms = PRESETS.replace("https://www.gutenberg.org/policy/terms_of_use.html", "http://example.org/terms")

        kotlin.test.assertFails { parseCataloguePresets(insecureAddress) }
        kotlin.test.assertFails { parseCataloguePresets(insecureTerms) }
    }

    private companion object {
        val PRESETS = """
            [
              {"id":"project-gutenberg","name":"Project Gutenberg","address":"https://www.gutenberg.org/ebooks/search.opds/","description":"Full","shortDescription":"Short","accountLabel":"No account needed","needsAccount":false,"termsUrl":"https://www.gutenberg.org/policy/terms_of_use.html"},
              {"id":"standard-ebooks","name":"Standard Ebooks","address":"https://standardebooks.org/feeds/opds","description":"Full","shortDescription":"Short","accountLabel":"Patron account needed","needsAccount":true,"termsUrl":null}
            ]
        """.trimIndent()
    }
}
