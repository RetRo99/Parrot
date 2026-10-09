package com.retro99.catalogue.ui.add

actual object CatalogueHttpPolicy {
    actual val allowHttp: Boolean = ALLOW_HTTP_CATALOGUES

    // Platform policy: iOS blocks HTTP catalogues for now; flip this value to enable them.
    private const val ALLOW_HTTP_CATALOGUES = false
}
