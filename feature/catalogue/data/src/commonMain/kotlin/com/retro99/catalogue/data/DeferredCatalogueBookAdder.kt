package com.retro99.catalogue.data

import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueBookAdder
import com.retro99.catalogue.domain.StagedCatalogueBook

/**
 * Leaves a checked download in staging. Adding it to the library through the staged book
 * import is the next step of Phase 3; until then a download stops at "adding".
 */
internal class DeferredCatalogueBookAdder : CatalogueBookAdder {
    override suspend fun add(profileId: String, book: StagedCatalogueBook): CatalogueBookAddResult =
        CatalogueBookAddResult.NotAddedYet
}
