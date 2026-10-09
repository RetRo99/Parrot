package com.retro99.catalogue.ui.sources

import com.retro99.catalogue.ui.add.*
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CataloguePresetFlagTest {
    @Test fun optionalFlagDefaultsFalseAndIsParsedOnlyFromData() {
        fun json(extra: String) = """[{"id":"source","name":"Name","address":"https://example.org/opds","description":"D","shortDescription":"D","accountLabel":"No account needed","needsAccount":false,"termsUrl":null$extra}]"""
        assertFalse(parseCataloguePresets(json("")).single().listEntriesAreBooks)
        assertTrue(parseCataloguePresets(json(",\"listEntriesAreBooks\":true")).single().listEntriesAreBooks)
    }
    @Test fun presetFlagIsPassedToStoreButAddingByAddressNeverSetsIt() = runTest {
        val flags = mutableListOf<Boolean>()
        val store = object : CatalogueAddStore {
            override suspend fun existingAddresses() = emptySet<String>()
            override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String { flags += false; return "source" }
            override suspend fun addPresetValidated(name: String, address: String, account: OpdsAccountDetails?, listEntriesAreBooks: Boolean): String { flags += listEntriesAreBooks; return "source" }
        }
        val validator = CatalogueAddressValidator { _, _ -> CatalogueValidation.Accepted() }
        CatalogueAddFlow(validator, store, true, "https://example.org/opds", listEntriesAreBooks = true).submit()
        CatalogueAddFlow(validator, store, true, "https://example.org/opds").submit()
        assertEquals(listOf(true, false), flags)
    }
}
