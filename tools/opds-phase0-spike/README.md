# OPDS Phase 0 spike harness

This module is the Phase 0 "focused compatibility/security spikes" harness from
`docs/opds-server-implementation-plan.md` §7. It holds:

1. **Fixtures** in `src/commonTest/resources/opds/` — small synthetic OPDS 1.x,
   OPDS 2.0, OpenSearch, and hostile-input documents. They are the fixture set
   Phase 1 tests assert against; Phase 1 must move them into
   `lib/opds/implementation` test resources (or copy them) and must not depend on
   this module's code.
2. **Spike tests** that record parser/transport/URL behaviors on both targets
   (Android host tests + iOS simulator tests).

## Fixture provenance and licensing

All fixtures are **synthetic**: their titles, names, descriptions, and URLs are
invented here, modeled on the *structural* shapes observed during the research
of the plan (§2.2, §2.3) but containing no third-party content. The RFC 3986 and
RFC 6570 test vectors used in the spike tests are quoted from the IETF published
RFCs (RFC 3986, RFC 6570). IETF RFCs may be reproduced subject to
Copyright Section 3.8 of the IETF TRD: https://trustee.ietf.org/license-info/
(each vector set cites its section; full RFC text is not copied here).
The OpenSearch `{startPage?}` shape follows the OpenSearch 1.1 parameter rules
already cited by the plan's research sources.

## Fixture loading strategy (Phase 0 finding)

Gradle-built Kotlin/Native simulator tests run as an executable **without an
app bundle**, so `src/commonTest/resources` is not readable on iOS (NSBundle
lookups fail there; NSBundle.mainBundle points at no usable bundle). The
fixtures therefore have two synchronized representations:

1. `src/commonTest/resources/opds/` — the authored fixture files (this is the
   source of truth; Phase 1 copies/moves them into `lib/opds/implementation`).
2. `src/commonTest/kotlin/com/retro99/opds/phase0/EmbeddedFixtures.kt` — the
   checked-in embedded copies the iOS actual reads.

An Android-host parity test
(`embedded fixture registry matches the resource fixture files`) proves the two
stay identical — Android is the one target that can read the resource files.
If a fixture file changes, mirror the change in `EmbeddedFixtures.kt` (same
format; the parity test will fail otherwise).

## Run

```
./gradlew :tools:opds-phase0-spike:testAndroidHostTest :tools:opds-phase0-spike:iosSimulatorArm64Test
```

(Verify task names with `./gradlew :tools:opds-phase0-spike:tasks --all`.)

## Fixture inventory

| Fixture | Purpose |
| --- | --- |
| `opds/opds1/listing.xml` | OPDS1 navigation feed (Gutenberg-style two-step browsing: relative `subsection` links, `start`/`next`, OpenSearch link, data-URI thumbnail, inherited `xml:base`) |
| `opds/opds1/verses-acquisition.xml` | OPDS1 acquisition feed: one work, two edition entries sharing one title; the first edition exposes **two** EPUB links |
| `opds/opds1/treatise-entry.xml` | OPDS1 standalone full entry with CDATA HTML content and inherited `xml:base` |
| `opds/opds1/osd.xml` | OpenSearch 1.1 description with `{searchTerms}` and optional `{startPage?}` |
| `opds/opds1/calibre-newest.xml` | Calibre-shaped feed where entries carry acquisition links and titles collide across different authors (grouping-rule negative case) |
| `opds/opds2/catalog.json` | OPDS2 feed: navigation, groups, facets, pagination, URI-template search link, contributor forms |
| `opds/opds2/landscape.json` | OPDS2 publication: localized title, contributor object/array forms, responsive images, indirect acquisition, priced buy link |
| `opds/error.html` | Non-catalogue body that document detection must reject |
| `opds/dtd-baseline.xml` | Bounded internal DTD entity tree — records what parsers actually do |
| `opds/dtd-external.xml` | External entity with an absent target — records external-entity behavior |
