package com.retro99.opds.implementation.fixtures

/**
 * iOS actual: reads the embedded registry, whose raw strings are margin-
 * decoded to exactly the resource file bytes (K/N Gradle simulator tests have
 * no bundle; see the Phase 0 spike record and the fixture-loading strategy in
 * docs/opds-phase0-spikes.md).
 */
internal actual fun readFixture(name: String): ByteArray {
    val fixture = EmbeddedFixtures.sources[name.removePrefix("/")]
        ?: throw AssertionError("no embedded fixture source for '$name' (EmbeddedFixtures is stale?)")
    return fixture.trimMargin("'").encodeToByteArray()
}

internal actual val platformTag: String get() = "ios-simulator"
