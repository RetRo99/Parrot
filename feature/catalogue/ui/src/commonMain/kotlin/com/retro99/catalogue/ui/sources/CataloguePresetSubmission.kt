package com.retro99.catalogue.ui.sources

import com.retro99.catalogue.ui.add.CatalogueAddFlow

/** Clearing the effect's request key before submit finishes cancels its own network check. */
internal suspend fun submitCataloguePreset(flow: CatalogueAddFlow, name: String, onConsumed: () -> Unit) {
    flow.submit(name = name)
    onConsumed()
}
