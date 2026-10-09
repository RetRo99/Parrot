package com.retro99.catalogue.ui.navigation

import com.retro99.server.api.CatalogueTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CatalogueRouteReferencesTest {
    private class Place(val address: String) : CatalogueTarget

    @Test fun a_reference_leads_back_to_its_place_and_says_nothing_about_it() {
        val references = CatalogueRouteReferences()
        val place = Place("https://kavita.home.lan/api/opds/3f2a9c1e-7b4d-4e2a-9c1e-5d6f7a8b9c0d/series/12?apikey=SECRET")

        val reference = references.referenceTo("source", place)

        assertSame(place, references.target("source", reference))
        assertTrue(Regex("[a-z0-9]{1,24}").matches(reference), reference)
        listOf("kavita", "SECRET", "3f2a", "/", ":", "?").forEach { assertFalse(it in reference, reference) }
    }

    @Test fun a_reference_belongs_to_one_catalogue() {
        val references = CatalogueRouteReferences()
        val reference = references.referenceTo("source", Place("a"))

        assertNull(references.target("other", reference))
        assertNull(references.target("source", "r999"))
        assertNull(references.target("source", "https://books.example/opds"))
    }

    @Test fun every_place_gets_its_own_reference() {
        val references = CatalogueRouteReferences()
        val first = Place("a")
        val second = Place("b")

        val one = references.referenceTo("source", first)
        val two = references.referenceTo("source", second)

        assertNotEquals(one, two)
        assertSame(first, references.target("source", one))
        assertSame(second, references.target("source", two))
    }

    @Test fun forgetting_a_catalogue_keeps_the_others() {
        val references = CatalogueRouteReferences()
        val gone = references.referenceTo("gone", Place("a"))
        val kept = references.referenceTo("kept", Place("b"))

        references.forget("gone")

        assertNull(references.target("gone", gone))
        assertEquals("b", (references.target("kept", kept) as Place).address)

        references.clear()
        assertNull(references.target("kept", kept))
    }

    @Test fun only_the_newest_are_kept_and_a_reference_is_never_given_twice() {
        val references = CatalogueRouteReferences()
        val all = (0 until CatalogueRouteReferences.MAX_REFERENCES + 50).map { references.referenceTo("source", Place("$it")) }

        assertEquals(all.size, all.toSet().size)
        assertNull(references.target("source", all.first()))
        assertNull(references.target("source", all[49]))
        assertEquals("50", (references.target("source", all[50]) as Place).address)
        assertEquals("${all.size - 1}", (references.target("source", all.last()) as Place).address)
    }
}
