package com.retro99.catalogue.ui.add

actual object CatalogueHttpPolicy {
    actual val allowHttp: Boolean = ALLOW_HTTP_CATALOGUES

    // Platform policy: Android permits explicitly confirmed anonymous HTTP catalogues.
    private const val ALLOW_HTTP_CATALOGUES = true
}
