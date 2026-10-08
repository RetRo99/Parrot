package com.retro99.opds.phase0

/**
 * Loads a fixture from shared test resources on every target that runs the
 * spike tests.
 *
 * Expect/actual across `commonTest` → `androidHostTest` / `iosTest` is itself a
 * Phase 0 finding: it verifies that shared test resources (and a test-side
 * expect/actual pair) work on both platforms, which Phase 1's fixture-driven
 * parser tests rely on.
 */
internal expect fun readFixture(name: String): ByteArray

/** All fixtures are UTF-8 text. */
internal fun readFixtureText(name: String): String = readFixture(name).decodeToString()

/** Tag recorded alongside observed behaviors in the Phase 0 report (per target). */
internal expect val platformTag: String

/** Fixture paths, to be moved verbatim into Phase 1 test resources. */
internal object Fixtures {
    const val OPDS1_LISTING = "opds/opds1/listing.xml"
    const val OPDS1_ACQUISITION = "opds/opds1/verses-acquisition.xml"
    const val OPDS1_FULL_ENTRY = "opds/opds1/treatise-entry.xml"
    const val OPDS1_OPEN_SEARCH = "opds/opds1/osd.xml"
    const val OPDS1_CALIBRE_NEWEST = "opds/opds1/calibre-newest.xml"
    const val OPDS2_CATALOG = "opds/opds2/catalog.json"
    const val OPDS2_PUBLICATION = "opds/opds2/landscape.json"
    const val ERROR_HTML = "opds/error.html"
    const val DTD_BASELINE = "opds/dtd-baseline.xml"
    const val DTD_EXTERNAL = "opds/dtd-external.xml"
}
