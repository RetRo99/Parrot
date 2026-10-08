# OPDS Phase 0 — spike results and gate report

**Plan:** `docs/opds-server-implementation-plan.md` §7 Phase 0 (focused
compatibility/security spikes), §10.0 recorded decisions, §11.7 design passes.
**Date:** 2026-10-08. **Branch:** `opds/phase-0-spikes` (off
`docs/opds-catalogue-plan`). **Spike harness:** retired after Phase 1.

Fixtures now live in `lib/opds/implementation/src/commonTest/resources/opds` (with embedded iOS copies); ported protocol, search, transport and cache code lives in `lib/opds/implementation/src/commonMain/kotlin/com/retro99/opds/implementation`.

§10.0 decisions are final and echoed here only where a spike verified their
preconditions; nothing in this report reopens a recorded decision.

---

## 1. What ran

- Android host Phase 0 verification tests (JVM,
  xmlutil generic `KtXmlReader` via the default factory fallback).
- iOS simulator Phase 0 verification tests
  (same shared-ish generic reader path).
- Read-only live-provider checks (no credentials, no provider contact): Project
  Gutenberg pages/descriptor over HTTPS, Standard Ebooks `/feeds` page.

All same-named tests pass on both targets; platform-specific recorded
behaviors are pinned in the spike's `PinnedBehavior` and reproduced below.

## 2. Recorded parser behaviors (xmlutil 1.0.2, generic `KtXmlReader`)

These are the parser facts Phase 1's fixture tests will assert as *background*
(the hardened parser itself, with its own limits and error categories, is
Phase 1 work). One caveat to carry into Phase 1/device QA: both spike targets
ran the same generic reader path (the JVM host resolves no StAX factory in
this module configuration, and iOS/Native uses the generic reader), whereas a
**stock Android device build may route through the Android streaming
factory**. The behaviors below were verified equivalent on both spike targets;
the on-device Android engine remains to be exercised during device QA.

| Behavior | Recorded result (Android host + iOS simulator) |
| --- | --- |
| DTD documents | **NOT rejected automatically — this is REQUIRED Phase 1 work.** A well-formed document with an internal DTD is **accepted**: a `DOCDECL` event is delivered and the body is parsed. Internal entities **are resolved**: `expandEntities=true` expands to `TEXT`; `expandEntities=false` delivers the same characters as a chain of resolved `ENTITY_REF` events (container entities carry empty text, leaf entities carry their characters). An external entity (`<!ENTITY x SYSTEM "file:///etc/hosts">`) **is** rejected by xmlutil's own DOCTYPE parser with `XmlException: "Unexpected content in document type declaration"` — before any DOCDECL event or body content; no file access occurs. There is **no built-in expansion limit**: a 15-level nested tree (32,768 chars) is fully resolved/buffered in both modes, and with `expandEntities=false` the cost appears as a storm of `ENTITY_REF` events (one per leaf). So entity-expansion attacks survive the library as-shipped, and the Phase 1 parser must stop at the DOCDECL event (mitigation demonstrated in the spike: abort before any entity text is read; where the reader cannot decode the DOCTYPE at all, its clean `XmlException` is itself the stop). |
| Misordered documents | A comment before the declaration fails with `XmlException: "Unexpected START_DOCUMENT in state START_DOC"` regardless of any DTD; the dedicated fixture `comment-before-declaration.xml` keeps this failure cause separate from DOCTYPE handling (the original Phase 0 pin had conflated the two — corrected). |
| Predefined entity references (`&amp;` …) | Delivered as `ENTITY_REF` events whose `reader.text` already carries the resolved character (`&`). Text reconstruction must concatenate `TEXT` + `CDSECT` + `ENTITY_REF` text or the document text loses `&`. |
| CDATA | Delivered as `CDSECT` events (not "CDATA"); content is verbatim, including raw `&amp;`-looking text and inline HTML. |
| Namespaces | Default namespaces and prefixes (`opensearch:`, `dc:`, `opds:`) resolve to their URIs on elements; attributes resolve with an empty namespace for plain attributes and the `xml:` attributes under `http://www.w3.org/XML/1998/namespace`. |
| `xml:base` / `xml:lang` | Surfed as ordinary attributes (keyed `xml:base` in the spike collector). The reader does **not** resolve them: links stay as written at their declaring element; Phase 1 must do the RFC 3986 merge itself. |
| `data:` inline images | Round-trip unchanged as attribute values (fixture: 1×1 PNG in the navigation feed). Specific behavior of the *image loader* (Coil) is device QA — outstanding. |
| Reader state quirk | After `END_ELEMENT` the reader keeps the last element's attribute accessors (XMLPullParser convention). Phase 1 must only read attributes inside `START_ELEMENT`. |
| Unknown/extension elements | Surfaced like any element; a streaming parser can ignore them within its element/depth budget — recorded basis for the Phase 1 bounded-walker design. |
| Non-XML bodies | The XML reader either fails cleanly or tolerates; Phase 1 document detection must not rely on parse failure alone (media type + structure detection; `opds/error.html` fixture exists for that test). |

Test-side facts recorded for Phase 1:

- `xmlStreaming` (the public `IXmlStreaming` facade) is the entry point; the
  `XmlStreaming` object is internal on this xmlutil version.
- Backtick test identifiers on the Native target reject `()§,`-style
  characters (JVM accepts them): Phase 1 test names must stay to letters,
  digits, `_`, `-`, and spaces.
- StringBuilder on Native needs `deleteRange`/`appendRange` (the JVM
  pseudonyms used by the spike worked by accident); use common API in
  `lib/opds` from the start.

## 3. Decisions demonstrated (each backed by passing tests on both targets)

1. **Parser/base library** — shared xmlutil streaming parsing
   (`XmlStreaming`/`IXmlStreaming`) behind a small collector/validator layer;
   same behavior on both targets (tables above). No platform engine-specific
   parser is used, so Android and iOS behave identically for the same bytes.
   The library does **not** reject DTDs or entity expansion for us — a
   well-formed internal-DTD document is accepted, its internal entities are
   expanded (or delivered resolved), and there is no built-in expansion
   limit; only external `SYSTEM` entities are rejected by the library's own
   DOCTYPE parser. Phase 1 therefore MUST implement DTD rejection itself —
   stop at the DOCDECL event before any entity text is read (mitigation
   demonstrated and green on both targets).
2. **URL resolution** — hand-rolled RFC 3986 §5.2 resolver (the spike's
   `ReferenceResolver`) passes the RFC §5.4.1/§5.4.2 vectors plus the
   catalogue cases on both targets (`Rfc3986ResolutionTest`): relative links
   resolve against the **effective response URL after redirects**, with
   meaningful trailing slash and query-only references preserved, root/absolute
   links with dot-segment elimination, and base URLs without paths handled.
   No maintained KMP RFC 3986 library was found in a quick survey; the
   hand-rolled resolver becomes `lib/opds`'s URL module in Phase 1.
3. **URI templates** — bounded RFC 6570 subset passing the RFC §2.4/§3.2
   form vector tables (`Rfc6570ExpansionTest`): literal validation, simple
   expansion `{var}` (incl. `{x,y}` multiple-variable lists), reserved
   expansion `{+var}` (incl. pct-triplet pass-through), form-style queries
   `{?list}` and continuations `{&list}`, name validation, and **explicit
   failure** (`UnsupportedTemplateException`) for every other operator,
   modifier, brace imbalance, bare `%`, and space literal. No maintained KMP
   RFC 6570 library exists in the surveyed ecosystem, matching the plan's
   fallback "implement the declared supported level with RFC fixtures"
   (§4 "URLs, search, and navigation").
4. **Transport shape** — isolated Ktor client with `followRedirects = false`
   and a manual redirect loop with a hard bound (5 hops planned in §4):
   credentials are attached only to the initial trusted-origin request
   (scheme + host + port) and **never on a redirect hop, not even a hop that
   returns to the same origin**; cross-origin hops demonstrably carry none;
   the loop returns the **effective final URL** (§4 "effective response URL
   after redirects"), which is what relative-link resolution feeds on
   (`TransportSpikeTest`). Phase 1 builds the real transport (isolated client,
   byte/date budgets, `Retry-After`, cache keys, logging hygiene) on this
   shape with MockEngine tests.
5. **Grouping rule ("one book with editions versus a list"; §11.7 Still
   open 1)** — decided as: a listing entry **without acquisition links** whose
   target is an **unpaginated** acquisition feed whose entries **share one
   title** is a single book with editions; **anything else** — entries with
   acquisition links of their own (Calibre-style feeds), a paginated target
   feed, mixed titles, an unfetched/unknown target, or another navigation
   feed — is a list, with the telling line on same-title siblings
   (`editionsList` fallback; the wrong guess degrades to the less-committed
   shape, `GroupingRuleSpikeTest`). Verified against the Gutenberg-shape
   listing → two-edition acquisition feed fixture and a Calibre-shaped feed
   fixture. A live Calibre-served feed cross-check (byte-level) belongs to the
   Phase 4 provider QA; the model needs each entry's acquisition-link count
   (design pass-3 engineering note) to feed the "telling line" tests.

## 4. Live provider checks (read-only, no contact)

### Project Gutenberg

- Usage guidance verified live (offline_catalogs.html): OPDS is
  "primarily intended for machine-to-machine communication for use in
  applications"; the entry point is `https://www.gutenberg.org/ebooks/search.opds/`;
  "A JSON-based OPDS2 feed is currently available for testing. **Please
  contact us for details and access.**"; "We expect to sunset the existing
  XML-based OPDS feeds in 2027."
- The robot policy still emphasizes human-only automated-access exceptions;
  the OPDS interface remains their sanctioned machine interface. The plan's
  caution that a catalogue *preset* must not turn into bulk mirroring stands,
  and user-initiated acquisition remains the app's only automated access.
- HTTPS OpenSearch descriptor re-verified:
  `https://www.gutenberg.org/catalog/osd-books.xml` serves
  `application/xml` 200 and advertises **three** `Url` rules: `text/html`
  (plain `http://www.gutenberg.org/...{searchTerms}`), OPDS2-relevant
  `application/atom+xml` at a **plain-HTTP host**
  (`http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}` — no
  `{startPage?}`, no Atom profile parameter) and `application/x-suggestions+json`.
  The `m.gutenberg.org` HTTPS equivalent currently answers 504/redirects to
  the `www.gutenberg.org` HTTPS resource; the Atom search at
  `https://www.gutenberg.org/ebooks/search.opds/` answers 200 with
  `application/atom+xml`.
  → Phase 1 must support the full plain `application/atom+xml` search-response
    shape (documented compatibility case, already in §2.3) and OpenSearch
    `p={startPage?}` descriptor parsing, and the **release-blocking
    compatibility decision** for the legacy HTTP search template — or a
    Gutenberg-provided replacement descriptor — is blocked on provider
    contact (outstanding).

### Standard Ebooks (preset access note verified live)

- `/feeds` verified: full OPDS feed access is a Patrons Circle benefit —
  "when prompted enter your email address and **leave the password field
  blank**"; OPDS root `https://standardebooks.org/feeds/opds`; OPDS2 by
  `Accept: application/opds+json`.
- Only the RSS/Atom *new-releases* feed is documented as open; the OPDS feed
  gates everything (401 without credentials). The preset must read
  "Patron account needed", never "free/open".
- The placeholder terms/license statements behind their `/feeds` page (CC0 for
  their produced content, third-party content may be copyrighted) are shown
  on the page; preset detail copy will reference this page rather than
  invent a terms URL.

## 5. Budgets and Basic/HTTP policy (recorded; device QA pending)

The §4 budgets are adopted as the Phase 1 starting budgets (5 MiB decoded
feed/description response, nesting depth 64, 2,000 items per response, 5
redirects, 20 parsed pages/25 MiB disk metadata cache per profile, 2
concurrent acquisitions, 512 MiB per-EPUB download ceiling) — clearly
labelled as tuning choices, not OPDS limits.

Basic/HTTP policy for Phase 1 onward:

- HTTPS by default; an explicitly added anonymous LAN HTTP catalogue is
  allowed per platform policy (Android already permits cleartext globally;
  `androidApp/src/main/res/xml/network_security_config.xml`). **Basic
  credentials are never sent over plain HTTP** (blocked at the "password over
  HTTP" dialog per design; §10.5 keeps Digest and protected Calibre over HTTP
  unsupported in v1 with the `pwHttpBlocked` dialogs).
- Credentials only to the configured HTTPS origin (scheme+host+port), never
  on redirect hops and never cross-origin (demonstrated by the spike).
- These stay subject to device-QA validation of the tunable budgets, which is
  on the device-QA matrix (outstanding below).

## 6. iOS platform facts recorded without a device

- `iosApp/iosApp/Info.plist` currently has **no `NSAppTransportSecurity`**
  exception → a LAN `http://…` catalogue is expected to fail on iOS by ATS
  until either a scoped exception (`NSAllowsLocalNetworking`) or an explicit
  "HTTPS required on iOS" error is shipped (§10.4 decision matrix; both
  design variants—the `httpIos` dialog and the Android shared wording—remain
  in hand from pass 3).
- The iOS app's background modes (audio/processing) support downloads only
  indirectly; acquisition-on-background behavior (§10.11) remains a device
  QA topic.

## 7. Gate assessment against §7 Phase 0

**Gate: parser and transport choices are demonstrated, unverified provider
behaviors are explicit rather than assumed, and each §11.7 verification has a
recorded result.**

| §11.7 "Still open" verification | Result |
| --- | --- |
| 1. Grouping rule | **Decided** (§3 item 5 above), verified by fixtures/spike tests; live Calibre interop moves to Phase 4 QA. |
| 2. iPhone plain HTTP | **Outstanding — physical iPhone required.** Code facts recorded (§6); no code change made. |
| 3. Preset terms links | **Partially recorded**: Standard Ebooks access note + copy verified from `/feeds`; Gutenberg usage guidance verified but the **OPDS2 endpoint access requires provider contact** (outstanding), so the Gutenberg preset is withheld from the announced data list pending that confirmation. |

Phase 0's other bullets:

| Plan §7 Phase 0 bullet | Status |
| --- | --- |
| Fixtures (synthetic/licensed) in test resources incl. Gutenberg nav→variants→acquisition shape | **Done** — 12 synthetic fixtures, now retained in `lib/opds/implementation/src/commonTest/resources/opds`; RFC vectors quoted with license notes in their ported tests. |
| Validate xmlutil namespace/mixed-content/DTD behavior on Android and iOS | **Done and corrected** — matrix in §2 (the original run pinned a bogus conclusion; fixtures now start with the declaration and the DTD matrix above is the verified record); DTD rejection is demonstrated as a spike mitigation, not a library property. |
| URI-template + URL resolution choice vs RFC tests | **Done** — §3 items 2–3 (vectors green on both). |
| Confirm Gutenberg usage guidance / HTTPS search descriptor / OPDS2 access | Partial: guidance + descriptor verified; OPDS2 access **requires provider contact** → outstanding; preset withheld. |
| Budgets + Basic/HTTP policy with device QA | Budgets/policy recorded (§5); **device-QA validation outstanding**. |
| iOS cleartext vs LAN HTTP + Coil `data:` thumbnails | Code facts recorded (§6 for cleartext); **device-verification outstanding** (requires an iPhone for ATS behavior; Coil `data:` render check needs an Android device/emulator run). |
| "One book vs list" rule | **Decided** (§3 item 5). |
| Design delivered; record each §11.7 verification result | Design boards present in repo (84 boards, `design/ember/catalogues`); §11.7 table above. |

## 8. Outstanding items (deliberately not started)

These are the Phase 0 items the task instruction excludes, plus the ones that
need a physical device or provider contact; none blocks the Phase 1 protocol
core:

1. **iPhone plain-HTTP verification (§10.4 / §11.7 open 2)** — needs a
   physical iPhone: test LAN catalogue on iOS, and whether existing
   Storyteller/Audiobookshelf servers work over `http:` today; blocked →
   single-button `httpIos` dialog, allowed → the Android wording unchanged.
2. **Gutenberg OPDS2 endpoint/testing access and updated search-descriptor
   confirmation** — needs provider contact; do not block generic custom-server
   work on this provider; withhold the Gutenberg preset until then.
3. **Device QA for budgets and Basic/HTTP policy measurements (§4/§5)** —
   needs Android/iOS devices (image-heavy fixture measurements, cleartext and
   redirect behavior on-device).
4. **Coil inline `data:` thumbnail render + per-source cover loading
   (§10.7)** — needs a device/emulator; unlocks `CoilInitializer` decision and
   excludes OPDS sources from the global bearer-token origin match.
5. **Live fixture cross-checks** — a real Calibre OPDS feed (grouping rule
   byte-check) and Standard Ebooks OPDS2 shape check; belongs to provider QA
   in later phases.

## 9. What Phase 1 inherits (test-first anchors)

The fixture set (module README inventory), the recorded behavior matrix (§2),
the RFC vectors (§3 items 2–3), and the transport rules demo (§3 item 4) — to
be moved into `lib/opds/api`/`lib/opds/implementation` with the Phase 1
test-first order (URL resolution → media-type detection → OPDS1 → OPDS2 →
acquisition classifier → OpenSearch/6570 → transport rules with MockEngine).

Phase 1 requirement made explicit by the corrected §2 record: **reject any
document containing a DOCTYPE, by stopping at the DOCDECL event before any
entity text is read** — xmlutil does not do it for us (it accepts a
well-formed internal DTD, resolves internal entities, and has no expansion
limit; only external `SYSTEM` entities fail in the library itself). The spike
method `parseStoppingAtDocdecl` (`XmlStreamingBehaviorTest`,
`phase1_mitigation_stops_at_DOCDECL_before_any_entity_is_read`) is the
demonstrated shape, green on both targets, and covers the three abort paths:
DOCDECL delivered (internal DTD), reader-thrown DOCTYPE parse failure
(external `SYSTEM`), and misordered/preamble garbage. Phase 1 re-asserts this
against the moved fixtures.
