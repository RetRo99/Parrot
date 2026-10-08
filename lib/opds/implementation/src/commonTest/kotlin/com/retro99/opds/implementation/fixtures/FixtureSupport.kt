package com.retro99.opds.implementation.fixtures

/**
 * Loads a fixture from shared test resources on every target that runs the
 * fixture tests.
 *
 * Loading strategy (from the Phase 0 spike; see
 * docs/opds-phase0-spikes.md):
 * - `src/commonTest/resources/opds/` is the authored source of truth;
 * - Gradle-built Kotlin/Native simulator tests run from an executable without
 *   a bundle, so common test resources are NOT readable there — the embedded
 *   [EmbeddedFixtures] registry (same content, checked in) is read instead;
 * - Android host tests read the resource files directly, and the parity test
 *   proves the two representations stay identical.
 */
internal expect fun readFixture(name: String): ByteArray

internal fun readFixtureText(name: String): String = readFixture(name).decodeToString()

/** Tag recorded alongside observed behaviors (kept for future per-target pins). */
internal expect val platformTag: String
