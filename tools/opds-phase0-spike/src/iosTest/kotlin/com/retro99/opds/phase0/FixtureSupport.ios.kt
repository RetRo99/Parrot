package com.retro99.opds.phase0

/**
 * iOS actual: the Phase 0 spike recorded that K/N Gradle simulator tests run
 * from an executable without a bundle, and common test resources are not
 * readable there (see docs/opds-phase0-spikes.md). Fixture bytes therefore
 * come from the generated [EmbeddedFixtures] registry, whose raw strings are
 * margin-decoded back to exactly the resource file bytes.
 */
internal actual fun readFixture(name: String): ByteArray {
    val fixture = EmbeddedFixtures.sources[name.removePrefix("/")]
        ?: throw AssertionError("no embedded fixture source for '$name' (EmbeddedFixtures.kt is stale?)")
    return fixture.trimMargin("'").encodeToByteArray()
}

/** Tag recorded alongside observed behaviors in the Phase 0 report. */
internal actual val platformTag: String get() = "ios-simulator"
